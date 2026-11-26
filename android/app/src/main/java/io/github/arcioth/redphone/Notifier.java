package io.github.arcioth.redphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import org.json.JSONObject;

/** bin/redphone-waybar as notifications: the "TEL:" chip, its tooltip, and the incoming-call screen. */
public final class Notifier {
    public static final String CH_STATUS = "status";
    public static final String CH_RING = "ring";
    public static final int STATUS_ID = 4701;
    public static final int RING_ID = 4702;

    private Notifier() {
    }

    public static void createChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel status = new NotificationChannel(CH_STATUS, "TEL status", NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        status.enableVibration(false);
        status.setSound(null, null);
        nm.createNotificationChannel(status);
        NotificationChannel ring = new NotificationChannel(CH_RING, "Incoming Red Phone calls", NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null); // SoundPlayer rings
        ring.enableVibration(false);
        ring.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ring);
    }

    // ---- waybar chip text (bin/redphone-waybar) ----

    public static String chipText(JSONObject st) {
        if (st == null) return "TEL: —";
        String state = st.optString("state", "idle");
        String mode = st.optString("mode", "call");
        String modeLbl = "receive".equals(mode) ? "RCV" : "CALL";
        int secs = st.optInt("call_seconds", 0);
        if ("unplugged".equals(state)) return "TEL: —";
        if ("ringing".equals(state)) return "TEL: RING!";
        if ("dialing".equals(state)) return "TEL: DIAL";
        if ("connecting".equals(state)) return "TEL: CONN";
        if ("connected".equals(state)) return String.format(java.util.Locale.ROOT, "TEL: %02d:%02d", secs / 60, secs % 60);
        if ("auth_required".equals(state)) return "TEL: LOGIN";
        if ("armed".equals(state)) {
            return st.isNull("armed_remaining") || !st.has("armed_remaining")
                    ? "TEL: RCV*" : "TEL: RCV " + st.optInt("armed_remaining") + "s";
        }
        return "TEL: " + modeLbl;
    }

    static String tooltip(JSONObject st) {
        if (st == null) return "Red Phone";
        String mode = "receive".equals(st.optString("mode")) ? "RCV" : "CALL";
        JSONObject pi = st.optJSONObject("provider_info");
        String prov = pi != null ? pi.optString("label", "LOCAL AI") : "LOCAL AI";
        Object d = st.opt("rcv_delay");
        String delay = (d == null || "random".equals(String.valueOf(d))) ? "random (6-56s)" : d + "s";
        String detail = st.optString("detail", "");
        if (detail.isEmpty()) detail = st.optString("state", "");
        return "Red Phone · " + mode + " · " + prov + "\nState: " + detail + "\nReceive Timer: " + delay
                + "\nMODE: switch CALL / RCV\nAI: cycle LOCAL / EVIL TWIN / CHATGPT / GROK";
    }

    // ---- notifications ----

    private static int res(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
    }

    private static PendingIntent open(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent action(Context ctx, String act) {
        Intent i = new Intent(ctx, NotificationActionReceiver.class).setAction(act);
        return PendingIntent.getBroadcast(ctx, Math.abs(act.hashCode() % 10000) + 10, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Action act(Context ctx, String title, String act) {
        return new Notification.Action.Builder(Icon.createWithResource(ctx, res(ctx, "ic_tel")), title, action(ctx, act)).build();
    }

    public static Notification buildStatus(Context ctx, JSONObject st) {
        String state = st == null ? "unplugged" : st.optString("state", "idle");
        boolean unplugged = "unplugged".equals(state);
        String detail = st == null ? "Starting…" : st.optString("detail", "");
        if (unplugged) detail = "Red Phone unplugged";
        Notification.Builder b = new Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(Icon.createWithResource(ctx, res(ctx, "ic_tel")))
                .setColor(0xFFFF2A42)
                .setContentTitle(chipText(st))
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(tooltip(st)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open(ctx));
        if (st != null && !unplugged) {
            boolean receive = "receive".equals(st.optString("mode"));
            if ("ringing".equals(state)) {
                b.addAction(act(ctx, "ANSWER", "answer"));
                b.addAction(act(ctx, "DECLINE", "decline"));
            } else if ("dialing".equals(state) || "connecting".equals(state) || "connected".equals(state)
                    || "auth_required".equals(state)) {
                b.addAction(act(ctx, "HANG UP", "hangup"));
            } else {
                b.addAction(act(ctx, receive ? "RING NOW" : "DIAL", receive ? "test_ring" : "dial"));
                b.addAction(act(ctx, "MODE", "toggle_mode"));
                b.addAction(act(ctx, "AI", "cycle_provider"));
            }
        }
        return b.build();
    }

    /** Updates the status notification and posts/cancels the incoming-call screen. */
    public static void update(Context ctx, JSONObject st) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(STATUS_ID, buildStatus(ctx, st));
            if (st != null && "ringing".equals(st.optString("state"))) {
                nm.notify(RING_ID, buildRing(ctx, st));
            } else {
                nm.cancel(RING_ID);
            }
        } catch (RuntimeException e) {
            RpLog.log("notify failed: " + e);
        }
    }

    public static void cancelAll(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(STATUS_ID);
            nm.cancel(RING_ID);
        }
    }

    private static Notification buildRing(Context ctx, JSONObject st) {
        JSONObject pi = st.optJSONObject("provider_info");
        String who = "Red Phone · " + (pi != null ? pi.optString("label", "LOCAL AI") : "LOCAL AI");
        Notification.Builder b = new Notification.Builder(ctx, CH_RING)
                .setSmallIcon(Icon.createWithResource(ctx, res(ctx, "ic_tel")))
                .setColor(0xFFFF2A42)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(open(ctx))
                .setFullScreenIntent(open(ctx), true);
        if (Build.VERSION.SDK_INT >= 31) {
            Person p = new Person.Builder().setName(who).build();
            b.setStyle(Notification.CallStyle.forIncomingCall(p, action(ctx, "decline"), action(ctx, "answer")));
        } else {
            b.setContentTitle("Incoming call").setContentText(who + " — lift handset!");
            b.addAction(act(ctx, "ANSWER", "answer"));
            b.addAction(act(ctx, "DECLINE", "decline"));
        }
        return b.build();
    }
}
