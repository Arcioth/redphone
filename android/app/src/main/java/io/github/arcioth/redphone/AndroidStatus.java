package io.github.arcioth.redphone;

import android.content.Context;

import org.json.JSONObject;

/**
 * STUB — owned by the UI module. The snapshot's "android" object (settings + permission status) that
 * android.js renders. Called from Controller.snapshot() with the controller lock held, on every poll:
 * must be cheap and must only use non-blocking getters.
 */
public final class AndroidStatus {
    private AndroidStatus() {
    }

    public static JSONObject snapshot(Context ctx, Controller c) {
        return new JSONObject();
    }
}
