package com.s9encryptor.strongtoastextended.app.platform

import android.content.Context
import android.system.Os
import com.s9encryptor.strongtoastextended.app.model.OperationResult
import com.s9encryptor.strongtoastextended.app.model.RootStatus
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A short-lived root authorization/bootstrap connection; native UI commands never use this path. */
class RootManager(context: Context) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val lifecycleLock = Any()
    private val diagnosticsLock = Any()
    private val sessionLock = Any()
    private val mutableStatus = MutableStateFlow(RootStatus.UNKNOWN)
    private val mutableDiagnostics = MutableStateFlow<List<String>>(emptyList())
    val status: StateFlow<RootStatus> = mutableStatus.asStateFlow()
    val diagnostics: StateFlow<List<String>> = mutableDiagnostics.asStateFlow()
    private val pending = ConcurrentHashMap<String, PendingReply>()
    @Volatile private var session: Session? = null
    @Volatile private var latestConnection: Session? = null
    @Volatile private var authorizationAttempted = false
    @Volatile private var previouslyGranted = false
    private var activeOperations = 0
    private var closeWhenIdle = false
    private var idleClose: Job? = null

    init {
        scope.launch {
            val detected = if (suAvailable()) RootStatus.AVAILABLE else RootStatus.UNAVAILABLE
            mutableStatus.compareAndSet(RootStatus.UNKNOWN, detected)
        }
    }

    suspend fun authorize(explicitRetry: Boolean = false): OperationResult = operation {
        authorizeLocked(explicitRetry)
    }

    suspend fun bootstrap(nonce: String): OperationResult {
        if (!RootProtocol.validNonce(nonce)) return OperationResult(false, "Invalid bootstrap nonce")
        return operation {
            val authorization = authorizeLocked(explicitRetry = false)
            if (!authorization.success) return@operation authorization
            val connection = session ?: return@operation OperationResult(false, "Root session is unavailable")
            val response = request(connection, "BOOTSTRAP", BOOTSTRAP_TIMEOUT_MS, nonce)
            OperationResult(response.optBoolean("success", false), safeMessage(response,
                "The enabled SystemUI module did not acknowledge bootstrap"))
        }
    }

    /** Backgrounding must not dismiss an in-flight Magisk permission decision. */
    fun close() {
        synchronized(lifecycleLock) {
            idleClose?.cancel()
            idleClose = null
            if (activeOperations > 0) closeWhenIdle = true
            else session?.let { disconnect(it, expected = true) }
        }
    }

    fun dispose() {
        session?.let { disconnect(it, expected = true, force = true) }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun operation(block: suspend () -> OperationResult): OperationResult =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                beginOperation()
                try {
                    block()
                } catch (e: TimeoutCancellationException) {
                    session?.let { disconnect(it, expected = true, force = true) }
                    if (mutableStatus.value == RootStatus.REQUESTING) mutableStatus.value = RootStatus.ERROR
                    record("Root bootstrap request timed out; no automatic retry")
                    OperationResult(false, "Root request timed out. Retry explicitly when ready")
                } catch (e: CancellationException) {
                    session?.let { disconnect(it, expected = true, force = true) }
                    if (mutableStatus.value == RootStatus.REQUESTING) mutableStatus.value = RootStatus.ERROR
                    record("Root request cancelled")
                    throw e
                } catch (e: Exception) {
                    val connection = session
                    val denied = connection?.denialSeen?.get() == true || mutableStatus.value == RootStatus.DENIED
                    connection?.let { disconnect(it, expected = true, force = true) }
                    if (mutableStatus.value == RootStatus.REQUESTING) {
                        mutableStatus.value = if (denied) RootStatus.DENIED else RootStatus.ERROR
                    }
                    record("Root transport failed (${e.javaClass.simpleName})")
                    OperationResult(false, if (denied) "Root access was denied. Retry explicitly to request it again"
                        else "Root bootstrap is unavailable. Check diagnostics and retry explicitly")
                } finally {
                    finishOperation()
                }
            }
        }

    private suspend fun authorizeLocked(explicitRetry: Boolean): OperationResult {
        if (!explicitRetry && (mutableStatus.value == RootStatus.DENIED ||
                    (authorizationAttempted && !previouslyGranted && mutableStatus.value == RootStatus.ERROR))) {
            return OperationResult(false, "Root was not granted. Use Grant root access to retry explicitly")
        }
        val existing = session
        if (existing != null && existing.process.isAlive && !existing.closing.get() &&
            mutableStatus.value == RootStatus.GRANTED) return OperationResult(true, "Root access granted")
        if (!suAvailable()) {
            mutableStatus.value = RootStatus.UNAVAILABLE
            record("No supported su executable was found")
            return OperationResult(false, "Root access is unavailable on this device")
        }
        val asset = extractAsset()
        mutableStatus.value = RootStatus.REQUESTING
        authorizationAttempted = true
        record("Requesting root authorization for controller bootstrap")
        val process = ProcessBuilder("su", "-c", RootProtocol.startupScript(
            asset.first.absolutePath, asset.second, UUID.randomUUID().toString())).start()
        val connection = Session(process, BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)))
        synchronized(sessionLock) {
            session = connection
            latestConnection = connection
        }
        startReaders(connection)
        val response = request(connection, "ROOT_STATUS", AUTHORIZATION_TIMEOUT_MS)
        if (!response.optBoolean("success", false) || response.optInt("uid", -1) != 0) {
            mutableStatus.value = RootStatus.DENIED
            disconnect(connection, expected = true)
            return OperationResult(false, "Root UID 0 was not granted")
        }
        connection.authorizing.set(false)
        previouslyGranted = true
        mutableStatus.value = RootStatus.GRANTED
        record("Root UID 0 verified; normal toast commands use the protected module channel")
        return OperationResult(true, "Root access granted")
    }

    private suspend fun request(connection: Session, operation: String, timeout: Long,
                                nonce: String? = null): JSONObject {
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JSONObject>()
        val waiting = PendingReply(connection, response)
        pending[id] = waiting
        try {
            return withTimeout(timeout) {
                val payload = JSONObject().put("protocol", 1).put("requestId", id).put("op", operation)
                if (nonce != null) payload.put("nonce", nonce)
                val line = payload.toString()
                check(line.length <= RootProtocol.MAX_LINE_CHARS)
                synchronized(connection.writeLock) {
                    if (connection.closing.get()) throw IOException("Root session closed")
                    connection.writer.write(line)
                    connection.writer.newLine()
                    connection.writer.flush()
                }
                response.await()
            }
        } finally {
            pending.remove(id, waiting)
        }
    }

    private fun startReaders(connection: Session) {
        scope.launch {
            try {
                InputStreamReader(connection.process.inputStream, Charsets.UTF_8).use { input ->
                    while (true) {
                        val line = RootProtocol.readBoundedLine(input) ?: break
                        if (!line.startsWith(RootProtocol.PREFIX)) {
                            if (line.isNotBlank()) record("Root helper emitted an unframed startup message")
                            continue
                        }
                        val response = JSONObject(line.substring(RootProtocol.PREFIX.length))
                        if (response.optInt("protocol", 0) != 1) throw IOException("Unsupported root protocol")
                        val id = response.optString("requestId", "")
                        if (id.isEmpty() && !response.optBoolean("success", false)) {
                            if (response.optString("errorCode", "") == "root_required") {
                                connection.denialSeen.set(true)
                                if (latestConnection === connection) mutableStatus.value = RootStatus.DENIED
                            }
                            failPending(connection, IOException("Root helper initialization failed"))
                        } else {
                            pending[id]?.takeIf { it.connection === connection }?.response?.complete(response)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!connection.expectedExit.get()) record("Root helper output became unavailable")
            } finally {
                if (!connection.expectedExit.get()) {
                    failPending(connection, IOException("Root helper disconnected"))
                    if (latestConnection === connection && mutableStatus.value == RootStatus.GRANTED) mutableStatus.value = RootStatus.ERROR
                    disconnect(connection, expected = false, force = true)
                }
            }
        }
        scope.launch {
            try {
                InputStreamReader(connection.process.errorStream, Charsets.UTF_8).use { input ->
                    while (true) {
                        val line = RootProtocol.readBoundedLine(input) ?: break
                        val lower = line.lowercase(java.util.Locale.ROOT)
                        if (listOf("permission denied", "not allowed", "access denied", "request rejected")
                                .any(lower::contains)) {
                            connection.denialSeen.set(true)
                            if (latestConnection === connection && connection.authorizing.get() && !connection.expectedExit.get()) {
                                mutableStatus.value = RootStatus.DENIED
                            }
                        }
                        if (line.isNotBlank()) record("Root: ${line.take(180)}")
                    }
                }
            } catch (_: Exception) {
                // Process shutdown closes both streams. There is no UI-thread wait here.
            }
        }
        scope.launch {
            try { connection.process.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (!connection.expectedExit.get()) {
                failPending(connection, IOException("Root process exited"))
            }
        }
    }

    private fun disconnect(connection: Session, expected: Boolean, force: Boolean = false) {
        if (expected) connection.expectedExit.set(true)
        if (!connection.closing.compareAndSet(false, true)) return
        synchronized(sessionLock) { if (session === connection) session = null }
        failPending(connection, IOException("Root session closed"))
        if (force) {
            runCatching { connection.writer.close() }
            connection.process.destroy()
        }
        scope.launch {
            if (!force) {
                runCatching {
                    synchronized(connection.writeLock) {
                        val exit = JSONObject().put("protocol", 1).put("requestId", UUID.randomUUID().toString())
                            .put("op", "EXIT").toString()
                        connection.writer.write(exit)
                        connection.writer.newLine()
                        connection.writer.flush()
                        connection.writer.close()
                    }
                }
            }
            runCatching {
                if (!connection.process.waitFor(250L, TimeUnit.MILLISECONDS)) {
                    connection.process.destroy()
                    if (!connection.process.waitFor(250L, TimeUnit.MILLISECONDS)) connection.process.destroyForcibly()
                }
            }
            runCatching { connection.process.inputStream.close() }
            runCatching { connection.process.errorStream.close() }
            runCatching { connection.process.outputStream.close() }
        }
    }

    private fun failPending(connection: Session, error: Exception) {
        pending.values.filter { it.connection === connection }.forEach { it.response.completeExceptionally(error) }
    }

    private fun beginOperation() = synchronized(lifecycleLock) {
        idleClose?.cancel()
        idleClose = null
        activeOperations++
        closeWhenIdle = false
    }

    private fun finishOperation() {
        val closeNow = synchronized(lifecycleLock) {
            activeOperations--
            if (activeOperations == 0 && closeWhenIdle) true else {
                if (activeOperations == 0) idleClose = scope.launch {
                    delay(IDLE_CLOSE_MS)
                    synchronized(lifecycleLock) {
                        if (activeOperations == 0) session?.let { disconnect(it, expected = true) }
                    }
                }
                false
            }
        }
        if (closeNow) session?.let { disconnect(it, expected = true) }
    }

    private fun extractAsset(): Pair<File, String> {
        val expected = application.assets.open(ASSET_DIGEST).bufferedReader().use { it.readText().trim() }
            .split(Regex("\\s+"))[0].lowercase(java.util.Locale.ROOT)
        check(RootProtocol.validNonce(expected)) { "Invalid packaged bootstrap digest" }
        val directory = File(application.noBackupFilesDir, "root-bootstrap")
        check(directory.isDirectory || directory.mkdirs()) { "Private bootstrap directory is unavailable" }
        Os.chmod(directory.absolutePath, 448) // 0700
        val destination = File(directory, "$expected.dex")
        if (destination.isFile && destination.length() in 1..MAX_ASSET_BYTES.toLong() && digest(destination) == expected) {
            Os.chmod(destination.absolutePath, 256) // 0400
            return destination to expected
        }
        val temporary = File.createTempFile("bootstrap-", ".tmp", directory)
        try {
            application.assets.open(ASSET_DEX).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        check(total <= MAX_ASSET_BYTES) { "Packaged bootstrap exceeds its size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(digest(temporary) == expected) { "Packaged bootstrap checksum mismatch" }
            Os.chmod(temporary.absolutePath, 256)
            check(temporary.renameTo(destination)) { "Could not atomically install private bootstrap" }
            return destination to expected
        } finally {
            temporary.delete()
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun suAvailable(): Boolean {
        if (listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")
                .any { File(it).canExecute() }) return true
        return runCatching {
            val check = ProcessBuilder("sh", "-c", "command -v su >/dev/null 2>&1").start()
            try { check.waitFor(2L, TimeUnit.SECONDS) && check.exitValue() == 0 }
            finally { check.destroy() }
        }.getOrDefault(false)
    }

    private fun safeMessage(response: JSONObject, fallback: String): String =
        response.optString("message", fallback).filter { !it.isISOControl() }.take(240).ifBlank { fallback }

    private fun record(message: String) {
        val safe = message.filter { !it.isISOControl() }.take(240)
        synchronized(diagnosticsLock) {
            mutableDiagnostics.value = (mutableDiagnostics.value + safe).takeLast(40)
        }
    }

    private class Session(val process: Process, val writer: BufferedWriter) {
        val writeLock = Any()
        val closing = AtomicBoolean(false)
        val expectedExit = AtomicBoolean(false)
        val denialSeen = AtomicBoolean(false)
        val authorizing = AtomicBoolean(true)
    }

    private data class PendingReply(val connection: Session, val response: CompletableDeferred<JSONObject>)

    companion object {
        private const val ASSET_DEX = "strongtoast-bootstrap.dex"
        private const val ASSET_DIGEST = "strongtoast-bootstrap.sha256"
        private const val MAX_ASSET_BYTES = 1024 * 1024
        private const val AUTHORIZATION_TIMEOUT_MS = 45_000L
        private const val BOOTSTRAP_TIMEOUT_MS = 4_000L
        private const val IDLE_CLOSE_MS = 30_000L
    }
}
