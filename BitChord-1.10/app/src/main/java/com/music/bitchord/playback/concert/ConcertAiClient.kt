package com.music.bitchord.playback.concert

import android.content.Context
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Small OpenAI-compatible chat-completions client for concert sequencing. */
object ConcertAiClient {
    suspend fun designSet(context: Context, concept: String, candidates: List<Pair<String, String>>, count: Int): String? =
        withContext(Dispatchers.IO) {
            val key = ConcertAiSettings.apiKey(context)
            if (key.isBlank()) return@withContext null
            val candidateJson = JSONArray().apply {
                candidates.forEach { (title, artist) ->
                    put(JSONObject().put("title", title).put("artist", artist))
                }
            }
            val system = """
                You are an expert live concert set-list designer. Create a believable running order for: "$concept".
                Prefer real songs by the named artist or songs genuinely fitting the requested concept.
                Build a concert arc with opener, rising, peak and closer roles. The first song must be opener,
                the final song must be closer, rising songs build momentum, and peak songs contain the biggest
                moments. Keep the order intentional, not alphabetical. Use the candidate list as the main
                source of songs and do not invent obscure tracks when a candidate fits.
                Choose exactly $count songs. Output ONLY a JSON array in performance order:
                [{"title":"...","artist":"...","role":"opener|rising|peak|closer"}].
                No markdown, no commentary.
                Candidates: $candidateJson
            """.trimIndent()
            val body = JSONObject().apply {
                put("model", ConcertAiSettings.model(context))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", concept))
                })
                put("temperature", 0.35)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(ConcertAiSettings.baseUrl(context))
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            runCatching {
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    json.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                }
            }.getOrNull()
        }
}
