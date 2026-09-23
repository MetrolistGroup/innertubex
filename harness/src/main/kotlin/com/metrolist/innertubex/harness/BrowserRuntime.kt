package com.metrolist.innertubex.harness

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Chromium is sandboxed AND has no network namespace egress; CDP uses private OS pipes, never a port. */
internal class BrowserRuntime(
    private val executable: String?,
    private val proxy: Proxy?,
) : AutoCloseable {
    private var profile: Path? = null
    private var process: Process? = null
    private var reader: Thread? = null
    private var session: String? = null
    private var pageTarget: String? = null
    private val pageSessions = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val writeLock = Any()

    suspend fun evaluate(script: String): String {
        if (process == null) start()
        val result =
            command(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", script)
                    put("returnByValue", true)
                    put("awaitPromise", false)
                },
            )
        check("exceptionDetails" !in result) { "Browser JavaScript failed" }
        val value = result["result"]?.jsonObject?.get("value") ?: return ""
        return runCatching { value.jsonPrimitive.content }.getOrElse { "" }
    }

    private suspend fun start() {
        val binary = executable ?: System.getenv("INNERTUBEX_CHROMIUM") ?: "/usr/bin/chromium"
        check(Path.of(binary).let { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }) {
            "Chromium unavailable: install Chromium or specify an absolute executable path"
        }
        check(Files.isExecutable(Path.of("/usr/bin/unshare"))) { "Chromium requires Linux unshare for isolated networking" }
        check(Files.isExecutable(Path.of("/usr/bin/node"))) { "Chromium pipe bridge requires Node.js" }
        val proxyFlag =
            proxy?.let {
                check(it.type() == Proxy.Type.HTTP && it.address() is InetSocketAddress) { "Unsupported browser proxy" }
                val address = it.address() as InetSocketAddress
                check(address.port in 1..65535) { "Invalid browser proxy" }
                val host = address.hostString
                "--proxy-server=http://${if (':' in host) "[$host]" else host}:${address.port}"
            }
        try {
            profile =
                Files.createTempDirectory("innertubex-browser-").also {
                    Files.setPosixFilePermissions(
                        it,
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    )
                }
            // Node only forwards NUL-delimited CDP messages; no token or cookie is placed in argv or on disk.
            // Linux unshare leaves Chromium's own sandbox enabled and removes all browser network interfaces.
            process =
                ProcessBuilder(
                    listOf(
                        "/usr/bin/unshare",
                        "-Un",
                        "--map-current-user",
                        "/usr/bin/node",
                        "-e",
                        PIPE_BRIDGE,
                        binary,
                        requireNotNull(profile).toString(),
                        USER_AGENT,
                    ) + listOfNotNull(proxyFlag),
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            reader =
                Thread(::readMessages, "innertubex-cdp-reader").apply {
                    isDaemon = true
                    start()
                }
            // No remote navigation is necessary: robots.txt is fulfilled locally with a tiny HTML document.
            val target =
                command(
                    "Target.createTarget",
                    buildJsonObject { put("url", "about:blank") },
                    null,
                )["targetId"]!!.jsonPrimitive.content
            pageTarget = target
            session =
                command(
                    "Target.attachToTarget",
                    buildJsonObject {
                        put("targetId", target)
                        put("flatten", true)
                    },
                    null,
                )["sessionId"]!!.jsonPrimitive.content
            pageSessions += checkNotNull(session)
            command("Page.enable")
            command("Runtime.enable")
            command("Fetch.enable", FETCH_PATTERNS)
            // Cover workers, service workers and additional pages before executing third-party interpreter code.
            command("Target.setAutoAttach", AUTO_ATTACH, null)
            command("Target.setAutoAttach", AUTO_ATTACH)
            command("Page.navigate", buildJsonObject { put("url", "https://www.youtube.com/robots.txt") })
            withTimeout(10_000) {
                while (evaluate("location.href === 'https://www.youtube.com/robots.txt' && document.readyState === 'complete'") !=
                    "true"
                ) {
                    delay(50)
                }
            }
            evaluate(PAGE_BOUND_TOKEN_SCRIPT)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private suspend fun command(
        method: String,
        params: JsonObject = buildJsonObject { },
        targetSession: String? = session,
    ): JsonObject {
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        try {
            send(method, params, targetSession, id)
            val response = withTimeout(10_000) { future.await() }
            check("error" !in response) { "Browser command failed: $method" }
            return response["result"]?.jsonObject ?: buildJsonObject { }
        } finally {
            pending.remove(id)
        }
    }

    private fun send(
        method: String,
        params: JsonObject,
        targetSession: String?,
        id: Int = nextId.incrementAndGet(),
    ) {
        val message =
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
                targetSession?.let { put("sessionId", it) }
            }.toString().encodeToByteArray()
        check(message.size <= 12 * 1024 * 1024) { "Browser command exceeds size limit" }
        synchronized(writeLock) {
            checkNotNull(process).outputStream.apply {
                write(message)
                write(0)
                flush()
            }
        }
    }

    private fun readMessages() {
        try {
            val bytes = ByteArray(128 * 1024)
            var size = 0
            checkNotNull(process).inputStream.buffered().use { input ->
                while (true) {
                    val byte = input.read()
                    if (byte == -1) break
                    if (byte == 0) {
                        if (size > 0) handle(Json.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject)
                        size = 0
                    } else {
                        check(size < bytes.size) { "Browser response exceeds size limit" }
                        bytes[size++] = byte.toByte()
                    }
                }
            }
        } catch (_: Exception) {
            // Chromium/Node failures and protocol payloads never escape to callbacks or logs.
        } finally {
            pending.values.forEach { it.completeExceptionally(IllegalStateException("Browser pipe closed")) }
        }
    }

    private fun handle(response: JsonObject) {
        response["id"]
            ?.jsonPrimitive
            ?.content
            ?.toIntOrNull()
            ?.let { pending.remove(it)?.complete(response) }
        val method = response["method"]?.jsonPrimitive?.content
        val eventSession = response["sessionId"]?.jsonPrimitive?.content
        val params = response["params"]?.jsonObject ?: return
        if (method == "Target.attachedToTarget") {
            val attached = params["sessionId"]?.jsonPrimitive?.content ?: return
            if (params["targetInfo"]
                    ?.jsonObject
                    ?.get("targetId")
                    ?.jsonPrimitive
                    ?.content == pageTarget
            ) {
                pageSessions += attached
            }
            // Set interception before releasing a newly attached target's debugger pause.
            send("Fetch.enable", FETCH_PATTERNS, attached)
            send("Target.setAutoAttach", AUTO_ATTACH, attached)
            send("Runtime.runIfWaitingForDebugger", buildJsonObject { }, attached)
        } else if (method == "Fetch.requestPaused") {
            val id = params["requestId"]?.jsonPrimitive?.content ?: return
            val request = params["request"]?.jsonObject
            val allowed =
                eventSession in pageSessions &&
                    request?.get("url")?.jsonPrimitive?.content == "https://www.youtube.com/robots.txt" &&
                    request["method"]?.jsonPrimitive?.content == "GET" &&
                    params["resourceType"]?.jsonPrimitive?.content == "Document"
            send(
                if (allowed) "Fetch.fulfillRequest" else "Fetch.failRequest",
                buildJsonObject {
                    put("requestId", id)
                    if (allowed) {
                        put("responseCode", 200)
                        put(
                            "responseHeaders",
                            Json.parseToJsonElement("[{\"name\":\"Content-Type\",\"value\":\"text/html; charset=utf-8\"}]"),
                        )
                        put("body", ROBOTS_BODY)
                    } else {
                        put("errorReason", "BlockedByClient")
                    }
                },
                eventSession,
            )
        }
    }

    override fun close() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Browser closed")) }
        pending.clear()
        process?.let { child ->
            val descendants = child.toHandle().descendants().toList()
            descendants.forEach { it.destroyForcibly() }
            child.destroyForcibly()
            runCatching { child.outputStream.close() }
            child.waitFor(2, TimeUnit.SECONDS)
            descendants.forEach { if (it.isAlive) runCatching { it.onExit().get(2, TimeUnit.SECONDS) } }
        }
        reader?.takeUnless { Thread.currentThread() == it }?.join(2_000)
        reader = null
        process = null
        session = null
        pageTarget = null
        pageSessions.clear()
        profile?.let { dir ->
            check(
                runCatching {
                    Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                }.isSuccess,
            ) { "Browser profile cleanup failed" }
        }
        profile = null
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { result, error ->
            if (error != null) continuation.resumeWithException(error) else continuation.resume(result)
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

private val FETCH_PATTERNS =
    buildJsonObject { put("patterns", Json.parseToJsonElement("[{\"urlPattern\":\"*\",\"requestStage\":\"Request\"}]")) }
private val AUTO_ATTACH =
    buildJsonObject {
        put("autoAttach", true)
        put("flatten", true)
        put("waitForDebuggerOnStart", true)
    }
private val ROBOTS_BODY = Base64.getEncoder().encodeToString("<!doctype html><title></title>".encodeToByteArray())

// Statically fixed bridge; Chromium uses fds 3/4 for CDP. stdout carries framed CDP, never diagnostics.
private const val PIPE_BRIDGE = """
const {spawn} = require('node:child_process');
const args = ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--disable-extensions', '--remote-debugging-pipe',
  '--user-data-dir=' + process.argv[2], '--user-agent=' + process.argv[3]];
if (process.argv[4]) args.push(process.argv[4]);
const child = spawn(process.argv[1], args, {stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe']});
process.on('exit', () => child.kill('SIGKILL'));
child.on('exit', () => process.exit(1));
process.stdin.pipe(child.stdio[3]);
const payload = Buffer.alloc(128 * 1024);
let size = 0;
child.stdio[4].on('data', chunk => {
  for (let i = 0; i < chunk.length; i++) {
    if (chunk[i] === 0) {
      if (size) {
        if (!process.stdout.write(Buffer.concat([payload.subarray(0, size), Buffer.from([0])]))) {
          child.stdio[4].pause();
          process.stdout.once('drain', () => child.stdio[4].resume());
        }
      }
      size = 0;
    } else {
      if (size >= payload.length) process.exit(2);
      payload[size++] = chunk[i];
    }
  }
});
"""

// Adapted from the sibling shared page-bound runtime; only Google's interpreter implements these algorithms.
internal const val PAGE_BOUND_TOKEN_SCRIPT = """
var bgVmFunctions = null;
var poTokenMinter = null;
function loadBotGuard(challengeData) {
  var bgVm = window[challengeData.globalName];
  bgVmFunctions = null;
  if (!bgVm || !bgVm.a) throw new Error("BotGuard VM is unavailable");
  var vmLoad = bgVm.a(challengeData.program, function(asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
    bgVmFunctions = { asyncSnapshotFunction: asyncSnapshotFunction, shutdownFunction: shutdownFunction,
      passEventFunction: passEventFunction, checkCameraFunction: checkCameraFunction };
  }, true, undefined, function() {}, [[], []], undefined, false,
    [function() {}, function() {}, function() {}, function() {}, function() {}]);
  return Promise.resolve(vmLoad).then(function() {
    return new Promise(function(resolve, reject) {
      var attempts = 0;
      var interval = setInterval(function() {
        if (bgVmFunctions && bgVmFunctions.asyncSnapshotFunction) { clearInterval(interval); resolve(bgVmFunctions); }
        else if (++attempts >= 10000) { clearInterval(interval); reject(new Error("Timed out waiting for BotGuard snapshot")); }
      }, 1);
    });
  });
}
function snapshot(vmFunctions, webPoSignalOutput) {
  return new Promise(function(resolve, reject) {
    try { vmFunctions.asyncSnapshotFunction(function(response) { resolve(response); },
      [undefined, undefined, webPoSignalOutput, undefined]); } catch (error) { reject(error); }
  });
}
function runBotGuard(challengeData) {
  var interpreter = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if (!interpreter) throw new Error("BotGuard interpreter is unavailable");
  new Function(interpreter)();
  var webPoSignalOutput = [];
  return loadBotGuard({ globalName: challengeData.globalName, program: challengeData.program })
    .then(function(vmFunctions) { return snapshot(vmFunctions, webPoSignalOutput); })
    .then(function(response) { return { webPoSignalOutput: webPoSignalOutput, botguardResponse: response }; });
}
async function createPoTokenMinter(webPoSignalOutput, integrityToken) {
  var getMinter = webPoSignalOutput[0];
  if (typeof getMinter !== "function") throw new Error("BotGuard minter factory is unavailable");
  poTokenMinter = await getMinter(integrityToken);
  if (typeof poTokenMinter !== "function") throw new Error("BotGuard minter is unavailable");
}
async function obtainPoToken(identifier) {
  if (!poTokenMinter) throw new Error("BotGuard minter has not been initialized");
  var result = await poTokenMinter(identifier);
  if (!(result instanceof Uint8Array)) throw new Error("BotGuard returned an invalid token");
  return result;
}
"""
