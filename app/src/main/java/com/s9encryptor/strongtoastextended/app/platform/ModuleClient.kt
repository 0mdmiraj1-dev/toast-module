package com.s9encryptor.strongtoastextended.app.platform

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.os.SystemClock
import com.s9encryptor.strongtoastextended.app.model.ModuleSnapshot
import com.s9encryptor.strongtoastextended.app.model.OperationResult
import com.s9encryptor.strongtoastextended.bridge.BridgeProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Signature-permission IPC. There is no root process, exported app receiver, or shared control file here. */
class ModuleClient(context: Context) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val diagnosticsLock = Any()
    private val mutableSnapshots = MutableStateFlow(ModuleSnapshot())
    private val mutableDiagnostics = MutableStateFlow<List<String>>(emptyList())
    val snapshots: StateFlow<ModuleSnapshot> = mutableSnapshots.asStateFlow()
    val diagnostics: StateFlow<List<String>> = mutableDiagnostics.asStateFlow()
    private val pending = ConcurrentHashMap<String, Pending>()
    private var generation = 0L
    private var clientToken: IBinder = Binder()
    private var subscriptionId: String? = null
    private var subscriptionReceiver: ResultReceiver? = null
    private var moduleToken: IBinder? = null
    private var moduleDeath: IBinder.DeathRecipient? = null
    private var bootId = ""
    private var lastRevision = -1L
    private val retiredBoots = LinkedHashSet<String>()

    suspend fun connect(): OperationResult = request("GET_CURRENT_STATE", subscribe = true)

    suspend fun request(
        command: String,
        payload: Bundle = Bundle(),
        subscribe: Boolean = false,
    ): OperationResult = withContext(Dispatchers.IO) {
        if (pending.size >= MAX_PENDING) return@withContext OperationResult(false, "Another action is still being checked. Try again shortly.")
        val id = UUID.randomUUID().toString()
        val input = Bundle(payload).apply {
            putInt("protocol", BridgeProtocol.VERSION)
            putString("requestId", id)
            putLong("sequence", nextSequence())
            putString("command", command)
            putBoolean("subscribe", subscribe)
        }
        val validated = try {
            BridgeProtocol.validate(input)
        } catch (_: RuntimeException) {
            record("Command rejected by local protocol validation")
            return@withContext OperationResult(false, "Check the action details and try again.")
        }
        val state = synchronized(lock) { Pair(generation, clientToken) }
        val entry = Pending(id, state.first, command, subscribe, CompletableDeferred())
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                receive(entry, resultCode, resultData)
            }
        }
        entry.receiver = receiver
        synchronized(lock) {
            if (entry.generation != generation) return@withContext OperationResult(false, CLOSED_MESSAGE)
            if (subscribe) {
                subscriptionId = id
                subscriptionReceiver = receiver
            }
            pending[id] = entry
        }
        val outgoing = Bundle(validated).apply {
            putParcelable("reply", receiver)
            putBinder("clientToken", state.second)
        }
        try {
            // Registration's sender permission authenticates this app's signature. A receiver
            // permission here would incorrectly require SystemUI to own our app permission.
            application.sendBroadcast(Intent(BridgeProtocol.ACTION).setPackage(BridgeProtocol.SYSTEM_UI).putExtras(outgoing))
            withTimeout(REQUEST_TIMEOUT_MS) { entry.result.await() }
        } catch (timeout: TimeoutCancellationException) {
            record("Module did not acknowledge the request before its deadline")
            invalidate("The SystemUI module did not respond. Enable its System UI scope and reconnect.", sendUnsubscribe = true)
            OperationResult(false, "The SystemUI module did not respond. Enable its System UI scope and reconnect.")
        } catch (cancelled: CancellationException) {
            if (subscribe) clearSubscription(entry)
            throw cancelled
        } catch (_: RuntimeException) {
            record("Protected broadcast could not be delivered")
            if (subscribe) clearSubscription(entry)
            OperationResult(false, "The SystemUI connection is unavailable. Check the module and try again.")
        } finally {
            pending.remove(id, entry)
        }
    }

    /** May be called on background and again on every foreground connection. */
    fun close() = invalidate("", sendUnsubscribe = true)

    fun dispose() {
        close()
        scope.launch { kotlinx.coroutines.delay(250L); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    private fun receive(entry: Pending, resultCode: Int, data: Bundle?) {
        val isWaiting = pending[entry.id] === entry && !entry.result.isCompleted
        synchronized(lock) {
            if (entry.generation != generation) return
            if (entry.subscribe && subscriptionId != entry.id) return
            if (!entry.subscribe && !isWaiting) return
        }
        if (data == null || resultCode !in 0..2 || !ModuleCodec.replyMatches(data, entry.id)) {
            record("Rejected an invalid private module reply")
            if (isWaiting) entry.result.complete(OperationResult(false, "The module returned an invalid response. Reconnect and try again."))
            return
        }
        try {
            val nextBoot = ModuleCodec.bootId(data)
            val incomingToken = data.getBinder("moduleToken")
            if (nextBoot.isEmpty() || incomingToken == null || !incomingToken.isBinderAlive) {
                record("Module reply had no live identity token")
                if (isWaiting) entry.result.complete(OperationResult(false, "The module identity could not be verified. Reconnect and try again."))
                return
            }
            val snapshot = ModuleCodec.decode(data)
            if (!snapshot.connected || !snapshot.moduleActive) {
                if (isWaiting) entry.result.complete(OperationResult(false, "The SystemUI module is not connected."))
                return
            }
            val stale = synchronized(lock) {
                if (entry.generation != generation || (entry.subscribe && subscriptionId != entry.id)) return
                when {
                    nextBoot in retiredBoots -> true
                    nextBoot == bootId && snapshot.revision < lastRevision -> true
                    nextBoot != bootId && !isWaiting -> true
                    else -> {
                        if (nextBoot != bootId) {
                            retireBoot(bootId)
                            bootId = nextBoot
                            lastRevision = -1L
                        }
                        if (!attachModuleToken(incomingToken, entry.generation)) return
                        lastRevision = snapshot.revision
                        mutableSnapshots.value = snapshot
                        false
                    }
                }
            }
            if (stale) {
                record("Ignored stale module state")
                if (isWaiting) entry.result.complete(OperationResult(false, "A newer module state is already available.", mutableSnapshots.value))
                return
            }
            if (isWaiting) {
                val success = ModuleCodec.replySuccess(data, resultCode)
                val message = ModuleCodec.replyMessage(data).ifEmpty {
                    if (success) "" else "The module could not complete this action."
                }
                entry.result.complete(OperationResult(success, message, snapshot))
            }
        } catch (_: RuntimeException) {
            record("Module state failed bounded decoding")
            if (isWaiting) entry.result.complete(OperationResult(false, "The module state could not be read. Reconnect and try again."))
        }
    }

    /** Called while holding lock; linkToDeath does not execute a remote application method. */
    private fun attachModuleToken(incoming: IBinder, expectedGeneration: Long): Boolean {
        if (moduleToken == incoming) return true
        unlinkModuleToken()
        val recipient = IBinder.DeathRecipient {
            val current = synchronized(lock) { moduleToken == incoming && generation == expectedGeneration }
            if (current) {
                record("SystemUI Binder died; current state is no longer confirmed")
                invalidate("SystemUI restarted. Reconnect to check the current status.", sendUnsubscribe = false, retireCurrent = true)
            }
        }
        return try {
            incoming.linkToDeath(recipient, 0)
            moduleToken = incoming
            moduleDeath = recipient
            true
        } catch (_: Exception) {
            scope.launch { invalidate("SystemUI restarted. Reconnect to check the current status.", sendUnsubscribe = false, retireCurrent = true) }
            false
        }
    }

    private fun clearSubscription(entry: Pending) {
        val token = synchronized(lock) {
            if (entry.generation != generation || subscriptionId != entry.id) return
            subscriptionId = null
            subscriptionReceiver = null
            val old = clientToken
            clientToken = Binder()
            old
        }
        unsubscribe(token)
    }

    private fun invalidate(message: String, sendUnsubscribe: Boolean, retireCurrent: Boolean = false) {
        val oldToken: IBinder
        val oldPending: List<Pending>
        synchronized(lock) {
            oldToken = clientToken
            clientToken = Binder() // A delayed unsubscribe can never remove a later subscription.
            generation++
            subscriptionId = null
            subscriptionReceiver = null
            unlinkModuleToken()
            if (retireCurrent) {
                retireBoot(bootId)
                bootId = ""
                lastRevision = -1L
            }
            oldPending = pending.values.toList()
            pending.clear()
            mutableSnapshots.value = mutableSnapshots.value.copy(
                connected = false, moduleActive = false, nativeReady = false, active = false,
                visible = false, visibilityKnown = false, lastError = message,
            )
        }
        oldPending.forEach { it.result.complete(OperationResult(false, message.ifEmpty { CLOSED_MESSAGE })) }
        if (sendUnsubscribe) unsubscribe(oldToken)
    }

    private fun unlinkModuleToken() {
        val token = moduleToken
        val recipient = moduleDeath
        moduleToken = null
        moduleDeath = null
        if (token != null && recipient != null) runCatching { token.unlinkToDeath(recipient, 0) }
    }

    private fun unsubscribe(token: IBinder) {
        scope.launch {
            runCatching {
                val payload = Bundle().apply {
                    putInt("protocol", BridgeProtocol.VERSION)
                    putString("requestId", UUID.randomUUID().toString())
                    putLong("sequence", nextSequence())
                    putString("command", "UNSUBSCRIBE")
                    putBinder("clientToken", token)
                    putParcelable("reply", object : ResultReceiver(null) {})
                }
                application.sendBroadcast(Intent(BridgeProtocol.ACTION).setPackage(BridgeProtocol.SYSTEM_UI).putExtras(payload))
            }
        }
    }

    private fun retireBoot(value: String) {
        if (value.isEmpty()) return
        retiredBoots += value
        while (retiredBoots.size > 8) retiredBoots.remove(retiredBoots.first())
    }

    private fun record(value: String) = synchronized(diagnosticsLock) {
        mutableDiagnostics.value = (mutableDiagnostics.value + value.take(240)).takeLast(200)
    }

    private class Pending(
        val id: String,
        val generation: Long,
        val command: String,
        val subscribe: Boolean,
        val result: CompletableDeferred<OperationResult>,
    ) {
        var receiver: ResultReceiver? = null
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val MAX_PENDING = 16
        private const val CLOSED_MESSAGE = "The app connection was closed. Reconnect to check the current status."
        private val sequence = AtomicLong()
        private fun nextSequence(): Long = sequence.updateAndGet { previous ->
            maxOf(previous + 1L, SystemClock.elapsedRealtimeNanos())
        }
    }
}
