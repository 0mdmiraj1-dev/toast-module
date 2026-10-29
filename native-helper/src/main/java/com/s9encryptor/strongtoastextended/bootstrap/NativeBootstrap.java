package com.s9encryptor.strongtoastextended.bootstrap;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Short-lived, root-only controller bootstrap. It cannot start or stop a toast. */
public final class NativeBootstrap {
    private static final String PREFIX = "S9BOOT1 ";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DESCRIPTOR = "com.s9encryptor.strongtoastextended.BOOTSTRAP";
    private static final int CALLBACK_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int MAX_LINE = 8192;
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern REQUEST_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final PrintWriter OUTPUT = new PrintWriter(System.out, true);

    private NativeBootstrap() { }

    public static void main(String[] args) {
        if (args.length != 0) {
            fail("", "invalid_arguments", "This helper accepts requests on stdin only");
            return;
        }
        if (Process.myUid() != 0) {
            fail("", "root_required", "Root UID 0 is required");
            return;
        }
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            final Handler main = new Handler(Looper.getMainLooper());
            Thread input = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        readRequests(new InputStreamReader(System.in, "UTF-8"), main);
                    } catch (IOException e) {
                        fail("", "protocol_error", "Bootstrap input closed or exceeded its limit");
                    } finally {
                        // The root shell receives a normal exit and runs its private-file cleanup.
                        System.exit(0);
                    }
                }
            }, "StrongToastBootstrapInput");
            input.start();
            Looper.loop();
        } catch (Throwable e) {
            // Do not print framework stack traces or a nonce to the app diagnostics stream.
            fail("", "framework_unavailable", "Android framework bootstrap is unavailable");
        }
    }

    private static void readRequests(Reader input, Handler main) throws IOException {
        String line;
        while ((line = readLine(input)) != null) {
            String requestId = "";
            try {
                JSONObject request = new JSONObject(line);
                requestId = request.optString("requestId", "");
                if (!REQUEST_ID.matcher(requestId).matches() || request.optInt("protocol", 0) != 1) {
                    fail("", "invalid_request", "Protocol version or request ID is invalid");
                    continue;
                }
                String operation = request.optString("op", "");
                if ("ROOT_STATUS".equals(operation)) {
                    JSONObject response = response(requestId, true, "Root access granted");
                    response.put("uid", Process.myUid());
                    send(response);
                } else if ("BOOTSTRAP".equals(operation)) {
                    String nonce = request.optString("nonce", "");
                    if (!NONCE.matcher(nonce).matches()) {
                        fail(requestId, "invalid_nonce", "A valid one-use bootstrap nonce is required");
                        continue;
                    }
                    bootstrap(requestId, nonce, main);
                } else if ("EXIT".equals(operation)) {
                    send(response(requestId, true, "Root bootstrap session closed"));
                    return;
                } else {
                    fail(requestId, "unsupported_operation", "Unsupported bootstrap operation");
                }
            } catch (Throwable e) {
                fail(requestId, "request_failed", "Bootstrap request could not be completed");
            }
        }
    }

    private static Context frameworkContext;

    // systemMain initializes GPU and vendor performance services as if this were
    // system_server. A headless bootstrap only needs a framework Context.
    private static Context getFrameworkContext() throws Exception {
        if (frameworkContext == null) {
            Class<?> type = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object thread = constructor.newInstance();
            frameworkContext = (Context) type.getMethod("getSystemContext").invoke(thread);
        }
        return frameworkContext;
    }

    private static void bootstrap(String requestId, final String nonce, Handler main) throws Exception {
        final BootstrapCallback callback = new BootstrapCallback();
        final AtomicBoolean invocationFailed = new AtomicBoolean(false);
        if (!main.post(new Runnable() {
            @Override public void run() {
                try {
                    Context context = getFrameworkContext();
                    callback.expectedUid = context.getPackageManager().getApplicationInfo(SYSTEM_UI, 0).uid;
                    Object manager = context.getSystemService("statusbar");
                    if (manager == null) throw new IllegalStateException("statusbar unavailable");
                    Bundle bundle = new Bundle();
                    bundle.putString("status_bar_strong_toast", "s9_bootstrap");
                    bundle.putString("s9_bootstrap_nonce", nonce);
                    bundle.putBinder("s9_bootstrap_callback", callback);
                    manager.getClass().getMethod("setStatus", int.class, String.class, Bundle.class)
                            .invoke(manager, 1, "strong_toast_action", bundle);
                } catch (Throwable e) {
                    invocationFailed.set(true);
                    callback.done.countDown();
                }
            }
        })) {
            fail(requestId, "framework_unavailable", "Android main handler is unavailable");
            return;
        }
        // Leave transport time within the app's four-second request timeout.
        boolean completed = callback.done.await(3500L, TimeUnit.MILLISECONDS);
        callback.accepting.set(false);
        if (invocationFailed.get()) {
            fail(requestId, "native_api_unavailable", "The native SystemUI bootstrap API is unavailable");
        } else if (!completed || callback.acknowledgement == null) {
            fail(requestId, "bootstrap_timeout", "The enabled SystemUI module did not acknowledge bootstrap");
        } else {
            Bundle ack = callback.acknowledgement;
            boolean ready = ack.getBoolean("nativeReady", false);
            JSONObject response = response(requestId, ready,
                    ready ? "Native controller ready" : "Native controller is not ready");
            response.put("nativeReady", ready);
            response.put("sessionId", ack.getString("sessionId", ""));
            send(response);
        }
    }

    private static final class BootstrapCallback extends Binder {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean accepting = new AtomicBoolean(true);
        volatile int expectedUid = -1;
        volatile Bundle acknowledgement;

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != CALLBACK_TRANSACTION) return super.onTransact(code, data, reply, flags);
            // This check runs in Binder's transaction thread, before any Handler dispatch.
            if (Binder.getCallingUid() != expectedUid || !accepting.get() || data.dataAvail() > MAX_LINE * 2) return false;
            try {
                data.enforceInterface(DESCRIPTOR);
                Bundle incoming = data.readBundle(NativeBootstrap.class.getClassLoader());
                if (incoming == null || incoming.getInt("protocol", 0) != 1) return false;
                String session = incoming.getString("sessionId", "");
                if (session == null || session.length() > 128) return false;
                Bundle copy = new Bundle();
                copy.putInt("protocol", 1);
                copy.putBoolean("nativeReady", incoming.getBoolean("nativeReady", false));
                copy.putString("sessionId", session);
                if (!accepting.compareAndSet(true, false)) return false;
                acknowledgement = copy;
                done.countDown();
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    private static String readLine(Reader input) throws IOException {
        StringBuilder line = new StringBuilder(128);
        int value;
        int count = 0;
        while ((value = input.read()) != -1) {
            if (value == '\n') return line.toString();
            if (++count > MAX_LINE) throw new IOException("Input limit exceeded");
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static JSONObject response(String requestId, boolean success, String message)
            throws Exception {
        JSONObject result = new JSONObject();
        result.put("protocol", 1);
        result.put("requestId", requestId);
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    private static void fail(String requestId, String code, String message) {
        try {
            JSONObject result = response(requestId, false, message);
            result.put("errorCode", code);
            if ("root_required".equals(code)) result.put("uid", Process.myUid());
            send(result);
        } catch (Exception ignored) {
            synchronized (OUTPUT) {
                OUTPUT.println(PREFIX + "{\"protocol\":1,\"requestId\":\"\",\"success\":false}");
            }
        }
    }

    private static void send(JSONObject response) {
        String line = response.toString();
        if (line.length() > MAX_LINE) return;
        synchronized (OUTPUT) { OUTPUT.println(PREFIX + line); }
    }
}
