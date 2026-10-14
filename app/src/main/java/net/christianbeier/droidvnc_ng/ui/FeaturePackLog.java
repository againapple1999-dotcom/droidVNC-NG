package net.christianbeier.droidvnc_ng.ui;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A small on-device log. Never records passwords, tokens, contact data, or filenames. */
public final class FeaturePackLog {
    private FeaturePackLog() {}
    public static synchronized void append(Context context, String message) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        String old = read(context);
        String next = stamp + " — " + message + "\n" + old;
        if (next.length() > 12000) next = next.substring(0,12000);
        context.getSharedPreferences("feature_pack_log", Context.MODE_PRIVATE)
            .edit().putString("entries", next).apply();
    }
    public static String read(Context context) {
        return context.getSharedPreferences("feature_pack_log", Context.MODE_PRIVATE)
            .getString("entries", "No local events yet.");
    }
}
