/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from convx upstream (79b02439, 1ef1181a) — Unison Lyrics
 * Library client: https://unison.boidu.dev
 * Data licensed under ODbL-1.0. Attribution required:
 * "Lyrics from Unison (https://unison.boidu.dev)"
 */

package com.convx.music.lyrics

import com.convx.music.betterlyrics.TTMLParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
private data class UnisonLyricsData(
    val id: Long,
    val song: String? = null,
    val artist: String? = null,
    val lyrics: String? = null,
    val format: String? = null,
    val syncType: String? = null,
    val score: Double? = null,
    val effectiveScore: Double? = null,
    val duration: Double? = null,
)

@Serializable
private data class UnisonLyricsResponse(val success: Boolean, val data: UnisonLyricsData? = null)

@Serializable
private data class UnisonSearchResponse(val success: Boolean, val data: List<UnisonLyricsData> = emptyList())

object UnisonClient {

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { isLenient = true; ignoreUnknownKeys = true })
            }
            defaultRequest { url("https://unison.boidu.dev") }
            expectSuccess = false
        }
    }

    private val titleCleanupPatterns = listOf(
        Regex("""^\s*[\[(【《〈＜].*?[\])】》〉＞]\s*"""),
        Regex("""\s*[\[(【《〈＜].*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?[\])】》〉＞]""", RegexOption.IGNORE_CASE),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators =
        listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (sep in artistSeparators) {
            if (cleaned.contains(sep, ignoreCase = true)) {
                cleaned = cleaned.split(sep, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private suspend fun fetchBest(
        videoId: String?,
        song: String?,
        artist: String?,
        album: String?,
        duration: Int?,
    ): UnisonLyricsData? = runCatching {
        client.get("/lyrics") {
            if (videoId != null) parameter("v", videoId)
            if (song != null) parameter("song", song)
            if (artist != null) parameter("artist", artist)
            if (album != null) parameter("album", album)
            if (duration != null && duration > 0) parameter("duration", duration)
        }.body<UnisonLyricsResponse>().takeIf { it.success }?.data
    }.getOrNull()

    private suspend fun search(
        song: String?,
        artist: String?,
        album: String?,
        duration: Int?,
    ): List<UnisonLyricsData> = runCatching {
        client.get("/lyrics/search") {
            if (song != null) parameter("song", song)
            if (artist != null) parameter("artist", artist)
            if (album != null) parameter("album", album)
            if (duration != null && duration > 0) parameter("duration", duration)
        }.body<UnisonSearchResponse>().takeIf { it.success }?.data ?: emptyList()
    }.getOrDefault(emptyList())

    /** TTML -> LRC + word timing via [TTMLParser]; LRC/plain passed through as-is. */
    private fun convertLyrics(raw: String, format: String?): String {
        val isTtml = format?.lowercase() == "ttml" || raw.trimStart().startsWith("<tt", ignoreCase = true)
        if (!isTtml) return raw
        val parsed = TTMLParser.parseTTML(raw)
        return if (parsed.isNotEmpty()) TTMLParser.toLRC(parsed) else raw
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        videoId: String?,
    ): Result<String> = runCatching {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        val effectiveDuration = duration.takeIf { it > 0 }

        fetchBest(videoId, cleanedTitle, cleanedArtist, album, effectiveDuration)?.let { item ->
            item.lyrics?.takeIf { it.isNotBlank() }?.let { return@runCatching convertLyrics(it, item.format) }
        }

        val results = search(cleanedTitle, cleanedArtist, album, effectiveDuration).filter { it.lyrics != null }
        val best = sortResults(results, cleanedTitle, cleanedArtist, duration).firstOrNull()
        val bestLyrics = best?.lyrics ?: throw IllegalStateException("Lyrics unavailable")
        convertLyrics(bestLyrics, best.format)
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        videoId: String?,
        callback: (String) -> Unit,
    ) {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        val results = search(cleanedTitle, cleanedArtist, album, duration.takeIf { it > 0 })
            .filter { it.lyrics != null }
        var count = 0
        for (item in sortResults(results, cleanedTitle, cleanedArtist, duration)) {
            currentCoroutineContext().ensureActive()
            if (count >= 5) break
            val raw = item.lyrics ?: continue
            if (raw.isNotBlank()) {
                callback(convertLyrics(raw, item.format))
                count++
            }
        }
    }

    private fun sortResults(
        results: List<UnisonLyricsData>,
        title: String,
        artist: String,
        duration: Int,
    ): List<UnisonLyricsData> = results.sortedByDescending { item ->
        var score = (item.effectiveScore ?: item.score ?: 0.0) * 0.4
        score += (stringSimilarity(title, item.song ?: "") + stringSimilarity(artist, item.artist ?: "")) / 2.0 * 0.4
        if (item.syncType == "linesync" || item.syncType == "richsync") score += 0.1
        if (duration > 0 && item.duration != null) {
            val diff = abs(item.duration - duration)
            score += when {
                diff <= 2 -> 0.1
                diff <= 10 -> 0.05
                else -> 0.0
            }
        }
        score
    }

    private fun stringSimilarity(a: String, b: String): Double {
        val s1 = a.trim().lowercase()
        val s2 = b.trim().lowercase()
        return when {
            s1 == s2 -> 1.0
            s1.isEmpty() || s2.isEmpty() -> 0.0
            s1.contains(s2) || s2.contains(s1) -> 0.8
            else -> 1.0 - (levenshtein(s1, s2).toDouble() / maxOf(s1.length, s2.length))
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
