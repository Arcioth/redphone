package io.github.arcioth.redphone.ui;

import android.net.Uri;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

/**
 * The deck needs no browser features: deny camera/mic/geolocation/file pickers/new windows, and send
 * page errors to logcat (not to redphone.log: the deck polls every 400 ms).
 */
public final class DeckChromeClient extends WebChromeClient {

    @Override
    public boolean onConsoleMessage(ConsoleMessage m) {
        if (m != null && (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)) {
            Log.w("RedPhone", "deck js: " + m.message() + " @" + m.lineNumber());
        }
        return true;
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        if (request != null) request.deny();
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        if (callback != null) callback.invoke(origin, false, false);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
            FileChooserParams fileChooserParams) {
        return false;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
        return false;
    }
}
