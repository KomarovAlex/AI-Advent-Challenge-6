package ru.koalexse.aichallenge.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.ChatResponse
import ru.koalexse.aichallenge.domain.StreamOptions
import ru.koalexse.aichallenge.domain.StreamResult
import java.util.concurrent.TimeUnit

interface LLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StreamResult>
}

class OpenAIApi(
    private val apiKey: String,
    private val url: String,
) : LLMApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // Бесконечный таймаут на чтение
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    override fun sendMessageStream(chatRequest: ChatRequest): Flow<StreamResult> = flow<StreamResult> {
        val streamRequest = chatRequest.copy(
            stream = true,
            stream_options = StreamOptions(include_usage = true)
        )
        val requestBody = gson.toJson(streamRequest)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Connection", "keep-alive")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("API Error: ${response.code}")
            }

            response.body.let { responseBody ->
                val source = responseBody.source()
                val buffer = okio.Buffer()

                while (!source.exhausted()) {
                    source.read(buffer, 8192)

                    while (!buffer.exhausted()) {
                        val line = buffer.readUtf8Line() ?: continue
                        processLine(line)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.net.SocketTimeoutException) {
                // Логируем таймаут
                println("Timeout occurred but continuing: ${e.message}")
            } else {
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<StreamResult>.processLine(line: String) {
        if (line.startsWith("data: ")) {
            val data = line.removePrefix("data: ").trim()
            if (data != "[DONE]") {
                try {
                    val chatResponse = gson.fromJson(data, ChatResponse::class.java)

                    val content = chatResponse.choices.firstOrNull()?.delta?.content
                    if (!content.isNullOrEmpty()) {
                        emit(StreamResult.Content(content))
                    }

                    chatResponse.usage?.let { usage ->
                        emit(StreamResult.TokenUsage(usage))
                    }
                } catch (_: Exception) {
                    // Skip malformed JSON chunks
                }
            }
        }
    }
}
