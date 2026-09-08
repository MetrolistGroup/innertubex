package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.d
import com.metrolist.innertubex.utils.sha1
import com.metrolist.innertubex.w
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock

private val PREPROCESSED_CACHE_KEY_REGEX = Regex("[a-f0-9]{40}")

/**
 * Runs yt-dlp's embedded JS solver (EJS) inside QuickJS — needed when the player uses
 * VM/table-driven ciphers that regex-based extraction cannot handle.
 *
 * @see <a href="https://github.com/yt-dlp/ejs">yt-dlp/ejs</a>
 */
internal class EjsChallengeSolver(
    private val engine: QuickJsEngine,
    private val logger: InnerTubeLogger,
) {
    companion object {
        private const val TAG = "EjsChallengeSolver"
        private const val MAX_PREPROCESSED_PLAYERS = 4
        private const val MAX_PLAYER_JS_LENGTH = 8 * 1024 * 1024
        private const val MAX_CHALLENGE_LENGTH = 64 * 1024
        private const val MAX_CHALLENGES = 256
        private const val MAX_CHALLENGE_PAYLOAD_LENGTH = 8 * 1024 * 1024
        private const val MAX_PAYLOAD_LENGTH = 10 * 1024 * 1024
        private const val MAX_RAW_OUTPUT_LENGTH = 10 * 1024 * 1024
        private const val MAX_SOLVER_OUTPUT_LENGTH = 256 * 1024
        private val payloadJson = Json { encodeDefaults = false }
    }

    data class SolveResult(
        val sigByChallenge: Map<String, String>,
        val nByChallenge: Map<String, String>,
        val preprocessedPlayer: String?,
    )

    private val initMutex = Mutex()
    private var bootstrapped = false
    private var bundleHash: String? = null

    private val preprocessedMutex = Mutex()
    private val preprocessedByPlayerUrl = LinkedHashMap<String, String>()
    private var readPreprocessedPlayer: suspend (String) -> String? = { null }
    private var writePreprocessedPlayer: suspend (String, String?) -> Unit = { _, _ -> }

    /** Ensures EJS lib/core are evaluated once (safe to call before loading parser _solve* scripts). */
    suspend fun ensureLoaded() {
        ensureBootstrapped()
    }

    fun setPreprocessedPlayerCache(
        read: suspend (String) -> String?,
        write: suspend (String, String?) -> Unit,
    ) {
        readPreprocessedPlayer = read
        writePreprocessedPlayer = write
    }

    suspend fun cachePreprocessedPlayer(
        playerUrl: String,
        preprocessedPlayer: String,
    ) {
        if (preprocessedPlayer.isBlank() || preprocessedPlayer.length > MAX_PLAYER_JS_LENGTH) return
        preprocessedMutex.withLock {
            putPreprocessedPlayerLocked(playerUrl, preprocessedPlayer)
        }
    }

    private suspend fun ensureBootstrapped() {
        initMutex.withLock {
            if (bootstrapped) return
            val startMs = Clock.System.now().toEpochMilliseconds()
            engine.initialize()
            engine.setupYoutubeGlobals()
            val lib = readYtEjsSolverScript("yt.solver.lib.min.js")
            val core = readYtEjsSolverScript("yt.solver.core.min.js")
            bundleHash = sha1(lib + core)
            engine.execute(lib)
            engine.execute("Object.assign(globalThis, lib);")
            engine.execute(core)
            bootstrapped = true
            logger.d(TAG, "EJS bootstrap done elapsed=${Clock.System.now().toEpochMilliseconds() - startMs}ms")
        }
    }

    /**
     * @param requestOrder pairs of ("sig"|"n") to distinct challenge strings for that request.
     */
    suspend fun solve(
        playerUrl: String,
        fullPlayerJs: String,
        requestOrder: List<Pair<String, List<String>>>,
        preferPreprocessed: Boolean = true,
    ): SolveResult {
        if (requestOrder.isEmpty() || requestOrder.all { it.second.isEmpty() }) {
            return SolveResult(emptyMap(), emptyMap(), null)
        }
        if (fullPlayerJs.length > MAX_PLAYER_JS_LENGTH ||
            requestOrder.sumOf { it.second.size } > MAX_CHALLENGES ||
            requestOrder.sumOf { (_, challenges) -> challenges.sumOf { it.length.toLong() } } >
            MAX_CHALLENGE_PAYLOAD_LENGTH ||
            requestOrder.any { (_, challenges) -> challenges.any { it.length > MAX_CHALLENGE_LENGTH } }
        ) {
            return SolveResult(emptyMap(), emptyMap(), null)
        }

        return try {
            ensureBootstrapped()
            val cacheKey = bundleHash?.let { preprocessedPlayerCacheKey(playerUrl, it) }
            val preprocessed =
                if (preferPreprocessed) {
                    loadPreprocessedPlayer(playerUrl, cacheKey)
                } else {
                    null
                }

            if (preprocessed != null) {
                val cachedResult =
                    try {
                        solveOnce(playerUrl, preprocessed, requestOrder, preprocessed = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        SolveResult(emptyMap(), emptyMap(), null)
                    }
                if (cachedResult.solvesAll(requestOrder)) return cachedResult
                evictPreprocessedPlayer(playerUrl, cacheKey)
                if (fullPlayerJs.isBlank()) return cachedResult
            }

            solveOnce(playerUrl, fullPlayerJs, requestOrder, preprocessed = false).also { result ->
                result.preprocessedPlayer?.let { persistPreprocessedPlayer(cacheKey, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "EJS solve failed player=${playerUrl.logId()} type=${e.logType()}")
            SolveResult(emptyMap(), emptyMap(), null)
        }
    }

    private suspend fun solveOnce(
        playerUrl: String,
        playerInputText: String,
        requestOrder: List<Pair<String, List<String>>>,
        preprocessed: Boolean,
    ): SolveResult {
        val requests =
            buildJsonArray {
                for ((kind, challenges) in requestOrder) {
                    if (challenges.isEmpty()) continue
                    add(
                        buildJsonObject {
                            put("type", kind)
                            putJsonArray("challenges") {
                                for (c in challenges) {
                                    add(JsonPrimitive(c))
                                }
                            }
                        },
                    )
                }
            }
        val requestsJson = payloadJson.encodeToString(JsonElement.serializer(), requests)
        val playerInput = QuickJsEngine.jsStringLiteral(playerInputText)
        val payloadPrefix =
            if (preprocessed) {
                "{\"type\":\"preprocessed\",\"preprocessed_player\":"
            } else {
                "{\"type\":\"player\",\"player\":"
            }
        val payloadSuffix =
            if (preprocessed) {
                ",\"requests\":$requestsJson}"
            } else {
                ",\"output_preprocessed\":true,\"requests\":$requestsJson}"
            }
        if (payloadPrefix.length + playerInput.length + payloadSuffix.length > MAX_PAYLOAD_LENGTH) {
            return SolveResult(emptyMap(), emptyMap(), null)
        }
        // jsc() may return objects QuickJS JSON.stringify cannot handle (circular refs);
        // copy only plain string fields into a new tree before stringify.
        val js =
            """
            (function() {
              var payload = $payloadPrefix$playerInput$payloadSuffix;
              var r = jsc(payload);
              if (!r) return JSON.stringify({"type":"error","error":"jsc returned null"});
              if (r.type === "error")
                return JSON.stringify({"type":"error","error":String(r.error != null ? r.error : "")});
              if (r.type !== "result")
                return JSON.stringify({"type":"error","error":"unexpected jsc type: "+String(r.type)});
              var out = {"type":"result","responses":[]};
              if (typeof r.preprocessed_player === "string" && r.preprocessed_player.length > 0) {
                if (r.preprocessed_player.length > $MAX_PLAYER_JS_LENGTH)
                  return JSON.stringify({"type":"error","error":"preprocessed player too large"});
                out.preprocessed_player = r.preprocessed_player;
              }
              var resps = r.responses;
              if (!resps) return JSON.stringify(out);
              for (var i = 0; i < resps.length; i++) {
                var resp = resps[i];
                if (!resp) {
                  out.responses.push({"type":"error","error":"null response"});
                  continue;
                }
                if (resp.type === "error") {
                  out.responses.push({
                    "type":"error",
                    "error":String(resp.error != null ? resp.error : "")
                  });
                } else if (resp.type === "result") {
                  var d = resp.data;
                  var plain = {};
                  if (d && typeof d === "object") {
                    var keys = Object.keys(d);
                    for (var j = 0; j < keys.length; j++) {
                      var k = keys[j];
                      var v = d[k];
                      var text = v == null ? "" : String(v);
                      if (text.length > $MAX_SOLVER_OUTPUT_LENGTH) {
                        plain = null;
                        break;
                      }
                      plain[k] = text;
                    }
                  }
                  if (plain == null)
                    out.responses.push({"type":"error","error":"solver output too large"});
                  else
                    out.responses.push({"type":"result","data":plain});
                } else {
                  out.responses.push({"type":"error","error":"unknown response type"});
                }
              }
              return JSON.stringify(out);
            })()
            """.trimIndent()
        val evaluateStartMs = Clock.System.now().toEpochMilliseconds()
        val evaluated = engine.evaluate(js, MAX_RAW_OUTPUT_LENGTH, collectGarbage = !preprocessed)
        if (evaluated.length > MAX_RAW_OUTPUT_LENGTH) return SolveResult(emptyMap(), emptyMap(), null)
        val raw = evaluated.trim()
        logger.d(
            TAG,
            "EJS evaluate done preprocessed=$preprocessed requests=${requestOrder.sumOf {
                it.second.size
            }} elapsed=${Clock.System.now().toEpochMilliseconds() - evaluateStartMs}ms player=${playerUrl.logId()}",
        )
        return parseOutput(playerUrl, raw, requestOrder)
    }

    private suspend fun loadPreprocessedPlayer(
        playerUrl: String,
        cacheKey: String?,
    ): String? {
        preprocessedMutex.withLock {
            preprocessedByPlayerUrl.remove(playerUrl)?.also {
                preprocessedByPlayerUrl[playerUrl] = it
                return it
            }
        }
        cacheKey ?: return null
        val stored =
            try {
                readPreprocessedPlayer(cacheKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
        if (stored.isBlank() || stored.length > MAX_PLAYER_JS_LENGTH) {
            safeWritePreprocessedPlayer(cacheKey, null)
            return null
        }
        preprocessedMutex.withLock { putPreprocessedPlayerLocked(playerUrl, stored) }
        return stored
    }

    private suspend fun persistPreprocessedPlayer(
        cacheKey: String?,
        preprocessedPlayer: String,
    ) {
        if (cacheKey != null && preprocessedPlayer.isNotBlank() && preprocessedPlayer.length <= MAX_PLAYER_JS_LENGTH) {
            safeWritePreprocessedPlayer(cacheKey, preprocessedPlayer)
        }
    }

    private suspend fun evictPreprocessedPlayer(
        playerUrl: String,
        cacheKey: String?,
    ) {
        preprocessedMutex.withLock { preprocessedByPlayerUrl.remove(playerUrl) }
        cacheKey?.let { safeWritePreprocessedPlayer(it, null) }
    }

    private suspend fun safeWritePreprocessedPlayer(
        cacheKey: String,
        value: String?,
    ) {
        try {
            writePreprocessedPlayer(cacheKey, value)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cache failures must not break playback.
        }
    }

    private suspend fun parseOutput(
        playerUrl: String,
        raw: String,
        requestOrder: List<Pair<String, List<String>>>,
    ): SolveResult {
        val root =
            try {
                Json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                logger.w(TAG, "EJS output was not valid JSON (${e.logType()})")
                return SolveResult(emptyMap(), emptyMap(), null)
            }

        if (root["type"]?.jsonPrimitive?.content != "result") {
            logger.w(TAG, "EJS returned a top-level error")
            return SolveResult(emptyMap(), emptyMap(), null)
        }

        val preprocessed =
            root["preprocessed_player"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { it.length <= MAX_PLAYER_JS_LENGTH }
        if (preprocessed != null) {
            preprocessedMutex.withLock { putPreprocessedPlayerLocked(playerUrl, preprocessed) }
        }

        val responses = root["responses"]?.jsonArray ?: JsonArray(emptyList())
        val nonEmptyRequests = requestOrder.filter { it.second.isNotEmpty() }
        if (responses.size != nonEmptyRequests.size) {
            logger.w(
                TAG,
                "EJS responses size mismatch expected=${nonEmptyRequests.size} got=${responses.size}",
            )
        }

        val sigMap = mutableMapOf<String, String>()
        val nMap = mutableMapOf<String, String>()
        nonEmptyRequests.forEachIndexed { i, (kind, _) ->
            if (responses.size <= i) return@forEachIndexed
            val resp = responses[i].jsonObject
            when (resp["type"]?.jsonPrimitive?.content) {
                "result" -> {
                    val dataObj = resp["data"]?.jsonObject ?: return@forEachIndexed
                    val m =
                        dataObj.entries
                            .associate { entry ->
                                entry.key to
                                    entry.value.jsonPrimitive.content.takeIf {
                                        it.isNotBlank() && it.length <= MAX_SOLVER_OUTPUT_LENGTH
                                    }
                            }.filterValues { it != null }
                            .mapValues { it.value!! }
                    when (kind) {
                        "sig" -> sigMap.putAll(m)
                        "n" -> nMap.putAll(m)
                    }
                }

                else -> {
                    logger.w(TAG, "EJS request kind=$kind failed")
                }
            }
        }

        return SolveResult(sigMap, nMap, preprocessed)
    }

    private fun putPreprocessedPlayerLocked(
        playerUrl: String,
        preprocessedPlayer: String,
    ) {
        preprocessedByPlayerUrl.remove(playerUrl)
        preprocessedByPlayerUrl[playerUrl] = preprocessedPlayer
        while (preprocessedByPlayerUrl.size > MAX_PREPROCESSED_PLAYERS) {
            val oldestKey = preprocessedByPlayerUrl.keys.firstOrNull() ?: break
            preprocessedByPlayerUrl.remove(oldestKey)
        }
    }

    private fun String.logId(): String = RemotePlayerConfigParser.extractPlayerHash(this) ?: "unknown"

    private fun Throwable.logType(): String = this::class.simpleName ?: "Exception"
}

internal fun preprocessedPlayerCacheKey(
    playerUrl: String,
    bundleHash: String,
): String? {
    if (!PREPROCESSED_CACHE_KEY_REGEX.matches(bundleHash)) return null
    runCatching { validatedPlayerScriptUrl(playerUrl) }.getOrNull() ?: return null
    return sha1("$playerUrl:$bundleHash")
}

private fun EjsChallengeSolver.SolveResult.solvesAll(requestOrder: List<Pair<String, List<String>>>): Boolean =
    requestOrder.all { (kind, challenges) ->
        val solved =
            when (kind) {
                "sig" -> sigByChallenge
                "n" -> nByChallenge
                else -> return@all false
            }
        challenges.all(solved::containsKey)
    }
