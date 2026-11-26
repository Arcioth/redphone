package io.github.arcioth.redphone.ui;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import io.github.arcioth.redphone.RpLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the deck from the APK on the virtual origin https://redphone.local/ (the desktop served
 * ui/index.html from http://127.0.0.1:47621/). Every other URL gets a 404 and every navigation away
 * is blocked, so the page holding the RedPhoneAndroid bridge only ever runs our own code.
 */
public final class DeckClient extends WebViewClient {

    private final AssetManager assets;
    private volatile DeckHost host;

    public DeckClient(AssetManager assets, DeckHost host) {
        this.assets = assets;
        this.host = host;
    }

    public void detach() {
        host = null;
    }

    /** Runs on a WebView IO thread (not the UI thread). */
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri u = request == null ? null : request.getUrl();
        DeckRoutes.Route route = u == null ? null
                : DeckRoutes.resolve(request.getMethod(), u.getScheme(), u.getHost(), u.getPort(), u.getPath());
        if (route == null) return notFound();
        try {
            if (route.injectHead) {
                String html = new String(readAll(assets.open(route.asset)), StandardCharsets.UTF_8);
                byte[] out = DeckRoutes.injectHead(html).getBytes(StandardCharsets.UTF_8);
                return response(route.mime, 200, "OK", new ByteArrayInputStream(out));
            }
            // AssetManager streams are closed by the WebView once the body is read.
            return response(route.mime, 200, "OK", assets.open(route.asset));
        } catch (IOException e) {
            RpLog.log("deck: missing asset " + route.asset + ": " + e);
            return notFound();
        }
    }

    /** Only reloads of the deck itself are allowed; links, redirects and frames elsewhere are blocked. */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri u = request == null ? null : request.getUrl();
        if (u != null && DeckRoutes.isDeckOrigin(u.getScheme(), u.getHost(), u.getPort())) return false;
        RpLog.log("deck: blocked navigation to " + (u == null ? "?" : u.getScheme() + "://" + u.getHost()));
        return true;
    }

    /**
     * The renderer died (crash, or killed for memory while the app was in the background). Returning
     * true keeps the app alive; the host must drop this WebView and build a new one.
     */
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        boolean crashed = detail != null && detail.didCrash();
        RpLog.log("deck: renderer gone (crashed=" + crashed + ")");
        DeckHost h = host;
        if (h != null) {
            h.onDeckRendererGone(view, crashed);
        } else {
            try {
                view.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        return true;
    }

    private static WebResourceResponse response(String mime, int code, String reason, InputStream data) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "no-referrer");
        if ("text/html".equals(mime)) headers.put("Content-Security-Policy", DeckRoutes.CONTENT_SECURITY_POLICY);
        String encoding = DeckRoutes.isText(mime) ? "UTF-8" : null;
        return new WebResourceResponse(mime, encoding, code, reason, headers, data);
    }

    private static WebResourceResponse notFound() {
        return response("text/plain", 404, "Not Found", new ByteArrayInputStream(new byte[0]));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
