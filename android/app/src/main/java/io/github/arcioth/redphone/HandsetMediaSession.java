package io.github.arcioth.redphone;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.KeyEvent;

/** run_handset_loop: routes the AB13X KEY_PLAYPAUSE / KEY_STOPCD button to the controller. */
final class HandsetMediaSession {
    private final MediaSession session;
    private final HandlerThread thread;

    HandsetMediaSession(Context ctx) {
        thread = new HandlerThread("redphone-handset");
        thread.start();
        session = new MediaSession(ctx.getApplicationContext(), "RedPhone");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev != null && HookInput.handle(ev)) return true;
                return super.onMediaButtonEvent(intent);
            }
        }, new Handler(thread.getLooper()));
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        setActive(false);
    }

    void setActive(boolean active) {
        long actions = PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(active ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED, 0, 1f)
                .build());
        session.setActive(active);
    }

    void release() {
        try {
            session.setActive(false);
            session.release();
        } catch (RuntimeException ignored) {
        }
        thread.quitSafely();
    }
}
