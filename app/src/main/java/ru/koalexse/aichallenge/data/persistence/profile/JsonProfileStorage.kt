package ru.koalexse.aichallenge.data.persistence.profile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Данные, хранимые в profiles.json.
 */
private data class ProfileStorageData(
    val version: Int = 1,
    val selectedProfileId: String = Profile.DEFAULT_PROFILE_ID,
    val profiles: List<PersistedProfile> = emptyList()
)

private data class PersistedProfile(
    val id: String,
    val name: String,
    val rawText: String,
    val facts: List<String>,
    val isDefault: Boolean
)

/**
 * JSON-хранилище профилей.
 *
 * Хранит список профилей и id выбранного профиля в `profiles.json`.
 * При первом обращении создаёт профиль по умолчанию.
 * Потокобезопасно через [Mutex].
 */
class JsonProfileStorage(
    private val context: Context,
    private val fileName: String = "profiles.json"
) {

    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File get() = File(context.filesDir, fileName)

    private var cachedProfiles: MutableList<Profile>? = null
    private var cachedSelectedId: String? = null

    // ==================== Public API ====================

    suspend fun getAll(): List<Profile> = mutex.withLock {
        ensureLoaded()
        cachedProfiles!!.toList()
    }

    suspend fun getById(id: String): Profile? = mutex.withLock {
        ensureLoaded()
        cachedProfiles!!.find { it.id == id }
    }

    suspend fun getSelectedId(): String = mutex.withLock {
        ensureLoaded()
        cachedSelectedId!!
    }

    suspend fun setSelectedId(id: String) {
        mutex.withLock {
            ensureLoaded()
            cachedSelectedId = id
            saveLocked()
        }
    }

    suspend fun save(profile: Profile) {
        mutex.withLock {
            ensureLoaded()
            val index = cachedProfiles!!.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                // Для default-профиля сохраняем isDefault и name
                val existing = cachedProfiles!![index]
                val toSave = if (existing.isDefault) {
                    profile.copy(isDefault = true, name = existing.name)
                } else {
                    profile
                }
                cachedProfiles!![index] = toSave
            } else {
                cachedProfiles!!.add(profile)
            }
            saveLocked()
        }
    }

    /**
     * Удаляет профиль по id. Default-профиль удалить нельзя.
     * @return true если профиль был удалён
     */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        ensureLoaded()
        val profile = cachedProfiles!!.find { it.id == id } ?: return@withLock false
        if (profile.isDefault) return@withLock false
        cachedProfiles!!.remove(profile)
        // Если удалили выбранный — переключаемся на default
        if (cachedSelectedId == id) {
            cachedSelectedId = Profile.DEFAULT_PROFILE_ID
        }
        saveLocked()
        true
    }

    // ==================== Private ====================

    private suspend fun ensureLoaded() {
        if (cachedProfiles != null && cachedSelectedId != null) return
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    val data = gson.fromJson(file.readText(), ProfileStorageData::class.java)
                    cachedProfiles = data?.profiles
                        ?.map { it.toDomain() }?.toMutableList() ?: mutableListOf()
                    cachedSelectedId = data?.selectedProfileId ?: Profile.DEFAULT_PROFILE_ID
                } else {
                    cachedProfiles = mutableListOf()
                    cachedSelectedId = Profile.DEFAULT_PROFILE_ID
                }
            } catch (_: Exception) {
                cachedProfiles = mutableListOf()
                cachedSelectedId = Profile.DEFAULT_PROFILE_ID
            }
            // Гарантируем наличие default-профиля
            if (cachedProfiles!!.none { it.isDefault }) {
                cachedProfiles!!.add(0, Profile.createDefault())
                saveLocked()
            }
        }
    }

    private suspend fun saveLocked() {
        val snapshot = cachedProfiles!!.toList()
        val selectedId = cachedSelectedId!!
        withContext(Dispatchers.IO) {
            try {
                val data = ProfileStorageData(
                    selectedProfileId = selectedId,
                    profiles = snapshot.map { it.toPersisted() }
                )
                file.writeText(gson.toJson(data))
            } catch (_: Exception) { /* не ломаем UX */ }
        }
    }

    // ==================== Mappers ====================

    private fun Profile.toPersisted() = PersistedProfile(
        id = id, name = name, rawText = rawText, facts = facts, isDefault = isDefault
    )

    private fun PersistedProfile.toDomain() = Profile(
        id = id, name = name, rawText = rawText, facts = facts, isDefault = isDefault
    )
}
