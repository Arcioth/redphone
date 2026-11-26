/*
 * Red Phone for Android: the extension service worker (extension/background.js) for
 * extension/content.js running inside the app's voice WebView.
 *
 * content.js talks to its service worker with chrome.runtime.sendMessage({type: "poll" | "heartbeat"}, cb).
 * Here those messages go to the app's @JavascriptInterface object window.RedPhoneVoiceBridge instead of
 * http://127.0.0.1:47621 (nothing listens on a port on Android):
 *   {type: "poll"}               -> cb({ok: true, data: <controller ext command>})
 *   {type: "heartbeat", payload} -> cb({ok: true, data: {ok: true}})
 * Callbacks run asynchronously like the real API; any bridge failure answers {ok: false}.
 *
 * The runtime is published as window.__redphoneChrome (read-only, non-enumerable) and handed to
 * content.js as its lexical `chrome`; window.chrome is left untouched for the page.
 */
(function () {
  "use strict";
  if (window.__redphoneChrome) return;

  var capturedBridge = window.RedPhoneVoiceBridge;

  function bridge() {
    return capturedBridge || window.RedPhoneVoiceBridge || null;
  }

  function handle(msg) {
    var b = bridge();
    if (!b || !msg || typeof msg !== "object") return { ok: false };
    if (msg.type === "poll") {
      var raw = b.poll();
      var data = JSON.parse(typeof raw === "string" && raw ? raw : "{}");
      return { ok: true, data: data };
    }
    if (msg.type === "heartbeat") {
      b.heartbeat(JSON.stringify(msg.payload || {}));
      return { ok: true, data: { ok: true } };
    }
    return { ok: false };
  }

  function later(cb, resp) {
    setTimeout(function () {
      cb(resp);
    }, 0);
  }

  var runtime = {
    id: undefined,
    lastError: undefined,
    // chrome.runtime.sendMessage([extensionId], message, [options], [callback])
    sendMessage: function () {
      var args = Array.prototype.slice.call(arguments);
      var cb = args.length && typeof args[args.length - 1] === "function" ? args.pop() : null;
      var msg = args.length >= 2 && typeof args[0] === "string" ? args[1] : args[0];
      var resp;
      try {
        resp = handle(msg);
      } catch (_err) {
        resp = { ok: false };
      }
      if (cb) {
        later(cb, resp);
        return undefined;
      }
      return Promise.resolve(resp);
    },
  };

  var api = { runtime: Object.freeze(runtime) };
  Object.defineProperty(window, "__redphoneChrome", {
    value: Object.freeze(api),
    configurable: false,
    enumerable: false,
    writable: false,
  });
})();
