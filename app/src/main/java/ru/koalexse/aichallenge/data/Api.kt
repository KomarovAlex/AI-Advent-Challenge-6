package ru.koalexse.aichallenge.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatResponse
import ru.koalexse.aichallenge.domain.Message

interface LLMApi {
    suspend fun sendMessage(messages: List<Message>): Result<String>
}

class OpenAIApi(
    private val apiKey: String,
    private val url: String,
    private val model:String,
) : LLMApi {
    private val client = OkHttpClient()
    private val gson = Gson()

    override suspend fun sendMessage(messages: List<Message>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiMessages = messages.filter { !it.isLoading }.map {
                    ApiMessage(
                        role = if (it.isUser) "user" else "assistant",
                        content = it.text
                    )
                }

                val requestBody = gson.toJson(
                    mapOf(
                        "model" to model,
                        "messages" to apiMessages
                    )
                )

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && body != null) {
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