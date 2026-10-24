package com.music.spine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ModuleProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `settings use each setting value rather than a shared value object`() {
        val context = json.parseToJsonElement(ModuleManager.contextArg(mapOf("quality" to "HI_RES_DOLBY", "dolbyAtmos" to "false"))).jsonObject
        val settings = context.getValue("settings").jsonObject
        assertEquals("HI_RES_DOLBY", settings.getValue("quality").jsonObject.getValue("value").jsonPrimitive.content)
        assertFalse(settings.containsKey("value"))
    }

    @Test fun `addon manifest supplies its declared quality options and default`() {
        val manifest = json.decodeFromString<ModuleManifest>("""{"noStreamCache":true,"settings":{"quality":{"type":"selector","defaultValue":"HI_RES","options":[{"label":"Hi-Res with Dolby Atmos","value":"HI_RES_DOLBY"},{"label":"CD Quality","value":"CD"}]}}}""")
        assertTrue(manifest.noStreamCache)
        assertEquals("HI_RES", manifest.defaults["quality"])
        assertEquals("HI_RES_DOLBY", manifest.settings.getValue("quality").options.first().value)
    }

    @Test fun `nullable streams and root HLS metadata decode`() {
        assertNull(json.decodeFromString<ModuleStreamResponse>("""{"streamUrl":null}""").streamUrl)
        val stream = json.decodeFromString<ModuleStreamResponse>("""{"streamUrl":"https://example.com/audio.m3u8","mimeType":"application/vnd.apple.mpegurl","streamType":"hls","track":{"codec":"EAC3_JOC","format":"m4a","bitrate":768}}""")
        assertEquals("hls", stream.streamType)
        assertEquals("EAC3_JOC", stream.track?.codec)
        assertEquals(768, stream.track?.bitrate)
    }

    @Test fun `concurrent duplicate calls execute once and cache completed results`() = runBlocking {
        val calls = SharedModuleCalls<Int>(60_000)
        val count = AtomicInteger()
        val answers = (1..20).map { async { calls.get("same") { delay(20); Result.success(count.incrementAndGet()) } } }.awaitAll()
        assertTrue(answers.all { it.getOrThrow() == 1 })
        assertEquals(1, calls.get("same") { Result.success(count.incrementAndGet()) }.getOrThrow())
    }

    @Test fun `no cache calls and failures can be retried`() = runBlocking {
        val calls = SharedModuleCalls<Int>(0)
        assertTrue(calls.get("key") { Result.failure(IllegalStateException()) }.isFailure)
        assertEquals(1, calls.get("key") { Result.success(1) }.getOrThrow())
        assertEquals(2, calls.get("key") { Result.success(2) }.getOrThrow())
    }
}
