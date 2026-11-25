package io.github.arcioth.redphone;

import android.content.Context;

/**
 * STUB — owned by the AUDIO module. Port of daemon.SoundPlayer (paplay loop / one-shot).
 *
 * Contract:
 *  - Sounds: assets/sounds/<name>.wav when present (pickup/hangup always ship; dialtone/ringtone only
 *    if the user downloaded them with yt-dlp), otherwise dialtone/ringtone are synthesized.
 *  - TARGET_PHONE  = the red handset earpiece (AudioRouter phone sink; fall back to default output).
 *  - TARGET_SPEAKER = the phone's built-in loudspeaker (desktop: laptop speakers) for the RCV ringtone.
 *  - playLoop: stops any previous loop, then loops until stop(). Non-blocking.
 *  - playOneshot: independent of the loop; stop() does NOT cut a one-shot (daemon semantics).
 *  - Thread-safe; never blocks the caller (controller lock is held while calling).
 */
public final class SoundPlayer {
    public static final String RINGTONE = "ringtone";
    public static final String DIALTONE = "dialtone";
    public static final String PICKUP = "pickup";
    public static final String HANGUP = "hangup";

    public static final int TARGET_PHONE = 0;
    public static final int TARGET_SPEAKER = 1;

    public SoundPlayer(Context ctx) {
    }

    public void playLoop(String sound, int target) {
    }

    public void playOneshot(String sound, int target) {
    }

    public void stop() {
    }
}
