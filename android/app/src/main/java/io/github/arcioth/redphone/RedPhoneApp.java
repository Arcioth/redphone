package io.github.arcioth.redphone;

import android.app.Application;

public final class RedPhoneApp extends Application {
    private static volatile RedPhoneApp instance;
    private Controller controller;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notifier.createChannels(this);
    }

    public static RedPhoneApp get() {
        return instance;
    }

    public synchronized Controller controller() {
        if (controller == null) controller = new Controller(this);
        return controller;
    }
}
