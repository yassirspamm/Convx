package com.convx.storage.domain

import java.net.URI
import java.util.Locale

object RemotePath {
    fun normalize(value: String): String {
        require('\u0000' !in value) { "Invalid folder path" }
        val parts = value.replace('\\', '/').split('/').filter(String::isNotEmpty)
        require(parts.none { it == ".." }) { "Folder path escapes the source" }
        return parts.filterNot { it == "." }.joinToString("/")
    }

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")
    fun name(path: String): String = normalize(path).substringAfterLast('/')
    fun child(folder: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..") { "Invalid file name" }
        return normalize(listOf(folder, name).filter(String::isNotEmpty).joinToString("/"))
    }
}

data class NetworkMediaLocation(val serverId: String, val path: String)

object NetworkMediaUri {
    const val Scheme = "convx-network"

    fun create(serverId: String, path: String): String {
        require(serverId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid server identifier" }
        return URI(Scheme, serverId, "/" + RemotePath.normalize(path), null, null).toASCIIString()
    }

    fun parse(value: String): NetworkMediaLocation? = try {
        val uri = URI(value)
        if (uri.scheme != Scheme || uri.rawAuthority.isNullOrBlank() || uri.query != null || uri.fragment != null || uri.userInfo != null) null
        else NetworkMediaLocation(uri.rawAuthority, RemotePath.normalize(uri.path.orEmpty()))
    } catch (_: Exception) { null }
}

data class NetworkEntry(val path: String, val name: String, val directory: Boolean, val size: Long = -1)

data class NetworkLibrary(val tracks: List<NetworkTrack>, val truncated: Boolean = false)

data class NetworkTrack(val id: String, val title: String, val artist: String?, val album: String?, val artwork: String?, val size: Long)

object NetworkMedia {
    private val audioExtensions = setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "aif", "aiff", "webm")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions
    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions

    fun track(serverId: String, entry: NetworkEntry, images: List<NetworkEntry>): NetworkTrack {
        val stem = entry.name.substringBeforeLast('.').trim()
        val parts = stem.split(" - ", limit = 2)
        val artist = parts.first().trim().takeIf { parts.size == 2 && it.isNotBlank() && it.toIntOrNull() == null }
        val title = if (parts.size == 2) parts[1].trim().ifBlank { stem } else stem
        val parent = RemotePath.parent(entry.path)
        val artwork = images.filter { RemotePath.parent(it.path) == parent }.minByOrNull {
            when (it.name.substringBeforeLast('.').lowercase(Locale.ROOT)) {
                stem.lowercase(Locale.ROOT) -> 0
                "cover" -> 1
                "folder" -> 2
                "front" -> 3
                "albumart" -> 4
                else -> 5
            }
        }?.let { NetworkMediaUri.create(serverId, it.path) }
        return NetworkTrack(NetworkMediaUri.create(serverId, entry.path), title, artist, parent.substringAfterLast('/').takeIf(String::isNotBlank), artwork, entry.size)
    }
}
