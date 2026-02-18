package ru.koalexse.aichallenge.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.ChatResponse
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

interface LLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<String>
}

class OpenAIApi(
    private val apiKey: String,
    private val url: String,
) : LLMApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    override fun sendMessageStream(chatRequest: ChatRequest): Flow<String> = flow {
        val streamRequest = chatRequest.copy(stream = true)
        val requestBody = gson.toJson(streamRequest)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("API Error: ${response.code}")
        }

        val reader: BufferedReader = response.body.byteStream().bufferedReader()

        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data != "[DONE]") {
                        try {
                            val chatResponse = gson.fromJson(data, ChatResponse::class.java)
                            val content = chatResponse.choices.firstOrNull()?.delta?.content
                            if (!content.isNullOrEmpty()) {
                                emit(content)
                            }
                        } catch (_: Exception) {
                            // Skip malformed JSON chunks
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
