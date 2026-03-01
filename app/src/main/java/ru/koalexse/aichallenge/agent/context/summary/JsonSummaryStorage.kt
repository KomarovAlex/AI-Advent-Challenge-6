package ru.koalexse.aichallenge.agent.context.summary

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Данные для сериализации summaries в JSON
 */
private data class SummaryStorageData(
    val version: Int = CURRENT_VERSION,
    val summaries: List<ConversationSummary> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * JSON-реализация хранилища summaries.
 *
 * Хранит summaries в JSON-файле в приватной директории приложения.
 * Все операции потокобезопасны через [Mutex].
 *
 * ### Гарантии согласованности
 * Мутация кэша и запись на диск выполняются **внутри одного** `mutex.withLock` —
 * гонка между несколькими вызовами невозможна, потеря данных исключена.
 * Этот же паттерн используется в [JsonFactsStorage] и [JsonBranchStorage].
 *
 * ```
 * addSummary / clear / loadSummaries
 *   └─ mutex.withLock {
 *        изменить cachedSummaries
 *        withContext(IO) { file.writeText(...) }   ← атомарно с изменением
 *      }
 * ```
 *
 * @param context контекст приложения
 * @param fileName имя файла для хранения (по умолчанию "summaries.json")
 */
class JsonSummaryStorage(
    private val context: Context,
    private val fileName: String = "summaries.json"
) : SummaryStorage {

    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val file: File
        get() = File(context.filesDir, fileName)

    // Кэш данных в памяти для быстрого чтения
    private var cachedSummaries: MutableList<ConversationSummary>? = null

    override suspend fun getSummaries(): List<ConversationSummary> {
        return mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries?.toList() ?: emptyList()
        }
    }

    override suspend fun addSummary(summary: ConversationSummary) {
        mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries!!.add(summary)
            saveLocked()
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            cachedSummaries = mutableListOf()
            saveLocked()
        }
    }

    override suspend fun getSize(): Int {
        return mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries?.size ?: 0
        }
    }

    override suspend fun isEmpty(): Boolean {
        return mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries?.isEmpty() ?: true
        }
    }

    override suspend fun loadSummaries(summaries: List<ConversationSummary>) {
        mutex.withLock {
            cachedSummaries = summaries.toMutableList()
            saveLocked()
        }
    }

    // ==================== Private ====================

    /**
     * Загружает данные с диска, если кэш ещё не инициализирован.
     * Должен вызываться внутри [mutex].
     */
    private suspend fun ensureLoadedInternal() {
        if (cachedSummaries != null) return
        withContext(Dispatchers.IO) {
            cachedSummaries = try {
                if (file.exists()) {
                    val data = gson.fromJson(file.readText(), SummaryStorageData::class.java)
                    data?.summaries?.toMutableList() ?: mutableListOf()
                } else {
                    mutableListOf()
                }
            } catch (_: Exception) {
                // При ошибке парсинга начинаем с пустого списка
                mutableListOf()
            }
        }
    }

    /**
     * Сохраняет текущий кэш на диск.
     * Должен вызываться внутри [mutex] — snapshot и запись атомарны по отношению
     * к другим мутациям кэша, гонка между конкурентными вызовами невозможна.
     */
    private suspend fun saveLocked() {
        val snapshot = cachedSummaries?.toList() ?: emptyList()
        withContext(Dispatchers.IO) {
            try {
                file.writeText(gson.toJson(SummaryStorageData(summaries = snapshot)))
            } catch (_: Exception) {
                // Не бросаем исключение, чтобы не ломать UX
            }
        }
    }
}
