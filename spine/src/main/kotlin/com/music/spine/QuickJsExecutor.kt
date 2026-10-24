package com.music.spine

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object QuickJsExecutor {
    private const val TAG = "SpineDebug"
    private const val MAX_MODULES = 12
    private const val ENGINES_PER_MODULE = 3
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private class Pool(source: String, val baseUrl: String) {
        val code by lazy { preprocessModuleCode(source) }
        val initialization = Mutex()
        val permits = Semaphore(ENGINES_PER_MODULE)
        val idle = ArrayDeque<QuickJs>()
        var initialized = false
        var manifest = "{}"
        var users = 0
        var retired = false
    }

    // Active exports pin their pool and exclusively lease one VM.
    private val engineLock = Any()
    private val pools = LinkedHashMap<String, Pool>(16, 0.75f, true)

    fun isLoaded(moduleId: String): Boolean = synchronized(engineLock) {
        pools[moduleId]?.initialized == true
    }

    suspend fun loadModule(moduleId: String, jsCode: String, fetchBase: String = ""): Result<String> {
        val pool = synchronized(engineLock) {
            pools.getOrPut(moduleId) { Pool(jsCode, fetchBase) }.also { it.users++ }
        }
        return try {
            pool.initialization.withLock {
                if (!pool.initialized) {
                    val engine = newEngine(pool)
                    synchronized(engineLock) {
                        pool.idle.addLast(engine)
                        pool.initialized = true
                    }
                }
            }
            Result.success(pool.manifest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            release(pool)
        }
    }

    private suspend fun newEngine(pool: Pool): QuickJs = withContext(Dispatchers.Default) {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 512 * 1024L
        try {
            bindConsole(qjs)
            bindAsyncFetch(qjs, pool.baseUrl)
            qjs.evaluate<String>(POLYFILLS)
            val code = pool.code
            qjs.evaluate<String>(
                """
                var __spine_mod = (function() {
                    var module = { exports: {} };
                    var exports = module.exports;
                    var self = {};
                    $code
                    if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                        return module.exports;
                    }
                    return {};
                })();
                'ok'
                """.trimIndent()
            )
            val exports = qjs.evaluate<String>("Object.keys(__spine_mod).join(',')")
            check(exports.split(',').contains("searchTracks") && exports.split(',').contains("getTrackStreamUrl")) {
                "Module must export searchTracks and getTrackStreamUrl"
            }
            val manifest = qjs.evaluate<String>(
                "JSON.stringify({settings: __spine_mod.settings || {}, noStreamCache: !!__spine_mod.noStreamCache})",
            )
            synchronized(engineLock) { pool.manifest = manifest }
            qjs
        } catch (e: Throwable) {
            qjs.close()
            throw e
        }
    }

    suspend fun callExport(moduleId: String, functionName: String, args: List<String>): Result<String> {
        val pool = synchronized(engineLock) {
            pools[moduleId]?.also { it.users++ }
        } ?: return Result.failure(IllegalStateException("Module $moduleId is not loaded"))
        return try {
            pool.permits.withPermit {
                val qjs = synchronized(engineLock) { pool.idle.removeFirstOrNull() } ?: newEngine(pool)
                var reusable = false
                try {
                    val answer = withContext(Dispatchers.Default) {
                        val name = JSONObject.quote(functionName)
                        check(qjs.evaluate<String>("typeof __spine_mod[$name]") == "function") {
                            "$functionName is not exported"
                        }
                        val arguments = args.joinToString(",")
                        qjs.evaluate<String>(
                            """
                            var __spine_resolved_json = undefined;
                            (async function() {
                                try {
                                    var r = await __spine_mod[$name]($arguments);
                                    __spine_resolved_json = typeof r === 'string' ? r : JSON.stringify(r);
                                } catch(e) {
                                    __spine_resolved_json = JSON.stringify({error: String(e && e.message || e)});
                                }
                            })();
                            """.trimIndent()
                        )
                        qjs.evaluate<String>("__spine_resolved_json")
                    }
                    val response = JSONObject(answer)
                    check(!response.has("error")) {
                        response.optString("error", "Module call failed")
                    }
                    reusable = true
                    Result.success(answer)
                } finally {
                    synchronized(engineLock) {
                        if (reusable && !pool.retired) pool.idle.addLast(qjs) else qjs.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            release(pool)
        }
    }

    private fun release(pool: Pool) = synchronized(engineLock) {
        pool.users--
        if (pool.retired && pool.users == 0) closeIdle(pool)
        while (pools.size > MAX_MODULES) {
            val candidate = pools.entries.firstOrNull { it.value.users == 0 } ?: break
            pools.remove(candidate.key)
            candidate.value.retired = true
            closeIdle(candidate.value)
        }
    }

    private fun closeIdle(pool: Pool) {
        while (pool.idle.isNotEmpty()) pool.idle.removeFirst().close()
    }

    fun unload(moduleId: String) = synchronized(engineLock) {
        pools.remove(moduleId)?.let { pool ->
            pool.retired = true
            closeIdle(pool)
        }
        Unit
    }

    fun unloadAll() = synchronized(engineLock) {
        pools.values.forEach { pool -> pool.retired = true; closeIdle(pool) }
        pools.clear()
    }

    private fun preprocessModuleCode(jsCode: String): String {
        val code = jsCode.trim()

        val exportPattern = Regex("""^export\s+const\s+\w+\s*=\s*`""")
        val exportMatch = exportPattern.find(code)
        if (exportMatch != null) {
            Log.d(TAG, "  Detected template literal export, extracting content...")
            val contentStart = exportMatch.range.last + 1
            var i = contentStart
            while (i < code.length) {
                if (code[i] == '\\' && i + 1 < code.length) {
                    i += 2
                    continue
                }
                if (code[i] == '`') {
                    val extracted = code.substring(contentStart, i).trim()
                    Log.d(TAG, "  ✓ Extracted template literal: ${extracted.length} chars")
                    return extracted
                }
                i++
            }
            Log.w(TAG, "  Template literal not closed, falling through to regex preprocess")
        }

        var result = code
        result = result.replace(Regex("""\bexport\s+default\s+(?=function|class|const|let|var|async)"""), "")
        result = result.replace(Regex("""\bexport\s+(const|let|var|function|class|async)\b"""), "$1")
        result = result.replace(Regex("""\bexport\s*\{[^}]*\}\s*;?"""), "")
        return result
    }

    private fun bindConsole(qjs: QuickJs) {
        qjs.define("console") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    Log.d(TAG, "[JS] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("error", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    Log.e(TAG, "[JS-ERR] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("warn", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    Log.w(TAG, "[JS-WARN] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("info", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    Log.i(TAG, "[JS-INFO] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
        }
    }

    private suspend fun bindAsyncFetch(qjs: QuickJs, fetchBase: String) {
        qjs.define("__spine") {
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val rawUrl = args[0]?.toString() ?: throw IllegalArgumentException("fetch requires a URL")
                    val method = args[1]?.toString() ?: "GET"
                    val headersJson = args[2]?.toString() ?: "{}"
                    val body = args[3]?.toString()
                    val url = resolveUrl(rawUrl, fetchBase)


                    val (statusCode, responseBody) = fetchUrl(url, method, headersJson, body)

                    val respObj = org.json.JSONObject().apply {
                        put("status", statusCode)
                        put("ok", statusCode in 200..299)
                        put("body", responseBody)
                    }
                    return respObj.toString()
                }
            })
            asyncFunction("setTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val ms = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
                    kotlinx.coroutines.delay(ms)
                    return "0"
                }
            })
            asyncFunction("clearTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    return "ok"
                }
            })
        }
        qjs.evaluate<Unit>(
            """
            var fetch = async function(url, options) {
                var method = 'GET';
                var headers = '{}';
                var body = null;
                if (options) {
                    method = options.method || 'GET';
                    if (options.headers) {
                        if (typeof options.headers === 'string') {
                            headers = options.headers;
                        } else {
                            try { headers = JSON.stringify(options.headers); } catch(e) { headers = '{}'; }
                        }
                    }
                    if (options.body !== undefined && options.body !== null) {
                        body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                    }
                    if (options.signal && options.signal.aborted) {
                        throw new Error('Aborted');
                    }
                }
                var raw = JSON.parse(await __spine.fetch(url, method, headers, body));
                var respBody = raw.body;
                return {
                    ok: raw.ok,
                    status: raw.status,
                    statusText: raw.ok ? 'OK' : 'Error',
                    json: function() { try { return JSON.parse(respBody); } catch(e) { throw new Error('Invalid JSON: ' + respBody.substring(0, 200)); } },
                    text: function() { return respBody; },
                    arrayBuffer: function() { throw new Error('Not implemented'); },
                    clone: function() { return this; },
                    headers: { get: function(k) { return null; } }
                };
            };

            var setTimeout = async function(fn, ms) {
                await __spine.setTimeout(null, ms || 0);
                if (typeof fn === 'function') fn();
                return 0;
            };
            var clearTimeout = function(id) {};
            """.trimIndent()
        )
    }

    private fun resolveUrl(url: String, base: String): String {
        return if (base.isEmpty()) url else URI(base.trimEnd('/') + "/").resolve(url).toString()
    }

    private suspend fun fetchUrl(url: String, method: String, headersJson: String, body: String?): Pair<Int, String> {
        return try {
            val builder = Request.Builder()
                .url(url)

            var hasUserAgent = false
            try {
                val headersObj = JSONObject(headersJson)
                for (key in headersObj.keys()) {
                    val value = headersObj.optString(key, "")
                    builder.header(key, value)
                    if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
                }
            } catch (_: Exception) {
                // Malformed headers must not prevent the request from using the default User-Agent.
            }

            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                )
            }

            val mediaType = (builder.build().header("Content-Type") ?: "application/json; charset=utf-8").toMediaType()
            when (method.uppercase(java.util.Locale.ROOT)) {
                "POST" -> {
                    builder.post((body ?: "").toRequestBody(mediaType))
                }
                "PUT" -> {
                    builder.put((body ?: "").toRequestBody(mediaType))
                }
                "PATCH" -> builder.patch((body ?: "").toRequestBody(mediaType))
                "DELETE" -> builder.delete(body?.toRequestBody(mediaType))
                "HEAD" -> builder.head()
                else -> builder.get()
            }

            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(builder.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use { it.code to it.body.string() }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "    fetch FAILED: $method $url — ${e.message}", e)
            throw e
        }
    }

    private const val POLYFILLS = """
        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = { aborted: false }; };
            AbortController.prototype.abort = function() { this.signal.aborted = true; };
        }

        if (typeof Object.assign !== 'function') {
            Object.assign = function(target) {
                if (target == null) throw new TypeError('Cannot convert undefined or null to object');
                var to = Object(target);
                for (var i = 1; i < arguments.length; i++) {
                    var source = arguments[i];
                    if (source != null) {
                        for (var key in source) {
                            if (Object.prototype.hasOwnProperty.call(source, key)) {
                                to[key] = source[key];
                            }
                        }
                    }
                }
                return to;
            };
        }

        if (typeof Promise.any !== 'function') {
            Promise.any = function(promises) {
                return new Promise(function(resolve, reject) {
                    var errors = [];
                    var remaining = promises.length;
                    if (remaining === 0) { reject(new AggregateError([], 'All promises were rejected')); return; }
                    promises.forEach(function(p, i) {
                        Promise.resolve(p).then(resolve, function(e) {
                            errors[i] = e;
                            remaining--;
                            if (remaining === 0) reject(new AggregateError(errors, 'All promises were rejected'));
                        });
                    });
                });
            };
        }

        if (typeof Promise.allSettled !== 'function') {
            Promise.allSettled = function(promises) {
                return Promise.all(promises.map(function(p) {
                    return Promise.resolve(p).then(
                        function(value) { return { status: 'fulfilled', value: value }; },
                        function(reason) { return { status: 'rejected', reason: reason }; }
                    );
                }));
            };
        }

        if (typeof AggregateError === 'undefined') {
            var AggregateError = function(errors, message) {
                this.errors = errors;
                this.message = message || '';
                this.name = 'AggregateError';
            };
            AggregateError.prototype = Object.create(Error.prototype);
        }

        if (typeof atob === 'undefined') {
            var atob = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input).replace(/=+$/, '');
                var output = '';
                for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = chars.indexOf(buffer);
                }
                return output;
            };
        }

        if (typeof setTimeout === 'undefined') {
            var __spine_timers = {};
            var __spine_timer_id = 0;
            var setTimeout = function(fn, ms) {
                var id = ++__spine_timer_id;
                __spine_timers[id] = { fn: fn, ms: ms || 0 };
                return id;
            };
            var clearTimeout = function(id) {
                if (__spine_timers[id]) delete __spine_timers[id];
            };
        }
        if (typeof clearTimeout === 'undefined') {
            var clearTimeout = function(id) {
                if (typeof __spine_timers !== 'undefined' && __spine_timers[id]) delete __spine_timers[id];
            };
        }

        if (typeof URL === 'undefined') {
            var URL = function(url, base) {
                this.href = url;
                try {
                    var a = url.replace(/^[^:]+:/, 'http:');
                    var match = a.match(/^\/\/([^/]+)(\/.*)?$/);
                    if (match) {
                        this.hostname = match[1];
                        this.pathname = match[2] || '/';
                    } else {
                        var m2 = a.match(/^https?:\/\/([^/]+)(\/.*)?$/);
                        if (m2) { this.hostname = m2[1]; this.pathname = m2[2] || '/'; }
                    }
                } catch(e) {
                    // Keep the default URL fields when this address cannot be parsed.
                }
            };
        }
    """
}
