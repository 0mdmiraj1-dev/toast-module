import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Separate native statusbar client. Never overwrites the tested strongtoast command/DEX. */
public final class S9ToastModes {
    private static void put(Object b, String method, Class<?> type, String key, Object val) throws Exception {
        b.getClass().getMethod(method, String.class, type).invoke(b, key, val);
    }
    private static String json(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 32) sb.append(String.format(Locale.ROOT, "\\u%04X", (int)c));
            else sb.append(c);
        }
        return sb.toString();
    }
    private static void send(Object manager, Object b, Class<?> bt) throws Exception {
        Method m = manager.getClass().getMethod("setStatus", int.class, String.class, bt);
        m.invoke(manager, 1, "strong_toast_action", b);
    }
    public static void main(String[] args) {
        if (args.length != 6) {
            System.err.println("Usage: S9ToastModes <rgb|timer|stop> <base64-text|-> <sec> <pkg> <icon> <png|svg>");
            System.exit(2);
        }
        try {
            String mode = args[0];
            if (!mode.equals("rgb") && !mode.equals("timer") && !mode.equals("stop"))
                throw new IllegalArgumentException("Bad mode");
            Class<?> looper = Class.forName("android.os.Looper");
            looper.getMethod("prepareMainLooper").invoke(null);
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Object context = at.getMethod("getSystemContext").invoke(thread);
            Object manager = Class.forName("android.content.Context")
                    .getMethod("getSystemService", String.class).invoke(context, "statusbar");
            if (manager == null) throw new IllegalStateException("statusbar unavailable");
            Class<?> bt = Class.forName("android.os.Bundle");
            Object bundle = bt.getConstructor().newInstance();
            put(bundle, "putString", String.class, "status_bar_strong_toast",
                    mode.equals("stop") ? "hide_strong_toast" : "show_custom_strong_toast");
            if (!mode.equals("stop")) {
                String text = new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8);
                if (text.isEmpty()) throw new IllegalArgumentException("empty text");
                int seconds = Integer.parseInt(args[2]);
                if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Duration: 1-3600s");
                String pkg = args[3], icon = args[4], format = args[5];
                if (!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*") ||
                    !icon.matches("[a-z][a-z0-9_]*") ||
                    !(format.equals("png") || format.equals("svg")))
                    throw new IllegalArgumentException("invalid icon resource");
                String param = "{\"left\":{\"textParams\":{\"text\":\"" + json(text) +
                    "\",\"textColor\":" + (mode.equals("timer") ? -16711936 : -1) + "}}," +
                    "\"right\":{\"iconParams\":{\"category\":\"drawable\",\"iconFormat\":\"" +
                    format + "\",\"iconResName\":\"" + icon + "\",\"iconType\":1}}}";
                put(bundle, "putString", String.class, "package_name", pkg);
                put(bundle, "putString", String.class, "strong_toast_category", "text_bitmap");
                put(bundle, "putString", String.class, "param", param);
                put(bundle, "putLong", long.class, "duration", Long.valueOf(seconds * 1000L));
                put(bundle, "putFloat", float.class, "level", Float.valueOf(0f));
                put(bundle, "putFloat", float.class, "rapid_rate", Float.valueOf(0f));
                put(bundle, "putString", String.class, "charge", null);
                put(bundle, "putInt", int.class, "string_toast_charge_flag", Integer.valueOf(0));
                put(bundle, "putParcelable", Class.forName("android.os.Parcelable"), "target", null);
                // V0.5-only extra. Old SystemUI ignores it; Xposed consumes it.
                put(bundle, "putString", String.class, "s9_mode", mode);
            }
            send(manager, bundle, bt);
            System.out.println("Strong Toast " + mode + " request sent. V0.5 LSPosed module required for animation.");
        } catch (Throwable e) {
            while (e instanceof InvocationTargetException &&
                   ((InvocationTargetException)e).getCause() != null)
                e = ((InvocationTargetException)e).getCause();
            System.err.println("S9ToastModes failed: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
