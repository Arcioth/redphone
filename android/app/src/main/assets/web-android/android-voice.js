/*
 * Red Phone for Android: WebView-only tweak that content.js does not need on the desktop.
 *
 * Page visibility keep-alive. The desktop voice window runs with --disable-renderer-backgrounding,
 * --disable-backgrounding-occluded-windows and IntensiveWakeUpThrottling off, so ChatGPT / Grok never
 * see the voice tab as hidden while it sits on the special workspace (HIDE WHOLE BROWSER) or behind
 * the deck. The app keeps the WebView "visible" to Chromium while it is hidden; this also keeps the
 * page from reacting to any brief hidden state (screen off, app switch), so a live voice session is
 * never paused by the page itself. Injected at document start together with page-hook.js, on
 * https://chatgpt.com and https://grok.com only.
 */
(function () {
  "use strict";
  if (window.__redphoneAndroidVoice) return;
  try {
    Object.defineProperty(window, "__redphoneAndroidVoice", { value: true });
  } catch (_err) {
    return;
  }

  function visible() {
    return "visible";
  }

  function notHidden() {
    return false;
  }

  try {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: visible });
    Object.defineProperty(document, "hidden", { configurable: true, get: notHidden });
    Object.defineProperty(document, "webkitVisibilityState", { configurable: true, get: visible });
    Object.defineProperty(document, "webkitHidden", { configurable: true, get: notHidden });
  } catch (_err) {}

  // visibilitychange is dispatched at the document; a capturing listener on window runs first.
  function swallow(e) {
    e.stopImmediatePropagation();
  }
  window.addEventListener("visibilitychange", swallow, true);
  window.addEventListener("webkitvisibilitychange", swallow, true);
})();
