package com.convx.storage.data

import com.convx.storage.domain.*
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class SmbFileSource : NetworkFileSource {
    private val sessions = ConcurrentHashMap<String, Session>()

    override suspend fun list(server: NetworkServer, folder: String): List<NetworkEntry> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val source = server as? NetworkServer.Smb ?: throw StorageException(StorageError.INVALID_CONFIGURATION)
        val relative = RemotePath.normalize(folder)
        val full = fullPath(source, relative)
        session(source).share(source).list(full.replace('/', '\\')).mapNotNull { info ->
            coroutineContext.ensureActive()
            val name = info.fileName
            if (name == "." || name == "..") null else NetworkEntry(
                RemotePath.child(relative, name), name,
                info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                info.endOfFile,
            )
        }.sortedWith(compareBy<NetworkEntry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    override fun open(server: NetworkServer, path: String): RemoteFile {
        val source = server as? NetworkServer.Smb ?: throw StorageException(StorageError.INVALID_CONFIGURATION)
        val handle = session(source).share(source).openFile(
            fullPath(source, path).replace('/', '\\'), EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        try {
            val length = handle.length
            return object : RemoteFile {
                override val length = length
                override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = handle.read(buffer, position, offset, length)
                override fun close() { handle.close() }
            }
        } catch (error: Exception) { handle.close(); throw error }
    }

    fun invalidate(id: String) { sessions.remove(id)?.close() }
    override fun close() { sessions.values.forEach { it.close() }; sessions.clear() }

    private fun session(server: NetworkServer.Smb): Session = sessions.computeIfAbsent(server.id) { Session() }
    private fun fullPath(server: NetworkServer.Smb, relative: String): String =
        listOf(server.folder, RemotePath.normalize(relative)).filter(String::isNotEmpty).joinToString("/")

    private class Session : java.io.Closeable {
        private var settings: NetworkServer.Smb? = null
        private var client: SMBClient? = null
        private var connection: Connection? = null
        private var disk: DiskShare? = null

        @Synchronized
        fun share(server: NetworkServer.Smb): DiskShare {
            disk?.takeIf { settings == server && connection?.isConnected == true }?.let { return it }
            close()
            val nextClient = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build())
            try {
                val nextConnection = nextClient.connect(server.host, server.port)
                val credentials = server.credentials
                val authentication = if (credentials.username.isBlank() && credentials.password.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(credentials.username, credentials.password.toCharArray(), credentials.domain)
                val session = nextConnection.authenticate(authentication)
                val share = session.connectShare(server.share) as? DiskShare ?: throw StorageException(StorageError.INVALID_CONFIGURATION)
                settings = server
                client = nextClient
                connection = nextConnection
                disk = share
                return share
            } catch (error: Exception) { nextClient.close(); throw error }
        }

        @Synchronized
        override fun close() {
            runCatching { disk?.close() }
            runCatching { connection?.close() }
            runCatching { client?.close() }
            disk = null
            connection = null
            client = null
            settings = null
        }
    }
}
