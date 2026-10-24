/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Runs a listening party for this device and keeps local playback in step with it.
 */

package com.convx.music.jam

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import com.convx.music.R
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.convx.music.extensions.metadata
import com.convx.music.extensions.toMediaItem
import com.convx.music.listentogether.ListenTogetherPlayerConnectionBridge
import com.convx.music.models.MediaMetadata
import com.convx.music.playback.PlayerConnection
import com.convx.music.playback.queues.ListQueue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** What this device knows about the party it is in. */
data class JamSession(
    val code: String,
    val you: PartyMember,
    val members: List<PartyMember> = emptyList(),
    val maxMembers: Int = 5,
    val hostOnlyControl: Boolean = false,
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
    val connected: Boolean = false,
) {
    /** The server refuses controls from everybody but the host when [hostOnlyControl] is on. */
    val canControl: Boolean get() = !hostOnlyControl || you.isHost
}

@Singleton
class JamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: ListenTogetherPlayerConnectionBridge,
    private val classic: dagger.Lazy<com.convx.music.listentogether.ListenTogetherManager>,
) {
    companion object {
        private const val TAG = "Jam"
        const val DEFAULT_SERVER = "https://bitchord-listen-together.onrender.com"

        /** Position error that is corrected with a seek; smaller drift is left alone. */
        private const val DRIFT_MS = 450L
        private const val PING_MS = 4_000L

        /** Frames our own changes cause come back as player events; ignore them for this long. */
        private const val ECHO_WINDOW_MS = 900L
        private const val QUEUE_SEND_DEBOUNCE_MS = 600L
        private const val MAX_QUEUE = 26
        private const val RECONNECT_GIVE_UP_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val client = JamClient()
    private val clock = JamClock()
    private val prefs = context.getSharedPreferences("jam", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<JamSession?>(null)
    val session: StateFlow<JamSession?> = _session.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The last thing worth telling the user: a refusal, a lost connection, a removal. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A party link opened from outside the app: joined as soon as the Party screen is up. */
    data class Invite(val code: String, val server: String?)

    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite.asStateFlow()

    fun offerInvite(code: String, server: String?) {
        _invite.value = Invite(normalise(code), server?.takeIf { it.startsWith("http") })
    }

    fun consumeInvite(): Invite? = _invite.value.also { _invite.value = null }

    /** Link that opens this party in Convx. */
    fun inviteLink(code: String): String =
        "convx://party/$code?server=" + java.net.URLEncoder.encode(serverUrl, "UTF-8")

    suspend fun warm() = client.warm(serverUrl)

    var serverUrl: String
        get() = prefs.getString("server", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString("server", value.trim().ifBlank { DEFAULT_SERVER }).apply()

    var displayName: String
        get() = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()
        set(value) = prefs.edit().putString("name", value.trim()).apply()

    private val identity: String
        get() = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    private var token: String? = null
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var queueSendJob: Job? = null
    private var startJob: Job? = null
    private var leaving = false
    private var lastSeq = -1L
    private var echoUntil = 0L

    /** Ids of the playlist as last sent or applied, so our own queue echo is not sent back. */
    private var queueSignature: List<String> = emptyList()
    private var listenedPlayer: Player? = null

    // ---- party lifecycle -------------------------------------------------------------------

    suspend fun create(name: String) = enter { client.create(serverUrl, request(name)) }

    suspend fun join(code: String, name: String) = enter { client.join(serverUrl, normalise(code), request(name)) }

    fun leave() {
        val current = _session.value ?: return
        leaving = true
        val leavingToken = token
        teardown()
        _session.value = null
        scope.launch { if (leavingToken != null) client.leave(serverUrl, current.code, leavingToken) }
    }

    fun setHostOnlyControl(enabled: Boolean) = control("setHostOnlyControl") { put("enabled", enabled) }

    private suspend fun enter(call: suspend () -> PartyMembership) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        // Being in a classic room as well would run two syncs against one player.
        runCatching { classic.get().takeIf { it.isInRoom }?.leaveRoom() }
        val waking = scope.launch {
            delay(3_000)
            _message.value = "Waking the server, this can take up to 30 seconds…"
        }
        try {
            val membership = call()
            teardown()
            leaving = false
            lastSeq = -1
            token = membership.token
            val party = membership.party
            _session.value = JamSession(
                code = membership.code,
                you = membership.you,
                members = party.members,
                maxMembers = party.maxMembers,
                hostOnlyControl = party.hostOnlyControl,
                playback = party.playback,
                queue = party.queue,
            )
            connect(membership.code, membership.token)
            applyRemote(party.playback)
        } catch (e: JamException) {
            _message.value = e.message
        } finally {
            waking.cancel()
            if (_session.value != null) _message.value = null
            _busy.value = false
        }
    }

    private fun request(name: String): JoinRequest {
        val shown = name.trim().ifBlank { displayName }.take(80)
        displayName = shown
        return JoinRequest(userId = identity, deviceId = identity, displayName = shown)
    }

    /** Codes are typed by hand: the server normalises case and look-alikes, we only strip spaces. */
    private fun normalise(code: String) = code.filterNot { it.isWhitespace() || it == '-' }.uppercase()

    // ---- socket ----------------------------------------------------------------------------

    private fun connect(code: String, token: String) {
        socket = client.open(serverUrl, code, token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    _session.value = _session.value?.copy(connected = true)
                    startPing(webSocket)
                    webSocket.send("""{"type":"sync"}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { runCatching { onFrame(text) }.onFailure { Timber.tag(TAG).w(it, "Bad frame") } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    // A refused handshake means the slot is gone; retrying will not bring it back.
                    if (response?.code == 401 || response?.code == 403 || response?.code == 404) {
                        _message.value = "The party has ended"
                        dropSession()
                    } else lost(webSocket)
                }
            }
        })
    }

    private fun lost(webSocket: WebSocket) {
        scope.launch {
            if (webSocket !== socket || leaving) return@launch
            pingJob?.cancel()
            _session.value = _session.value?.copy(connected = false)
            reconnect()
        }
    }

    private fun reconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            var wait = 1_000L
            while (!leaving && _session.value != null) {
                if (SystemClock.elapsedRealtime() - started > RECONNECT_GIVE_UP_MS) {
                    _message.value = "Lost connection to the party"
                    dropSession()
                    return@launch
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(8_000L)
                val session = _session.value ?: return@launch
                val current = token ?: return@launch
                socket?.cancel()
                connect(session.code, current)
                delay(3_000)
                if (_session.value?.connected == true) return@launch
            }
        }
    }

    private fun startPing(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                webSocket.send("""{"type":"ping","clientMs":${System.currentTimeMillis()}}""")
                delay(PING_MS)
            }
        }
    }

    private fun teardown() {
        pingJob?.cancel()
        reconnectJob?.cancel()
        queueSendJob?.cancel()
        startJob?.cancel()
        socket?.close(1000, null)
        socket = null
        token = null
        clock.reset()
    }

    private fun dropSession() {
        teardown()
        _session.value = null
    }

    // ---- frames in -------------------------------------------------------------------------

    private fun onFrame(text: String) {
        val frame = client.json.parseToJsonElement(text).jsonObject
        when (frame["type"]?.jsonPrimitive?.contentOrNull) {
            "welcome" -> frame["party"]?.let { party ->
                val snapshot = client.json.decodeFromJsonElement<PartySnapshot>(party)
                frame["you"]?.let { you -> update { it.copy(you = client.json.decodeFromJsonElement(you)) } }
                update {
                    it.copy(
                        members = snapshot.members, maxMembers = snapshot.maxMembers,
                        hostOnlyControl = snapshot.hostOnlyControl, queue = snapshot.queue,
                    )
                }
                onState(snapshot.playback)
            }
            "state" -> frame["playback"]?.let { onState(client.json.decodeFromJsonElement(it)) }
            "queue" -> frame["queue"]?.let { queue ->
                val incoming = client.json.decodeFromJsonElement<PartyQueue>(queue)
                update { it.copy(queue = incoming) }
                applyQueue(incoming)
            }
            "members" -> update { current ->
                current.copy(
                    members = frame["members"]?.let { client.json.decodeFromJsonElement<List<PartyMember>>(it) } ?: current.members,
                    maxMembers = frame["maxMembers"]?.jsonPrimitive?.intOrNull ?: current.maxMembers,
                    hostOnlyControl = frame["hostOnlyControl"]?.jsonPrimitive?.booleanOrNull ?: current.hostOnlyControl,
                ).let { next -> next.copy(you = next.members.firstOrNull { it.memberId == current.you.memberId } ?: current.you) }
            }
            "pong" -> {
                val sent = frame["clientMs"]?.jsonPrimitive?.longOrNull
                val server = frame["serverMs"]?.jsonPrimitive?.longOrNull
                if (sent != null && server != null) clock.observe(sent, server, System.currentTimeMillis())
            }
            "error" -> _message.value = frame["message"]?.jsonPrimitive?.contentOrNull ?: frame["error"]?.jsonPrimitive?.contentOrNull
            "bye" -> {
                _message.value = if (frame["reason"]?.jsonPrimitive?.contentOrNull == "kicked") "You were removed from the party" else null
                dropSession()
            }
        }
    }

    private fun update(change: (JamSession) -> JamSession) {
        _session.value = _session.value?.let(change)
    }

    private fun onState(playback: PartyPlayback) {
        // Every mutation bumps seq; an older or equal one is a duplicate or a straggler.
        if (playback.seq <= lastSeq) return
        lastSeq = playback.seq
        update { it.copy(playback = playback) }
        applyRemote(playback)
        val session = _session.value ?: return
        if (playback.queueSeq != session.queue.seq) socket?.send("""{"type":"syncQueue"}""")
    }

    // ---- party -> local player -------------------------------------------------------------

    private fun applyRemote(playback: PartyPlayback) {
        val connection = bridge.playerConnection.value ?: return
        val player = connection.player
        val track = playback.track ?: return
        echoUntil = SystemClock.elapsedRealtime() + ECHO_WINDOW_MS
        val serverNow = clock.serverNow(System.currentTimeMillis())
        val target = JamSync.positionAt(playback, serverNow)

        if (player.currentMediaItem?.mediaId != track.videoId) {
            startTrack(connection, track, target, playback.isPlaying)
            return
        }
        if (abs(player.currentPosition - target) > DRIFT_MS) player.seekTo(target)
        startJob?.cancel()
        if (playback.isPlaying) {
            val delayMs = JamSync.startDelayMs(playback, serverNow)
            if (!player.playWhenReady) {
                startJob = scope.launch { delay(delayMs); echoUntil = SystemClock.elapsedRealtime() + ECHO_WINDOW_MS; connection.play() }
            }
        } else if (player.playWhenReady) {
            connection.pause()
        }
    }

    private fun startTrack(connection: PlayerConnection, track: PartyTrack, position: Long, playing: Boolean) {
        val queue = _session.value?.queue
        val inQueue = queue != null && queue.index in queue.items.indices && queue.items[queue.index].videoId == track.videoId
        val items = if (inQueue) queue!!.items.map { toMediaItem(it) } else listOf(toMediaItem(track))
        queueSignature = items.map { it.mediaId }
        connection.playQueue(ListQueue(title = null, items = items, startIndex = if (inQueue) queue!!.index else 0, position = position), force = true)
        if (!playing) scope.launch { delay(250); echoUntil = SystemClock.elapsedRealtime() + ECHO_WINDOW_MS; connection.pause() }
    }

    /** A queue change that leaves the playing track alone only swaps what comes after it. */
    private fun applyQueue(queue: PartyQueue) {
        val connection = bridge.playerConnection.value ?: return
        val player = connection.player
        if (queue.items.isEmpty() || queue.index !in queue.items.indices) return
        if (player.currentMediaItem?.mediaId != queue.items[queue.index].videoId) return
        val upcoming = queue.items.drop(queue.index + 1).map { toMediaItem(it) }
        val signature = listOf(player.currentMediaItem!!.mediaId) + upcoming.map { it.mediaId }
        if (signature == playlistIds(player).drop(player.currentMediaItemIndex)) return
        echoUntil = SystemClock.elapsedRealtime() + ECHO_WINDOW_MS
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
        player.addMediaItems(upcoming)
        queueSignature = playlistIds(player)
    }

    // ---- local player -> party -------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        private fun suppressed() = SystemClock.elapsedRealtime() < echoUntil || !canSend()

        /** A guest's own change while the host holds the controls: undo it and say why. */
        private fun refused(): Boolean {
            if (SystemClock.elapsedRealtime() < echoUntil) return false
            val session = _session.value ?: return false
            if (!session.connected || session.canControl) return false
            applyRemote(session.playback)
            applyQueue(session.queue)
            Toast.makeText(context, R.string.jam_host_only_toast, Toast.LENGTH_SHORT).show()
            return true
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (refused() || suppressed()) return
            val player = listenedPlayer ?: return
            control(if (playWhenReady) "play" else "pause") { put("positionMs", player.currentPosition) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK || refused() || suppressed()) return
            control("seek") { put("positionMs", newPosition.positionMs) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null || refused() || suppressed()) return
            val session = _session.value ?: return
            if (session.playback.track?.videoId == mediaItem.mediaId) return
            val track = toTrack(mediaItem) ?: run {
                _message.value = "Files on this phone can't be shared with the party"
                return
            }
            val playing = listenedPlayer?.playWhenReady ?: true
            control("setTrack") {
                put("track", client.json.encodeToJsonElement(track))
                put("positionMs", 0L)
                put("isPlaying", playing)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || refused() || suppressed()) return
            queueSendJob?.cancel()
            queueSendJob = scope.launch {
                delay(QUEUE_SEND_DEBOUNCE_MS)
                sendQueue()
            }
        }
    }

    // Declared after [playerListener] on purpose: the collector below runs immediately on the main
    // thread, and a listener that is still null makes ExoPlayer throw a NullPointerException.
    init {
        scope.launch {
            bridge.playerConnection.collectLatest { connection ->
                listenedPlayer?.removeListener(playerListener)
                listenedPlayer = connection?.player?.also { it.addListener(playerListener) }
            }
        }
    }

    private fun sendQueue() {
        val player = listenedPlayer ?: return
        if (!canSend() || player.mediaItemCount == 0) return
        val ids = playlistIds(player)
        if (ids == queueSignature) return
        queueSignature = ids
        // The server keeps the playing track plus up to 25 upcoming; history stays on each device.
        val window = (player.currentMediaItemIndex until player.mediaItemCount).take(MAX_QUEUE)
            .mapNotNull { toTrack(player.getMediaItemAt(it)) }
        if (window.isEmpty()) return
        control("setQueue") {
            put("queue", client.json.encodeToJsonElement(window))
            put("queueIndex", 0)
        }
    }

    private fun canSend(): Boolean = _session.value?.let { it.connected && it.canControl } == true

    private fun control(action: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val webSocket = socket ?: return
        val frame = buildJsonObject {
            put("type", "control")
            put("action", action)
            build()
        }
        client.send(webSocket, frame)
    }

    // ---- conversions -----------------------------------------------------------------------

    private fun playlistIds(player: Player): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun toTrack(item: MediaItem): PartyTrack? {
        val meta = item.metadata ?: return null
        // Files on this phone mean nothing to the other devices.
        if (meta.isLocalFile) return null
        return PartyTrack(
            videoId = meta.id,
            title = meta.title,
            artist = meta.artists.joinToString { it.name },
            thumbnailUrl = meta.thumbnailUrl,
            durationMs = meta.duration.takeIf { it > 0 }?.times(1000L),
        )
    }

    private fun toMediaItem(track: PartyTrack): MediaItem = MediaMetadata(
        id = track.videoId,
        title = track.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
        duration = track.durationMs?.div(1000)?.toInt() ?: -1,
        thumbnailUrl = track.thumbnailUrl,
    ).toMediaItem()
}
