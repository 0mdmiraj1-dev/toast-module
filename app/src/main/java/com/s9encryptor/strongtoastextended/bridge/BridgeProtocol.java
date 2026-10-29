package com.s9encryptor.strongtoastextended.bridge;

import android.os.Bundle;
import java.util.Locale;
import java.util.regex.Pattern;

/** Framework-only protocol shared by the native app and the injected SystemUI code. */
public final class BridgeProtocol {
    public static final int VERSION = 1;
    public static final String APP_ID = "com.s9encryptor.strongtoastextended";
    public static final String SYSTEM_UI = "com.android.systemui";
    public static final String PERMISSION = APP_ID + ".permission.CONTROL";
    public static final String ACTION = APP_ID + ".CONTROL";
    public static final String BOOTSTRAP_DESCRIPTOR = APP_ID + ".BOOTSTRAP";
    public static final int MAX_TEXT_CODEPOINTS = 8192;
    public static final Pattern NONCE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private BridgeProtocol() { }

    public static boolean isMutation(String command) {
        return !("GET_CURRENT_STATE".equals(command) || "SUBSCRIBE".equals(command)
            || "UNSUBSCRIBE".equals(command) || "PREPARE_ROOT_BOOTSTRAP".equals(command));
    }

    /** Copies only bounded primitives; never trusts custom Parcelable input. */
    public static Bundle validate(Bundle input) {
        if (input == null || input.getInt("protocol", 0) != VERSION) throw invalid("Unsupported command version");
        String id = string(input, "requestId", "", 64);
        if (!UUID.matcher(id).matches()) throw invalid("Invalid request identifier");
        long sequence = input.getLong("sequence", 0L);
        if (sequence <= 0L) throw invalid("Invalid command sequence");
        String command = string(input, "command", "", 40);
        if (!("START_STATUS".equals(command) || "UPDATE_STATUS".equals(command)
            || "STOP_STATUS".equals(command) || "START_RGB".equals(command)
            || "START_TIMER".equals(command) || "STOP_TIMER".equals(command)
            || "GET_CURRENT_STATE".equals(command) || "UPDATE_SETTINGS".equals(command)
            || "SUBSCRIBE".equals(command) || "UNSUBSCRIBE".equals(command)
            || "PREPARE_ROOT_BOOTSTRAP".equals(command))) throw invalid("Unknown command");
        Bundle result = new Bundle();
        result.putInt("protocol", VERSION);
        result.putString("requestId", id);
        result.putLong("sequence", sequence);
        result.putString("command", command);
        result.putBoolean("subscribe", bool(input, "subscribe", false));
        for (String key : new String[]{"autoRestore", "vibration", "shake", "experimentalEffects"})
            result.putBoolean(key, bool(input, key, true));
        result.putBoolean("reducedMotion", bool(input, "reducedMotion", false));
        if ("PREPARE_ROOT_BOOTSTRAP".equals(command)) {
            String nonce = string(input, "nonce", "", 64);
            if (!NONCE.matcher(nonce).matches()) throw invalid("Invalid bootstrap nonce");
            result.putString("nonce", nonce);
        }
        if ("START_STATUS".equals(command) || "UPDATE_STATUS".equals(command)
            || "START_RGB".equals(command) || "START_TIMER".equals(command)) {
            String text = string(input, "text", "", MAX_TEXT_CODEPOINTS * 2);
            if (text.trim().isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT_CODEPOINTS)
                throw invalid("Status text is empty or too long");
            int seconds = input.getInt("durationSeconds", 0);
            if (seconds < 1 || seconds > 3600) throw invalid("Duration must be between 1 second and 60 minutes");
            String style = string(input, "style", "NORMAL", 12).toUpperCase(Locale.ROOT);
            if (!("NORMAL".equals(style) || "BOLD".equals(style) || "ITALIC".equals(style))) throw invalid("Invalid font style");
            String iconPackage = string(input, "iconPackage", APP_ID, 100);
            String iconName = string(input, "iconName", "ic_strongtoast_native", 64);
            String iconFormat = string(input, "iconFormat", "png", 5);
            boolean ownIcon = APP_ID.equals(iconPackage) && "ic_strongtoast_native".equals(iconName);
            boolean hyperIcon = "com.sevtinge.hyperceiler".equals(iconPackage) && "ic_hyperceiler".equals(iconName);
            if (!(ownIcon || hyperIcon) || !("png".equals(iconFormat) || "svg".equals(iconFormat))) throw invalid("Invalid icon resource");
            Object colorValue = input.get("color");
            if (colorValue != null && !(colorValue instanceof Integer)) throw invalid("Invalid text color");
            result.putString("text", text); // Preserve Unicode and trailing whitespace exactly.
            result.putInt("durationSeconds", seconds);
            result.putString("style", style);
            result.putInt("color", input.getInt("color", 0xFF0BFC03));
            result.putBoolean("rainbow", bool(input, "rainbow", false));
            result.putString("iconPackage", iconPackage);
            result.putString("iconName", iconName);
            result.putString("iconFormat", iconFormat);
        }
        return result;
    }

    private static String string(Bundle b, String key, String fallback, int max) {
        Object value = b.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String) || ((String)value).length() > max) throw invalid("Invalid " + key);
        return (String)value;
    }
    private static boolean bool(Bundle b, String key, boolean fallback) {
        Object value = b.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw invalid("Invalid " + key);
        return (Boolean)value;
    }
    private static IllegalArgumentException invalid(String text) { return new IllegalArgumentException(text); }
}
