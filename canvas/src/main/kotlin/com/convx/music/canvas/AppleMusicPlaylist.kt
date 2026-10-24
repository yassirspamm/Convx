package com.convx.music.canvas

import java.net.URI
import kotlinx.serialization.json.*

data class ApplePlaylistLink(val storefront: String, val id: String) {
    companion object {
        fun parse(input: String): ApplePlaylistLink? = runCatching {
            val uri = URI(input.trim())
            if (uri.scheme != "https" || !uri.host.equals("music.apple.com", ignoreCase = true) ||
                uri.userInfo != null || uri.port != -1) return null
            val parts = uri.path.split('/').filter { it.isNotEmpty() }
            val index = parts.indexOf("playlist")
            if (index !in 0..1 || parts.size <= index + 1) return null
            val storefront = if (index == 1) parts[0] else AppleMusicCatalog.storefront
            val id = parts.last()
            if (!storefront.matches(Regex("[a-z]{2}")) || !id.matches(Regex("pl\\.[A-Za-z0-9-]+"))) return null
            ApplePlaylistLink(storefront, id)
        }.getOrNull()
    }
}

data class ApplePlaylistTrack(val title: String, val artist: String, val durationMs: Int)
data class ApplePlaylist(
    val title: String,
    val tracks: List<ApplePlaylistTrack>,
    val unsupportedTracks: Int,
    val artworkUrl: String? = null,
)

object AppleMusicPlaylist {
    suspend fun fetch(link: ApplePlaylistLink): ApplePlaylist = fetch(link) {
        AppleMusicCatalog.request(it, priority = true)
    }

    internal suspend fun fetch(link: ApplePlaylistLink, request: suspend (String) -> JsonObject?): ApplePlaylist {
        val base = "/v1/catalog/${link.storefront}/playlists/${link.id}"
        val root = request(base)
            ?: error("Playlist is unavailable")
        val playlist = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("Playlist is unavailable")
        val attributes = playlist["attributes"]?.jsonObject ?: error("Playlist has no metadata")
        val title = attributes["name"]?.jsonPrimitive?.contentOrNull
            ?: error("Playlist has no name")
        val artworkUrl = attributes["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.replace("{w}", "600")
            ?.replace("{h}", "600")
            ?.replace("{f}", "jpg")
        val tracks = mutableListOf<ApplePlaylistTrack>()
        var unsupported = 0
        var page: String? = "$base/tracks?limit=100"
        val visited = mutableSetOf<String>()
        while (page != null) {
            check(visited.add(page) && visited.size <= 1000) { "Invalid playlist pagination" }
            val response = request(page)
                ?: error("Playlist tracks are unavailable")
            val data = response["data"] as? JsonArray ?: error("Invalid playlist response")
            data.forEach { element ->
                val item = element.jsonObject
                val attrs = item["attributes"] as? JsonObject
                val name = attrs?.get("name")?.jsonPrimitive?.contentOrNull
                val artist = attrs?.get("artistName")?.jsonPrimitive?.contentOrNull
                if (item["type"]?.jsonPrimitive?.contentOrNull != "songs" || name.isNullOrBlank() || artist.isNullOrBlank()) {
                    unsupported++
                } else {
                    tracks += ApplePlaylistTrack(name, artist, attrs["durationInMillis"]?.jsonPrimitive?.intOrNull ?: 0)
                }
            }
            page = response["next"]?.jsonPrimitive?.contentOrNull?.let { next ->
                // Only follow the same playlist's relative relationship URL, never arbitrary hosts.
                val uri = URI(next)
                check(uri.scheme == null && uri.rawAuthority == null && uri.path == "$base/tracks" && uri.fragment == null) {
                    "Invalid playlist pagination"
                }
                next
            }
        }
        return ApplePlaylist(title, tracks, unsupported, artworkUrl)
    }
}
