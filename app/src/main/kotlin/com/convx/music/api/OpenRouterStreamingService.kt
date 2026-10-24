/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import timber.log.Timber

object OpenRouterStreamingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    fun streamTranslation(
        text: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String
    ): Flow<StreamChunk> = flow {
        if (text.isBlank()) {
            emit(StreamChunk.Error("Input text is empty"))
            return@flow
        }

        val lineCount = text.lines().size
        
        Timber.d("Starting streaming translation for $lineCount lines")

        try {
            val request = buildRequest(text, targetLanguage, apiKey, baseUrl, model, mode, lineCount)
            client.newCall(request).execute().use { response ->
                Timber.d("Got streaming response: ${response.code}")

                if (!response.isSuccessful) {
                    emit(StreamChunk.Error("Translation failed: ${responseError(response)}"))
                    return@flow
                }

                val reader = BufferedReader(InputStreamReader(response.body.byteStream()))
                val content = readStream(reader, lineCount)
                if (!content.sawDoneMarker && content.text.isNotEmpty()) {
                    Timber.w("Stream ended without [DONE] marker, attempting to parse content")
                    emitTranslationResult(content.text, lineCount)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Streaming error")
            emit(StreamChunk.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(
        text: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        lineCount: Int,
    ): Request {
        val systemPrompt = """You are a precise lyrics translation assistant. Your output must ALWAYS be a valid JSON array of strings.

CRITICAL RULES:
1. Output ONLY a JSON array: ["line1", "line2", "line3"]
2. NO explanations, NO questions, NO additional text
3. Each input line maps to exactly one output line
4. Preserve empty lines as empty strings ""
5. Return EXACTLY $lineCount items in the array
6. If uncertain, provide best approximation but maintain line count"""

        val userPrompt = when (mode) {
            "Transcribed" -> """Transcribe/transliterate the following $lineCount lines phonetically into $targetLanguage script.

CRITICAL REQUIREMENTS:
- Convert the SOUND/PRONUNCIATION of the original text into $targetLanguage script
- DO NOT translate the meaning - only represent how the original words SOUND
- Use the native script of $targetLanguage
- Preserve the original pronunciation as closely as possible
- Keep punctuation and formatting

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings."""

            else -> """Translate the following $lineCount lines to $targetLanguage.

IMPORTANT:
- Provide natural, accurate translation
- Maintain poetic flow and meaning
- Keep punctuation appropriate for target language
- Preserve line-by-line structure exactly

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings."""
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
        }
        val jsonBody = JSONObject().apply {
            if (model.isNotBlank()) put("model", model)
            put("messages", messages)
            put("stream", true)
            put("temperature", 0.3)
            put("max_tokens", lineCount * 100)
        }

        return Request.Builder()
            .url(baseUrl.ifBlank { "https://openrouter.ai/api/v1/chat/completions" })
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}")
            }
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/cosmictaserdev-creator/Convx")
            .addHeader("X-Title", "convx")
            .post(jsonBody.toString().toRequestBody(JSON))
            .build()
    }

    private suspend fun FlowCollector<StreamChunk>.readStream(
        reader: BufferedReader,
        expectedLineCount: Int,
    ): StreamContent {
        val content = StringBuilder()
        var chunkCount = 0

        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data: ")) continue

            val data = line.substring(6)
            if (data == "[DONE]") {
                Timber.d("Streaming complete, received $chunkCount chunks")
                val fullContent = content.toString()
                emitTranslationResult(fullContent, expectedLineCount, logDetails = true)
                return StreamContent(fullContent, sawDoneMarker = true)
            }

            val chunk = parseContentChunk(data) ?: continue
            content.append(chunk)
            chunkCount++
            emit(StreamChunk.Content(chunk))
        }

        return StreamContent(content.toString(), sawDoneMarker = false)
    }

    private fun parseContentChunk(data: String): String? = try {
        val jsonObject = json.parseToJsonElement(data).jsonObject
        val choices = jsonObject["choices"]?.jsonArray
        choices?.get(0)?.jsonObject?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    } catch (e: Exception) {
        Timber.v("Ignored malformed chunk: ${e.message}")
        null
    }

    private suspend fun FlowCollector<StreamChunk>.emitTranslationResult(
        content: String,
        expectedLineCount: Int,
        logDetails: Boolean = false,
    ) {
        if (logDetails) Timber.d("Full content length: ${content.length}")

        parseTranslationContent(content, expectedLineCount)
            .onSuccess { translatedLines ->
                if (logDetails) Timber.d("Successfully parsed ${translatedLines.size} lines")
                emit(StreamChunk.Complete(translatedLines))
            }
            .onFailure { error ->
                if (logDetails) Timber.e(error, "Failed to parse translation")
                emit(StreamChunk.Error(error.message ?: "Parsing failed"))
            }
    }

    private fun responseError(response: okhttp3.Response): String = try {
        JSONObject(response.body.string())
            .optJSONObject("error")?.optString("message")
            ?: "HTTP ${response.code}: ${response.message}"
    } catch (e: Exception) {
        "HTTP ${response.code}: ${response.message}"
    }

    private data class StreamContent(val text: String, val sawDoneMarker: Boolean)

    private fun parseTranslationContent(content: String, expectedLineCount: Int): Result<List<String>> {
        val cleanedContent = content.replace("```json", "").replace("```", "").trim()
        val extractedArray = extractJsonArray(cleanedContent)

        val translatedLines = parseJsonArray(content.trim())
            ?: parseJsonArray(cleanedContent)
            ?: extractedArray?.let(::parseJsonArray)
            ?: extractedArray?.let { parsePlainLines(cleanedContent) }
            ?: return Result.failure(Exception("Failed to parse translation"))

        return Result.success(fitLineCount(translatedLines, expectedLineCount))
    }

    sealed class StreamChunk {
        data class Content(val text: String) : StreamChunk()
        data class Complete(val translatedLines: List<String>) : StreamChunk()
        data class Error(val message: String) : StreamChunk()
    }
}
