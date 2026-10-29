package com.s9encryptor.strongtoastextended.app.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import com.s9encryptor.strongtoastextended.bridge.BridgeProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [com.s9encryptor.strongtoastextended.StrongToastBridgeTest.RealResultReceiver::class])
class ModuleClientTest {
    @Test fun connectUsesPrivateReplyAndExplicitSystemUiPackage() = runBlocking {
        val context = TestContext()
        val client = ModuleClient(context)
        val result = client.connect()
        assertTrue(result.success)
        val intent = context.lastQuery!!
        assertEquals(BridgeProtocol.ACTION, intent.action)
        assertEquals(BridgeProtocol.SYSTEM_UI, intent.`package`)
        assertNotNull(intent.getParcelableExtra<ResultReceiver>("reply"))
        assertNotNull(intent.extras!!.getBinder("clientToken"))
        assertTrue(intent.getLongExtra("sequence", 0) > 0)
        assertTrue(client.snapshots.value.connected)
        assertFalse(client.snapshots.value.visible) // No native visibility proof in the response.
        client.close()
    }

    @Test fun wrongPrivateRequestIdIsRejectedWithoutChangingTruth() = runBlocking {
        val context = TestContext().apply { corruptRequestId = true }
        val client = ModuleClient(context)
        val result = client.connect()
        assertFalse(result.success)
        assertFalse(client.snapshots.value.connected)
        assertFalse(client.snapshots.value.active)
        client.close()
    }

    @Test fun staleSubscriptionRevisionCannotResurrectAStoppedStatus() = runBlocking {
        val context = TestContext()
        val client = ModuleClient(context)
        assertTrue(client.connect().success)
        val receiver = context.receiver!!
        val query = context.lastQuery!!
        receiver.send(2, context.response(query, 21, active = false))
        assertFalse(client.snapshots.value.active)
        receiver.send(2, context.response(query, 20, active = true))
        assertFalse(client.snapshots.value.active)
        assertEquals(21L, client.snapshots.value.revision)
        assertTrue(client.diagnostics.value.any { it.contains("stale") })
        client.close()
    }

    @Test fun oldConnectionCannotOverwriteReconnectedStateAndTokenRotates() = runBlocking {
        val context = TestContext()
        val client = ModuleClient(context)
        assertTrue(client.connect().success)
        val oldQuery = context.lastQuery!!
        val oldReceiver = context.receiver!!
        val oldToken = oldQuery.extras!!.getBinder("clientToken")
        client.close()
        context.revision = 30
        assertTrue(client.connect().success)
        assertNotEquals(oldToken, context.lastQuery!!.extras!!.getBinder("clientToken"))
        oldReceiver.send(2, context.response(oldQuery, 99, active = false))
        assertEquals(30L, client.snapshots.value.revision)
        assertTrue(client.snapshots.value.active)
        client.close()
    }

    @Test fun invalidInputsAreRejectedBeforeSendingAnIntent() = runBlocking {
        val context = TestContext()
        val client = ModuleClient(context)
        val result = client.request("START_STATUS", Bundle().apply {
            putString("text", "x")
            putInt("durationSeconds", 3601)
        })
        assertFalse(result.success)
        assertNull(context.lastQuery)
        client.close()
    }

    @Test fun closeAlsoSendsAFrameworkOnlyCallback() = runBlocking {
        val context = TestContext()
        val client = ModuleClient(context)
        assertTrue(client.connect().success)
        client.close()
        assertTrue("Unsubscribe must decode without app classes", context.unsubscribed.await(3, TimeUnit.SECONDS))
    }

    private class TestContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        @Volatile var lastQuery: Intent? = null
        @Volatile var receiver: ResultReceiver? = null
        var revision = 20L
        var corruptRequestId = false
        val unsubscribed = CountDownLatch(1)
        private val module = Binder()
        override fun getApplicationContext(): Context = this
        override fun sendBroadcast(intent: Intent) {
            // A real SystemUI process cannot load the app's anonymous callback class.
            // Parcel before any extras access; pre-decoding with the app loader hides this bug.
            val parcel = Parcel.obtain()
            val delivered = try {
                intent.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                Intent.CREATOR.createFromParcel(parcel).apply {
                    setExtrasClassLoader(object : ClassLoader(ResultReceiver::class.java.classLoader) {
                        override fun loadClass(name: String, resolve: Boolean): Class<*> {
                            if (name.startsWith("com.s9encryptor.")) throw ClassNotFoundException(name)
                            return super.loadClass(name, resolve)
                        }
                    })
                }
            } finally { parcel.recycle() }
            val reply = delivered.getParcelableExtra<ResultReceiver>("reply")!!
            assertEquals(ResultReceiver::class.java, reply.javaClass)
            if (delivered.getStringExtra("command") == "UNSUBSCRIBE") {
                unsubscribed.countDown()
                return
            }
            lastQuery = delivered
            receiver = reply
            val payload = response(delivered, revision, active = true)
            if (corruptRequestId) payload.putString("requestId", "not-the-private-request")
            reply.send(0, payload)
        }
        fun response(intent: Intent, version: Long, active: Boolean) = Bundle().apply {
            putInt("protocol", 1)
            putString("requestId", intent.getStringExtra("requestId"))
            putString("lastRequestId", "11111111-1111-1111-1111-111111111111")
            putString("bootId", "module-boot-one")
            putString("sessionId", "active-session")
            putBinder("moduleToken", module)
            putLong("revision", version)
            putBoolean("success", true)
            putBoolean("connected", true)
            putBoolean("moduleActive", true)
            putBoolean("nativeReady", true)
            putBoolean("active", active)
            putBoolean("visibilityKnown", false)
            putBoolean("visible", false)
            putString("text", "S9_Encryptor")
            putLong("expiresAtElapsed", SystemClock.elapsedRealtime() + 120_000)
        }
    }
}
