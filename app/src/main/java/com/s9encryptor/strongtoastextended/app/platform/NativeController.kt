package com.s9encryptor.strongtoastextended.app.platform

import android.content.Context
import android.os.Bundle
import com.s9encryptor.strongtoastextended.app.model.*
import com.s9encryptor.strongtoastextended.bridge.BridgeProtocol
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One APK: native UI -> authenticated module; root is only authorization/bootstrap. */
class NativeController(context: Context) : ToastController {
    private val application = context.applicationContext
    private val module = ModuleClient(application)
    private val root = RootManager(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutations = Mutex()
    override val snapshots: StateFlow<ModuleSnapshot> = module.snapshots
    override val rootStatus: StateFlow<RootStatus> = root.status
    override val diagnostics: StateFlow<List<String>> = combine(root.diagnostics, module.diagnostics) { a,b ->
        (a+b).takeLast(200)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun connect(settings: UserSettings): OperationResult = module.connect()
    override suspend fun requestRoot(): OperationResult = root.authorize(explicitRetry = true)

    override suspend fun command(type: CommandType, draft: ToastDraft, settings: UserSettings): OperationResult {
        if (type == CommandType.GET_CURRENT_STATE) return module.connect()
        // A safe Stop must work even after returning to the app with no cached root session.
        if (type == CommandType.STOP_STATUS || type == CommandType.STOP_TIMER)
            return module.request(type.name)
        return mutations.withLock {
            if (type == CommandType.UPDATE_SETTINGS && root.status.value != RootStatus.GRANTED)
                return@withLock OperationResult(true, "Settings saved for the next authorized start.")
            val granted = if (root.status.value == RootStatus.GRANTED) OperationResult(true) else root.authorize()
            if (!granted.success) return@withLock granted
            var connected = module.connect()
            if (!connected.success) return@withLock connected.copy(message = "Enable StrongToast Extended for System UI in LSPosed, reboot, then reconnect.")
            if (!module.snapshots.value.nativeReady) {
                val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
                val prepared = module.request("PREPARE_ROOT_BOOTSTRAP", Bundle().apply { putString("nonce", nonce) })
                if (!prepared.success) return@withLock prepared
                val bootstrapped = root.bootstrap(nonce)
                if (!bootstrapped.success) return@withLock bootstrapped.copy(message = "The native Strong Toast interface is unavailable. Check module diagnostics and reboot.")
                connected = module.connect()
                if (!connected.success || !module.snapshots.value.nativeReady)
                    return@withLock OperationResult(false, "SystemUI is not ready for Strong Toast on this firmware.", connected.snapshot)
            }
            if (type == CommandType.UPDATE_SETTINGS) return@withLock module.request(type.name, ModuleCodec.settingsPayload(settings))
            val normalized = runCatching { InputValidator.normalizeDraft(draft) }.getOrElse {
                return@withLock OperationResult(false, it.message ?: "Check the status details.")
            }
            val icon = resolveIcon(normalized.icon)
            module.request(type.name, ModuleCodec.commandPayload(normalized, settings, icon.first, icon.second, "png"))
        }
    }

    private suspend fun resolveIcon(choice: IconChoice): Pair<String,String> = withContext(Dispatchers.IO) {
        if (choice == IconChoice.HYPERCEILER) {
            val available = runCatching {
                application.packageManager.getResourcesForApplication("com.sevtinge.hyperceiler")
                    .getIdentifier("ic_hyperceiler", "drawable", "com.sevtinge.hyperceiler") != 0
            }.getOrDefault(false)
            if (available) return@withContext "com.sevtinge.hyperceiler" to "ic_hyperceiler"
        }
        BridgeProtocol.APP_ID to "ic_strongtoast_native"
    }
    override fun closeTransport() { module.close(); root.close() }
    override fun dispose() { module.dispose(); root.dispose(); scope.cancel() }
}
