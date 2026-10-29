package com.s9encryptor.strongtoastextended;

import android.app.Application;
import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import com.s9encryptor.strongtoastextended.bridge.BridgeProtocol;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Signature-authenticated, main-thread command bridge. No root service or public state file. */
final class StrongToastBridge {
    private static final String TAG = "[StrongToastBridge] ";
    private static final long MAX_SEQUENCE_AGE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final String BOOT_ID = UUID.randomUUID().toString();
    private static final IBinder MODULE_TOKEN = new Binder();
    private static final Bundle CAPABILITIES = new Bundle();
    // A dead callback must never block SystemUI's main looper. Bound pending deliveries.
    private static final ThreadPoolExecutor DELIVERIES = new ThreadPoolExecutor(1, 1, 30,
        TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), runnable -> {
            Thread thread = new Thread(runnable, "StrongToast-private-replies");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static Handler main;
    private static Context context;
    private static boolean installed;
    private static boolean registered;
    private static Object nativeController;
    private static long lastMutationSequence;
    private static long revision;
    private static String sessionId = "";
    private static String lastRequestId = "";
    private static String lastError = "";
    private static String bootstrapNonce;
    private static long bootstrapExpires;
    private static ResultReceiver subscriber;
    private static IBinder subscriberToken;
    private static IBinder.DeathRecipient subscriberDeath;
    private static String subscriptionRequestId;
    private static Runnable heartbeat;
    private static Runnable publishPending;

    private StrongToastBridge() { }

    static void install(ClassLoader loader, Handler handler) {
        if (installed) return;
        installed = true;
        main = handler;
        for (String name : new String[]{"receiver", "controllerConstructor", "watchdog", "capture",
            "explicitHide", "nativeHide", "nativeComplete", "shadeState", "shadeFull", "shadeClosed",
            "controlCenter", "nativeVisibility"}) CAPABILITIES.putBoolean(name, false);
        try {
            Class<?> control = XposedHelpers.findClass("com.android.systemui.toast.MIUIStrongToastControl", loader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllConstructors(control, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) captureController(p.thisObject);
                }
            });
            capability("controllerConstructor", !hooks.isEmpty());
        } catch (Throwable error) { report("Native controller constructor unavailable", error); }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { attach((Context) p.thisObject); } catch (Throwable error) { report("Application context unavailable", error); }
                }
            });
        } catch (Throwable error) { report("Application attach fallback unavailable", error); }
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.SystemUIApplication", loader,
                "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { attach((Context) p.thisObject); } catch (Throwable error) { report("SystemUI context unavailable", error); }
                    }
                });
        } catch (Throwable ignored) { }
        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application != null) attach(application);
        } catch (Throwable ignored) { }
    }

    private static void onMain(Runnable action) {
        if (main == null) return;
        Runnable safe = () -> { try { action.run(); } catch (Throwable error) { report("Bridge callback failed", error); } };
        if (Looper.myLooper() == main.getLooper()) safe.run(); else main.post(safe);
    }

    private static void attach(Context available) {
        onMain(() -> {
            if (registered || available == null || !BridgeProtocol.SYSTEM_UI.equals(available.getPackageName())) return;
            Context application = available.getApplicationContext();
            context = application != null ? application : available;
            IntentFilter filter = new IntentFilter(BridgeProtocol.ACTION);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    receive(intent);
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= 33)
                    context.registerReceiver(receiver, filter, BridgeProtocol.PERMISSION, main, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(receiver, filter, BridgeProtocol.PERMISSION, main);
                registered = true;
                // Protected platform wake broadcasts reconcile elapsed deadlines after deep sleep.
                try {
                    IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
                    wake.addAction(Intent.ACTION_USER_PRESENT);
                    BroadcastReceiver deadlines = new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) {
                            if (Intent.ACTION_SCREEN_ON.equals(i.getAction()) || Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                                StrongToastHook.checkElapsedDeadline(); StrongToastEffects.onScreenWake(); publish();
                            }
                        }
                    };
                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(deadlines, wake, null, main, Context.RECEIVER_NOT_EXPORTED);
                    else context.registerReceiver(deadlines, wake, null, main);
                } catch (Throwable ignored) { }
                capability("receiver", true);
                publish();
            } catch (Throwable error) { report("Protected command receiver unavailable", error); }
        });
    }

    private static void receive(Intent intent) {
        ResultReceiver reply = null;
        String requestId = "";
        try {
            if (intent == null || !BridgeProtocol.ACTION.equals(intent.getAction()) ||
                !BridgeProtocol.SYSTEM_UI.equals(intent.getPackage())) return;
            Bundle input = intent.getExtras();
            if (input == null) return;
            input.setClassLoader(ResultReceiver.class.getClassLoader());
            Object supplied = input.get("reply");
            if (!(supplied instanceof ResultReceiver)) return;
            reply = (ResultReceiver) supplied;
            Object rawId = input.get("requestId");
            if (rawId instanceof String && ((String) rawId).length() <= 64) requestId = (String) rawId;
            IBinder token = input.getBinder("clientToken");
            Bundle command = BridgeProtocol.validate(input);
            requestId = command.getString("requestId", "");
            execute(command, reply, token);
        } catch (Throwable error) {
            setError(friendly(error));
            if (reply != null) {
                try { deliver(reply, requestId, false, friendly(error)); } catch (Throwable ignored) { }
            }
        }
    }

    private static void execute(Bundle input, ResultReceiver reply, IBinder token) throws Exception {
        String command = input.getString("command", "");
        String requestId = input.getString("requestId", "");
        if (BridgeProtocol.isMutation(command)) {
            long sequence = input.getLong("sequence");
            long now = SystemClock.elapsedRealtimeNanos();
            if (sequence <= lastMutationSequence || sequence < Math.max(0L, now - MAX_SEQUENCE_AGE_NANOS) ||
                sequence > now + MAX_SEQUENCE_AGE_NANOS)
                throw new IllegalArgumentException("Outdated command; reconnect and try again");
            // Reserve the sequence even if native execution fails: retries need a new request.
            lastMutationSequence = sequence;
        }
        if ("GET_CURRENT_STATE".equals(command) || "SUBSCRIBE".equals(command)) {
            if ("SUBSCRIBE".equals(command) || input.getBoolean("subscribe", false)) subscribe(reply, token, requestId);
            deliver(reply, requestId, true, "Current SystemUI state");
            return;
        }
        if ("UNSUBSCRIBE".equals(command)) {
            if (token != null && token.equals(subscriberToken)) clearSubscriber();
            deliver(reply, requestId, true, "Subscription closed");
            return;
        }
        if ("PREPARE_ROOT_BOOTSTRAP".equals(command)) {
            bootstrapNonce = input.getString("nonce");
            bootstrapExpires = SystemClock.elapsedRealtime() + 15_000L;
            deliver(reply, requestId, true, "One-use native bootstrap prepared");
            return;
        }
        if ("UPDATE_SETTINGS".equals(command)) {
            StrongToastHook.updateSettings(input);
        } else if ("STOP_STATUS".equals(command) || "STOP_TIMER".equals(command)) {
            Object control = nativeController;
            // Cleanup precedes native hiding, including when the ROM controller is unavailable.
            StrongToastHook.stopFromBridge(control);
        } else {
            if (nativeController == null) throw new IllegalStateException("Native controller is not ready; connect the module or run native setup");
            if (!CAPABILITIES.getBoolean("capture")) throw new IllegalStateException("This firmware does not expose the required native toast capture hook");
            boolean rgb = "START_RGB".equals(command) || input.getBoolean("rainbow", false);
            boolean timer = "START_TIMER".equals(command);
            if ((rgb || timer) && !input.getBoolean("experimentalEffects", true))
                throw new IllegalArgumentException("Enable experimental effects before starting RGB or a timer");
            Bundle nativeRequest = createNativeRequest(input, timer ? "timer" : rgb ? "rgb" : "styled");
            Object control = nativeController;
            XposedHelpers.callMethod(control, "showCustomStrongToast", nativeRequest);
            if (!requestId.equals(StrongToastHook.activeRequestId()))
                throw new IllegalStateException("Native request was not captured; check the SystemUI hook capabilities");
        }
        lastRequestId = requestId;
        lastError = "";
        publish();
        deliver(reply, requestId, true, "Command applied; native visibility is reported separately");
    }

    private static Bundle createNativeRequest(Bundle command, String mode) throws Exception {
        String iconPackage = command.getString("iconPackage", BridgeProtocol.APP_ID);
        String iconName = command.getString("iconName", "ic_strongtoast_native");
        String iconFormat = command.getString("iconFormat", "png");
        if (BridgeProtocol.APP_ID.equals(iconPackage)) iconFormat = "png";
        if (!iconExists(iconPackage, iconName)) {
            iconPackage = BridgeProtocol.APP_ID;
            iconName = "ic_strongtoast_native";
            iconFormat = "png";
        }
        if (!iconExists(iconPackage, iconName)) throw new IllegalStateException("The app's native toast icon is unavailable; reinstall the APK");
        JSONObject text = new JSONObject().put("text", command.getString("text", ""))
            .put("textColor", command.getInt("color", 0xFF0BFC03));
        JSONObject icon = new JSONObject().put("category", "drawable").put("iconFormat", iconFormat)
            .put("iconResName", iconName).put("iconType", 1);
        JSONObject params = new JSONObject().put("left", new JSONObject().put("textParams", text))
            .put("right", new JSONObject().put("iconParams", icon));
        long countdown = command.getInt("durationSeconds") * 1000L;
        Bundle request = new Bundle();
        request.putString("status_bar_strong_toast", "show_custom_strong_toast");
        request.putString("package_name", iconPackage);
        request.putString("strong_toast_category", "text_bitmap");
        request.putString("param", params.toString());
        request.putLong("duration", countdown + ("timer".equals(mode) ? 30_000L : 0L));
        request.putFloat("level", 0f);
        request.putFloat("rapid_rate", 0f);
        request.putString("charge", null);
        request.putInt("string_toast_charge_flag", 0);
        request.putParcelable("target", null);
        request.putString("s9_mode", mode);
        request.putString("s9_style", command.getString("style", "NORMAL").toLowerCase(Locale.ROOT));
        request.putLong("s9_countdown_ms", countdown);
        request.putString("s9_request_id", command.getString("requestId"));
        request.putBoolean("s9_auto_restore", command.getBoolean("autoRestore", true));
        request.putBoolean("s9_reduced_motion", command.getBoolean("reducedMotion", false));
        request.putBoolean("s9_vibration", command.getBoolean("vibration", true));
        request.putBoolean("s9_shake", command.getBoolean("shake", true));
        request.putBoolean("s9_experimental_effects", command.getBoolean("experimentalEffects", true));
        return request;
    }

    private static boolean iconExists(String pkg, String name) {
        if (context == null) return false;
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(pkg);
            return resources.getIdentifier(name, "drawable", pkg) != 0;
        } catch (Throwable ignored) { return false; }
    }

    static void captureController(Object control) {
        if (control == null) return;
        onMain(() -> {
            if (nativeController != control) {
                nativeController = control;
                publish();
            }
        });
    }

    static Object controller() { return nativeController; }

    static void capability(String name, boolean available) {
        if (!CAPABILITIES.containsKey(name) || CAPABILITIES.getBoolean(name) != available) {
            CAPABILITIES.putBoolean(name, available);
            revision++;
        }
    }

    static void onRequestCaptured(Bundle request) {
        sessionId = UUID.randomUUID().toString();
        lastRequestId = request.getString("s9_request_id", "");
        lastError = "";
        publish();
    }

    static void onCleared() { sessionId = ""; publish(); }
    static void onVisibilityChanged() { publish(); }
    static void setError(String error) { lastError = boundedMessage(error); }

    static String boundedText(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= BridgeProtocol.MAX_TEXT_CODEPOINTS ? text :
            text.substring(0, text.offsetByCodePoints(0, BridgeProtocol.MAX_TEXT_CODEPOINTS));
    }

    private static String boundedMessage(String message) {
        if (message == null) return "";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String friendly(Throwable error) {
        return boundedMessage(error.getMessage() == null ? "Command could not be applied" : error.getMessage());
    }

    private static Bundle snapshot() {
        Bundle state;
        try { state = StrongToastHook.snapshotState(); }
        catch (Throwable error) {
            state = new Bundle();
            state.putBoolean("active", false);
            state.putBoolean("visible", false);
            state.putBoolean("visibilityKnown", false);
            lastError = "Native state could not be read on this firmware";
        }
        state.putInt("protocol", BridgeProtocol.VERSION);
        state.putBoolean("connected", true);
        state.putBoolean("moduleActive", true);
        state.putBoolean("nativeReady", nativeController != null);
        state.putString("bootId", BOOT_ID);
        state.putString("sessionId", state.getBoolean("active") ? sessionId : "");
        state.putLong("revision", revision);
        state.putString("lastRequestId", lastRequestId);
        state.putString("lastError", lastError);
        state.putBundle("capabilities", new Bundle(CAPABILITIES));
        state.putBinder("moduleToken", MODULE_TOKEN);
        return state;
    }

    private static void deliver(ResultReceiver receiver, String requestId, boolean success, String message) {
        Bundle result = snapshot(); // Only main thread reads session or native view state.
        result.putBoolean("success", success);
        result.putString("message", boundedMessage(message));
        result.putString("requestId", requestId == null ? "" : requestId);
        submit(() -> { try { receiver.send(success ? 0 : 1, result); } catch (Throwable ignored) { } });
    }

    private static void submit(Runnable action) {
        try { DELIVERIES.execute(action); } catch (Throwable ignored) { }
    }

    static void publish() {
        onMain(() -> {
            revision++;
            if (subscriber == null || publishPending != null) return;
            publishPending = () -> {
                publishPending = null;
                if (subscriber != null) deliver(subscriber, subscriptionRequestId, true, "SystemUI state changed");
            };
            main.post(publishPending);
        });
    }

    private static void subscribe(ResultReceiver reply, IBinder token, String requestId) throws Exception {
        if (token == null || !token.isBinderAlive()) throw new IllegalArgumentException("A live client token is required for subscription");
        clearSubscriber();
        IBinder.DeathRecipient death = () -> onMain(() -> { if (token.equals(subscriberToken)) clearSubscriber(); });
        token.linkToDeath(death, 0);
        subscriber = reply;
        subscriberToken = token;
        subscriberDeath = death;
        subscriptionRequestId = requestId;
        heartbeat = new Runnable() { @Override public void run() {
            if (subscriber == null) return;
            deliver(subscriber, subscriptionRequestId, true, "Current SystemUI state");
            main.postDelayed(this, 1000L);
        }};
        main.postDelayed(heartbeat, 1000L);
    }

    private static void clearSubscriber() {
        if (subscriberToken != null && subscriberDeath != null) {
            try { subscriberToken.unlinkToDeath(subscriberDeath, 0); } catch (Throwable ignored) { }
        }
        if (heartbeat != null) main.removeCallbacks(heartbeat);
        if (publishPending != null) main.removeCallbacks(publishPending);
        heartbeat = null;
        publishPending = null;
        subscriber = null;
        subscriberToken = null;
        subscriberDeath = null;
        subscriptionRequestId = null;
    }

    /** Called only from intercepted setStatus; invalid attempts are suppressed by the hook too. */
    static void handleBootstrap(Object control, Bundle input) {
        onMain(() -> {
            try {
                String nonce = input.getString("s9_bootstrap_nonce", "");
                if (bootstrapNonce == null || SystemClock.elapsedRealtime() >= bootstrapExpires ||
                    !BridgeProtocol.NONCE.matcher(nonce).matches() || !MessageDigest.isEqual(
                        nonce.getBytes(StandardCharsets.US_ASCII), bootstrapNonce.getBytes(StandardCharsets.US_ASCII))) return;
                // Consume before any callback or native-controller state change.
                bootstrapNonce = null;
                bootstrapExpires = 0L;
                nativeController = control;
                IBinder callback = input.getBinder("s9_bootstrap_callback");
                publish();
                if (callback == null) return;
                Bundle reply = new Bundle();
                reply.putInt("protocol", BridgeProtocol.VERSION);
                reply.putBoolean("nativeReady", nativeController != null);
                reply.putString("sessionId", BOOT_ID);
                submit(() -> {
                    Parcel parcel = Parcel.obtain();
                    try {
                        parcel.writeInterfaceToken(BridgeProtocol.BOOTSTRAP_DESCRIPTOR);
                        parcel.writeBundle(reply);
                        callback.transact(1, parcel, null, IBinder.FLAG_ONEWAY);
                    } catch (Throwable ignored) { } finally { parcel.recycle(); }
                });
            } catch (Throwable error) { report("Bootstrap rejected", error); }
        });
    }

    private static void report(String message, Throwable error) {
        lastError = boundedMessage(message);
        try { XposedBridge.log(TAG + message + ": " + error); } catch (Throwable ignored) { }
    }
}
