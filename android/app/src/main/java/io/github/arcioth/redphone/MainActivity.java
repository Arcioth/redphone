package io.github.arcioth.redphone;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** STUB — owned by the UI module. The Red Phone deck (desktop popup window). */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RedPhoneService.start(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (HookInput.handle(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
