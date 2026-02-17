package ru.koalexse.aichallenge.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.ChatResponse
import java.util.concurrent.TimeUnit

interface LLMApi {
    suspend fun sendMessage(chatRequest: ChatRequest): Result<String>
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

    override suspend fun sendMessage(chatRequest: ChatRequest): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = gson.toJson(chatRequest)

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body.string()

                if (response.isSuccessful) {
                    val chatResponse = gson.fromJson(body, ChatResponse::class.java)
                    val result = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                    Result.success(result)
                } else {
                    Result.failure(Exception("API Error: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}