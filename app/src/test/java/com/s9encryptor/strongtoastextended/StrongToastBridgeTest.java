package com.s9encryptor.strongtoastextended;

import static org.junit.Assert.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import com.s9encryptor.strongtoastextended.bridge.BridgeProtocol;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Receiver authorization configuration and state isolation, with real Android Bundles. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, shadows = {StrongToastBridgeTest.RealResultReceiver.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class StrongToastBridgeTest {
    /** Robolectric's default send shadow discards the Binder in a parcelled receiver. */
    @org.robolectric.annotation.Implements(ResultReceiver.class)
    public static class RealResultReceiver {
        @org.robolectric.annotation.RealObject private ResultReceiver real;
        @org.robolectric.annotation.Implementation
        protected void send(int code, Bundle data) {
            org.robolectric.shadow.api.Shadow.directlyOn(real, ResultReceiver.class, "send",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class, code),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Bundle.class, data));
        }
    }
    private final BlockingQueue<Bundle> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<Thread> deliveryThreads = new LinkedBlockingQueue<>();
    private ResultReceiver reply;

    @Before public void prepare() throws Exception {
        set("main", new Handler(Looper.getMainLooper()));
        set("registered", false);
        set("nativeController", null);
        set("lastMutationSequence", 0L);
        set("lastRequestId", "");
        set("lastError", "");
        set("bootstrapNonce", null);
        set("bootstrapExpires", 0L);
        reply = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                data.putInt("testResultCode", code);
                deliveryThreads.add(Thread.currentThread());
                replies.add(data);
            }
        };
    }

    @After public void cleanup() throws Exception {
        call("clearSubscriber", new Class<?>[0]);
        set("nativeController", null);
    }

    @Test public void crossUidReceiverAlwaysRequiresSignaturePermission() throws Exception {
        RecordingContext context = new RecordingContext();
        call("attach", new Class<?>[]{Context.class}, context);
        assertEquals(BridgeProtocol.PERMISSION, context.requiredPermission);
        assertEquals(Context.RECEIVER_EXPORTED, context.flags);
        assertEquals(BridgeProtocol.ACTION, context.filter.getAction(0));
        assertNotNull(context.receiver);
    }

    @Test public void queryKeepsMutationSequenceAndEchoesItsOwnCallbackId() throws Exception {
        long sequence = SystemClock.elapsedRealtimeNanos();
        Bundle mutation = request("UPDATE_SETTINGS", sequence);
        execute(mutation, null);
        Bundle changed = takeReply();
        assertTrue(changed.getBoolean("success"));
        Bundle query = request("GET_CURRENT_STATE", 1L);
        execute(query, null);
        Bundle state = takeReply();
        assertEquals(query.getString("requestId"), state.getString("requestId"));
        assertEquals(mutation.getString("requestId"), state.getString("lastRequestId"));
        assertEquals(sequence, get("lastMutationSequence"));
        assertFalse(state.getBoolean("visible"));
        assertFalse(state.getBoolean("visibilityKnown"));
        assertNotEquals(Looper.getMainLooper().getThread(), deliveryThreads.poll(3, TimeUnit.SECONDS));
    }

    @Test public void parcelledBroadcastRetainsItsPrivateReplyAndPackage() throws Exception {
        RecordingContext context = new RecordingContext();
        call("attach", new Class<?>[]{Context.class}, context);
        Bundle input = request("GET_CURRENT_STATE", SystemClock.elapsedRealtimeNanos());
        input.putParcelable("reply", reply);
        input.putBinder("clientToken", new Binder());
        Intent outgoing = new Intent(BridgeProtocol.ACTION).setPackage(BridgeProtocol.SYSTEM_UI).putExtras(input);
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            outgoing.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Intent delivered = Intent.CREATOR.createFromParcel(parcel);
            Object decoded = delivered.getParcelableExtra("reply");
            assertTrue("Decoded private callback: " + decoded, decoded instanceof ResultReceiver);
            context.receiver.onReceive(context, delivered);
            assertEquals("Receiver error", "", get("lastError"));
            Bundle result = takeReply();
            assertTrue(result.getBoolean("success"));
            assertTrue(result.getBoolean("moduleActive"));
            assertEquals(input.getString("requestId"), result.getString("requestId"));
        } finally { parcel.recycle(); }
    }

    @Test public void staleAndImplausibleMutationSequencesAreRejected() throws Exception {
        long now = SystemClock.elapsedRealtimeNanos();
        execute(request("UPDATE_SETTINGS", now), null);
        takeReply();
        assertRejected(request("UPDATE_SETTINGS", now));
        assertRejected(request("UPDATE_SETTINGS", now + TimeUnit.MINUTES.toNanos(6)));
        assertEquals(now, get("lastMutationSequence"));
    }

    @Test public void bootstrapNonceIsOneUseAndExpiredNonceCannotCaptureController() throws Exception {
        String nonce = String.join("", java.util.Collections.nCopies(64, "a"));
        set("bootstrapNonce", nonce);
        set("bootstrapExpires", SystemClock.elapsedRealtime() - 1L);
        Bundle input = new Bundle();
        input.putString("s9_bootstrap_nonce", nonce);
        Object control = new Object();
        StrongToastBridge.handleBootstrap(control, input);
        assertNull(get("nativeController"));
        set("bootstrapExpires", SystemClock.elapsedRealtime() + 15_000L);
        StrongToastBridge.handleBootstrap(control, input);
        assertSame(control, get("nativeController"));
        assertNull(get("bootstrapNonce"));
        Object attacker = new Object();
        StrongToastBridge.handleBootstrap(attacker, input);
        assertSame(control, get("nativeController"));
    }

    @Test public void unrelatedClientCannotUnsubscribeCurrentClient() throws Exception {
        Binder current = new Binder();
        execute(request("SUBSCRIBE", 1L), current);
        takeReply();
        execute(request("UNSUBSCRIBE", 2L), new Binder());
        takeReply();
        assertSame(current, get("subscriberToken"));
        execute(request("UNSUBSCRIBE", 3L), current);
        takeReply();
        assertNull(get("subscriberToken"));
        assertNull(get("heartbeat"));
    }

    @Test public void unicodeReplyBoundingDoesNotSplitSurrogatePairs() {
        String supplementary = new String(Character.toChars(0x1F600));
        String text = String.join("", java.util.Collections.nCopies(8193, supplementary));
        String bounded = StrongToastBridge.boundedText(text);
        assertEquals(8192, bounded.codePointCount(0, bounded.length()));
        assertEquals(16384, bounded.length());
    }

    private Bundle request(String name, long sequence) {
        Bundle input = new Bundle();
        input.putInt("protocol", 1);
        input.putString("requestId", UUID.randomUUID().toString());
        input.putLong("sequence", Math.max(1L, sequence));
        input.putString("command", name);
        return BridgeProtocol.validate(input);
    }
    private void execute(Bundle input, Binder token) throws Exception {
        call("execute", new Class<?>[]{Bundle.class, ResultReceiver.class, android.os.IBinder.class}, input, reply, token);
    }
    private void assertRejected(Bundle input) throws Exception {
        try { execute(input, null); fail("Expected sequence rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    private Bundle takeReply() throws Exception {
        Bundle result = replies.poll(3, TimeUnit.SECONDS);
        assertNotNull("Private worker reply should arrive", result);
        return result;
    }
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = StrongToastBridge.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw wrapped;
        }
    }
    private static void set(String name, Object value) throws Exception {
        Field field = StrongToastBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static Object get(String name) throws Exception {
        Field field = StrongToastBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private static final class RecordingContext extends ContextWrapper {
        String requiredPermission;
        IntentFilter filter;
        int flags;
        BroadcastReceiver receiver;
        RecordingContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public String getPackageName() { return BridgeProtocol.SYSTEM_UI; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            if (filter.hasAction(BridgeProtocol.ACTION)) {
                this.requiredPermission = permission;
                this.flags = flags;
                this.filter = filter;
                this.receiver = receiver;
            }
            return null;
        }
    }
}
