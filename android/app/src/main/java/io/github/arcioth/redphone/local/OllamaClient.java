package io.github.arcioth.redphone.local;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal Ollama HTTP client (HttpURLConnection + org.json, no android.* imports): /api/tags and
 * /api/chat with NDJSON streaming, mirroring the urllib requests of local_call.py.
 *
 * <p>Every open connection is registered with a {@link Tracker} so that a call session can abort a
 * blocked request from another thread by {@link HttpURLConnection#disconnect()}.
 */
public final class OllamaClient {

    /** Registry of in-flight connections (owned by the call session). */
    public interface Tracker {
        /** @return false when the owner is already cancelled (the request is then not sent). */
        boolean register(HttpURLConnection c);

        void unregister(HttpURLConnection c);
    }

    /** Receives streamed reply tokens. Return false to stop reading. */
    public interface TokenSink {
        boolean onToken(String token) throws InterruptedException;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final int TAGS_TIMEOUT_MS = 2500;
    static final int CHAT_CONNECT_TIMEOUT_MS = 6000;
    /** local_call.py uses 45 s; a phone-hosted model can be slower to produce its first token. */
    static final int CHAT_READ_TIMEOUT_MS = 60000;
    static final int WARM_TIMEOUT_MS = 60000;

    private final String base;
    private final Tracker tracker;

    public OllamaClient(String baseUrl, Tracker tracker) {
        this.base = normalizeBase(baseUrl);
        this.tracker = tracker;
    }

    public String baseUrl() {
        return base;
    }

    /** The base URL without any user:password@ part, for logs and the UI. */
    public String displayUrl() {
        return redact(base);
    }

    static String redact(String url) {
        int scheme = url.indexOf("://");
        int from = scheme < 0 ? 0 : scheme + 3;
        int slash = url.indexOf('/', from);
        int at = url.lastIndexOf('@', slash < 0 ? url.length() - 1 : slash);
        return at >= from ? url.substring(0, from) + url.substring(at + 1) : url;
    }

    /** Trims, drops trailing slashes and adds http:// when the user typed just host:port. */
    public static String normalizeBase(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) u = "http://127.0.0.1:11434";
        if (!u.contains("://")) u = "http://" + u;
        return u;
    }

    /** Ollama is a loopback/LAN service: bypass any configured HTTP proxy for private hosts. */
    static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) h = h.substring(1, h.endsWith("]") ? h.length() - 1 : h.length());
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.equals("::1")) return true;
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        if (h.startsWith("fe80:") || h.startsWith("fd") || h.startsWith("fc")) return h.contains(":");
        if (h.startsWith("100.")) { // CGNAT range, e.g. Tailscale 100.64.0.0/10
            try {
                int second = Integer.parseInt(h.split("\\.")[1]);
                return second >= 64 && second <= 127;
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (h.startsWith("172.")) {
            try {
                int second = Integer.parseInt(h.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    private HttpURLConnection open(String path, int connectMs, int readMs) throws IOException {
        URL url = new URL(base + path);
        HttpURLConnection c = (HttpURLConnection) (isPrivateHost(url.getHost())
                ? url.openConnection(Proxy.NO_PROXY) : url.openConnection());
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setUseCaches(false);
        // urllib (local_call.py) closes after each request; a pooled socket buys nothing on loopback.
        c.setRequestProperty("Connection", "close");
        if (tracker != null && !tracker.register(c)) {
            c.disconnect();
            throw new InterruptedIOException("cancelled");
        }
        return c;
    }

    private void close(HttpURLConnection c) {
        if (c == null) return;
        if (tracker != null) tracker.unregister(c);
        c.disconnect();
    }

    private static void postJson(HttpURLConnection c, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(UTF8);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream out = c.getOutputStream();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static IOException httpError(HttpURLConnection c, int code) {
        String msg = "HTTP " + code;
        InputStream err = c.getErrorStream();
        if (err != null) {
            try {
                String body = new String(readLimited(err, 4096), UTF8).trim();
                String e = body;
                try {
                    e = new JSONObject(body).optString("error", body);
                } catch (JSONException ignored) {
                }
                if (!e.isEmpty()) msg += ": " + e;
            } catch (IOException ignored) {
            } finally {
                try {
                    err.close();
                } catch (IOException ignored) {
                }
            }
        }
        return new IOException(msg);
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while (bos.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - bos.size()))) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** GET /api/tags -> model names (possibly empty). Throws when Ollama is unreachable. */
    public List<String> listModels() throws IOException {
        HttpURLConnection c = open("/api/tags", TAGS_TIMEOUT_MS, TAGS_TIMEOUT_MS);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw httpError(c, code);
            InputStream in = c.getInputStream();
            byte[] body;
            try {
                body = readLimited(in, 4 * 1024 * 1024);
            } finally {
                in.close();
            }
            List<String> names = new ArrayList<String>();
            try {
                JSONArray models = new JSONObject(new String(body, UTF8)).optJSONArray("models");
                if (models != null) {
                    for (int i = 0; i < models.length(); i++) {
                        JSONObject m = models.optJSONObject(i);
                        String name = m == null ? "" : m.optString("name", "");
                        if (!name.isEmpty()) names.add(name);
                    }
                }
            } catch (JSONException e) {
                throw new IOException("bad /api/tags reply: " + e.getMessage());
            }
            return names;
        } finally {
            close(c);
        }
    }

    /** _warm_ollama: tiny non-streaming request so the model is loaded before the first turn. */
    public void warm(String model, boolean evil) throws IOException {
        JSONObject body = new JSONObject();
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", "selam"));
            JSONObject opts = new JSONObject();
            if (evil) opts.put("num_gpu", 22);
            opts.put("num_predict", 1);
            body.put("model", model).put("messages", msgs).put("stream", false).put("keep_alive", "10m")
                    .put("options", opts);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection c = open("/api/chat", CHAT_CONNECT_TIMEOUT_MS, WARM_TIMEOUT_MS);
        try {
            postJson(c, body);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw httpError(c, code);
            InputStream in = c.getInputStream();
            try {
                readLimited(in, 1024 * 1024);
            } finally {
                in.close();
            }
        } finally {
            close(c);
        }
    }

    /** stream_ollama_and_speak options. */
    public static JSONObject chatOptions(boolean evil) {
        JSONObject opts = new JSONObject();
        try {
            opts.put("num_predict", 115);
            opts.put("temperature", evil ? 0.88 : 0.72);
            if (evil) opts.put("num_gpu", 22);
        } catch (JSONException ignored) {
        }
        return opts;
    }

    /**
     * POST /api/chat with stream=true, keep_alive 10m. Tokens are delivered in order; returns when the
     * stream reports done, ends, or the sink returns false. A mid-stream {"error": ...} line throws.
     */
    public void chat(String model, JSONArray messages, JSONObject options, TokenSink sink)
            throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model).put("messages", messages).put("stream", true).put("keep_alive", "10m")
                    .put("options", options);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection c = open("/api/chat", CHAT_CONNECT_TIMEOUT_MS, CHAT_READ_TIMEOUT_MS);
        try {
            postJson(c, body);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw httpError(c, code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject chunk;
                    try {
                        chunk = new JSONObject(line);
                    } catch (JSONException e) {
                        throw new IOException("bad stream chunk: " + e.getMessage());
                    }
                    String err = chunk.optString("error", "");
                    if (!err.isEmpty()) throw new IOException(err);
                    JSONObject msg = chunk.optJSONObject("message");
                    String tok = msg == null ? "" : msg.optString("content", "");
                    if (!tok.isEmpty() && !sink.onToken(tok)) break;
                    if (chunk.optBoolean("done", false)) break;
                }
            } finally {
                r.close();
            }
        } finally {
            close(c);
        }
    }
}
