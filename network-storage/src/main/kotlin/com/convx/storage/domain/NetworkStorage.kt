package com.convx.storage.domain

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.IOException

enum class StorageError { AUTHENTICATION, NOT_FOUND, CONNECTION, INVALID_RESPONSE, INVALID_CONFIGURATION, LIMIT_REACHED, CREDENTIALS_UNAVAILABLE }

class StorageException(val error: StorageError, cause: Throwable? = null) : IOException(error.name, cause)

interface NetworkServerStore {
    val servers: StateFlow<List<NetworkServer>>
    suspend fun load(): List<NetworkServer>
    suspend fun save(server: NetworkServer)
    suspend fun delete(id: String)
    fun find(id: String): NetworkServer?
}

interface RemoteFile : Closeable {
    val length: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

interface NetworkFileSource : Closeable {
    suspend fun list(server: NetworkServer, folder: String): List<NetworkEntry>
    fun open(server: NetworkServer, path: String): RemoteFile
}

interface NetworkLibraryRepository {
    val servers: StateFlow<List<NetworkServer>>
    suspend fun loadServers(): List<NetworkServer>
    suspend fun save(server: NetworkServer)
    suspend fun delete(id: String)
    suspend fun test(server: NetworkServer)
    suspend fun browse(id: String, folder: String = ""): List<NetworkEntry>
    suspend fun scan(id: String, maxTracks: Int = 10_000): NetworkLibrary
    suspend fun readArtwork(uri: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray
}
