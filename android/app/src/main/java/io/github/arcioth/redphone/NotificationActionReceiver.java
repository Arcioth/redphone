package io.github.arcioth.redphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Notification buttons: answer / decline / hangup / dial / test_ring / toggle_mode / cycle_provider. */
public final class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final String a = intent.getAction();
        if (a == null) return;
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    if ("toggle_mode".equals(a)) {
                        ApiRouter.handle("POST", "/api/mode", b.put("mode", "toggle"));
                    } else if ("cycle_provider".equals(a)) {
                        ApiRouter.handle("POST", "/api/provider", b.put("provider", "cycle"));
                    } else {
                        ApiRouter.handle("POST", "/api/action", b.put("action", a));
                    }
                } catch (Exception e) {
                    RpLog.log("notification action " + a + " failed: " + e);
                } finally {
                    pr.finish();
                }
            }
        }, "redphone-notif-action").start();
    }
}
