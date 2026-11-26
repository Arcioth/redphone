package io.github.arcioth.redphone;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

/** Quick Settings "TEL" tile = the Waybar chip: tap switches CALL / RCV. */
public final class TelTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Controller.Listener listener;

    @Override
    public void onStartListening() {
        super.onStartListening();
        final Controller c = RedPhoneApp.get().controller();
        listener = new Controller.Listener() {
            @Override
            public void onState(final JSONObject snap) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        paint(snap);
                    }
                });
            }
        };
        c.addListener(listener);
        paint(c.snapshot());
    }

    @Override
    public void onStopListening() {
        if (listener != null) RedPhoneApp.get().controller().removeListener(listener);
        listener = null;
        super.onStopListening();
    }

    @Override
    public void onClick() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                RedPhoneApp.get().controller().setMode("toggle");
            }
        }, "redphone-tile").start();
    }

    private void paint(JSONObject st) {
        Tile t = getQsTile();
        if (t == null) return;
        String chip = Notifier.chipText(st);
        t.setLabel("TEL");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(chip.replace("TEL: ", ""));
        t.setIcon(Icon.createWithResource(this, getResources().getIdentifier("ic_tel", "drawable", getPackageName())));
        t.setState(st != null && st.optBoolean("plugged") ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
