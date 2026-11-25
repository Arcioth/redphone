package io.github.arcioth.redphone;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** daemon.log(): logcat (tag RedPhone) + files/redphone.log (rotated at 512 KiB). */
public final class RpLog {
    public static final String TAG = "RedPhone";
    private static final long MAX_BYTES = 512 * 1024;

    private RpLog() {
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
        RedPhoneApp app = RedPhoneApp.get();
        if (app == null) return;
        synchronized (RpLog.class) {
            try {
                File f = new File(app.getFilesDir(), "redphone.log");
                if (f.length() > MAX_BYTES) {
                    File old = new File(app.getFilesDir(), "redphone.log.1");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + msg + "\n";
                FileOutputStream out = new FileOutputStream(f, true);
                try {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
