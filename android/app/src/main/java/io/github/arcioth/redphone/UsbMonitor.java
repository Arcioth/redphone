package io.github.arcioth.redphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** daetron + is_phone_plugged: AB13X (001f:0b21) hotplug, debounced 250 ms. */
final class UsbMonitor {
    static final int VID = 0x001f;
    static final int PID = 0x0b21;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Boolean last;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            schedule();
        }
    };
    private final AudioDeviceCallback audioCb = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            schedule();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            schedule();
        }
    };
    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            final boolean present = present();
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (UsbMonitor.this) {
                        if (!started) return;
                        if (last != null && last == present) return;
                        last = present;
                    }
                    RpLog.log("USB hardware present=" + present);
                    RedPhoneApp.get().controller().setHardwarePresent(present);
                }
            });
        }
    };

    UsbMonitor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.registerAudioDeviceCallback(audioCb, main);
        main.post(scan); // immediate scan
    }

    void stop() {
        if (!started) return;
        synchronized (this) {
            started = false;
            last = null;
        }
        main.removeCallbacks(scan);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.unregisterAudioDeviceCallback(audioCb);
        exec.shutdown();
    }

    private void schedule() {
        main.removeCallbacks(scan);
        main.postDelayed(scan, 250);
    }

    private boolean present() {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (d.getVendorId() == VID && d.getProductId() == PID) return true;
                }
            }
        } catch (RuntimeException e) {
            RpLog.log("usb scan: " + e);
        }
        return RedPhoneApp.get().controller().audio.isRedPhonePresent();
    }
}
