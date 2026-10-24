package com.convx.music.utils

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Lookup for the optional handwritten wordmark used when Apple Music has no artist logo. */
object ArtistSignature {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    suspend fun findUrl(artistName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = Uri.encode(artistName.trim())
            val request = Request.Builder()
                .url("https://artist-signature.onrender.com/v1/name/$name?format=svg&type=handwritten")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val signatures = JSONObject(response.body?.string().orEmpty())
                    .optJSONArray("signatures") ?: return@use null
                (0 until signatures.length()).firstNotNullOfOrNull { index ->
                    signatures.optJSONObject(index)
                        ?.optJSONObject("asset")
                        ?.optString("url")
                        ?.takeIf { it.startsWith("https://") }
                }
            }
        }.getOrNull()
    }
}
