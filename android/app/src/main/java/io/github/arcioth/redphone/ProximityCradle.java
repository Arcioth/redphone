package io.github.arcioth.redphone;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

/** Android-only cradle source: rest the handset over the proximity sensor = on the cradle. */
final class ProximityCradle implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private HandlerThread thread;
    private boolean enabled;
    private boolean near;

    ProximityCradle(Context ctx) {
        sm = (SensorManager) ctx.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    synchronized void setEnabled(boolean want) {
        if (want == enabled || sensor == null) return;
        enabled = want;
        if (want) {
            thread = new HandlerThread("redphone-prox");
            thread.start();
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, new Handler(thread.getLooper()));
        } else {
            sm.unregisterListener(this);
            if (thread != null) thread.quitSafely();
            thread = null;
            if (near) {
                near = false;
                RedPhoneApp.get().controller().resetHook(); // silent: no dial/answer
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        boolean n = e.values[0] < Math.min(sensor.getMaximumRange(), 5f);
        synchronized (this) {
            if (!enabled || n == near) return;
            near = n;
        }
        RedPhoneApp.get().controller().onSpaceChanged(n);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }
}
