package com.music.innertube

import com.music.innertube.models.SongItem
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

data class OfficialVideo(val songId: String, val videoId: String)

class OfficialVideoResolver(
    private val search: suspend (String) -> Result<List<SongItem>> = { query ->
        YouTube.search(query, SearchFilter.FILTER_VIDEO).map { result -> result.items.filterIsInstance<SongItem>() }
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val video: OfficialVideo?, val expiresAt: Long)
    private val cache = LinkedHashMap<String, Cached>(128, .75f, true)

    suspend fun resolve(id: String, title: String, artists: List<String>, duration: Int, videoType: String?): Result<OfficialVideo?> {
        if (!id.matches(YouTubeId)) return Result.success(null)
        if (videoType == "MUSIC_VIDEO_TYPE_OMV" || videoType == "MUSIC_VIDEO_TYPE_UGC") return Result.success(OfficialVideo(id, id))
        synchronized(cache) { cache[id]?.takeIf { it.expiresAt > nowMillis() } }?.let { return Result.success(it.video) }
        if (title.isBlank() || artists.all(String::isBlank)) return Result.success(null)
        val response = withTimeoutOrNull(6_000) { search("$title ${artists.first(String::isNotBlank)}".trim()) }
            ?: return Result.failure(IOException("Video lookup timed out"))
        val candidates = response.getOrElse { return Result.failure(it) }
        val video = candidates.take(6).firstOrNull { matchesOfficialVideo(it, title, artists, duration) }
            ?.let { OfficialVideo(id, it.id) }
        synchronized(cache) {
            cache[id] = Cached(video, nowMillis() + if (video == null) 300_000 else 3_600_000)
            while (cache.size > 128) cache.remove(cache.keys.first())
        }
        return Result.success(video)
    }

    companion object {
        private val YouTubeId = Regex("[A-Za-z0-9_-]{11}")
        private val FeaturedArtists = Regex("\\s*\\((?:feat\\.?|ft\\.?|featuring)\\s+[^)]*\\)", RegexOption.IGNORE_CASE)

        fun matchesOfficialVideo(video: SongItem, title: String, artists: List<String>, duration: Int): Boolean {
            if (video.musicVideoType != "MUSIC_VIDEO_TYPE_OMV" || !video.id.matches(YouTubeId)) return false
            val normalizedTitle = normalize(title.replace(FeaturedArtists, ""))
            if (normalizedTitle.isEmpty() || !normalize(video.title).contains(normalizedTitle)) return false
            val names = artists.map(::normalize).filter(String::isNotEmpty)
            if (names.isEmpty()) return false
            val sameArtist = names.any { name ->
                video.artists.any { normalize(it.name).contains(name) } || normalize(video.title).contains(name)
            }
            if (!sameArtist) return false
            val length = video.duration ?: return true
            return duration <= 0 || length.toLong() in (duration.toLong() - 15)..(duration.toLong() + 180)
        }

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    }
}
