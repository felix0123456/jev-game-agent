package com.jev.gameagent.jev

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class JevApiClient(context: Context) {

    companion object {
        private const val TAG = "JevApiClient"
        private const val PREFS_NAME = "jev_settings"
        private const val KEY_API_KEY = "jev_api_key"
        private const val KEY_ENDPOINT = "jev_endpoint"
        private const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    suspend fun decide(request: JevDecisionRequest): Result<JevDecisionResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = apiKey
                if (token.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Jev API Key is not configured."))
                }

                val jsonBody = gson.toJson(request)
                val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    val responseString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Jev API Error (${response.code}): $responseString")
                        return@withContext Result.failure(
                            RuntimeException("Jev HTTP ${response.code}: $responseString")
                        )
                    }

                    val decisionResponse = gson.fromJson(responseString, JevDecisionResponse::class.java)
                    Result.success(decisionResponse)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing Jev decide request", e)
                Result.failure(e)
            }
        }
}
