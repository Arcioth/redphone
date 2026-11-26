/*
 * Red Phone for Android: deck additions on top of the unchanged desktop ui/index.html.
 *
 * Injected by MainActivity right after <head>, so it runs before index.html's own script:
 *  - fetch shim: /api/* requests are answered in-process by the RedPhoneAndroid bridge
 *    (MainActivity -> ApiRouter, the port of daemon.py's HTTP Handler). Nothing listens on a port.
 *  - window.__redphonePush(json): MainActivity pushes every controller state change.
 *  - On DOMContentLoaded the deck gets the SPACEBAR · CRADLE pad, the gear + ANDROID SETTINGS
 *    panel, and a stage that shrinks to fit phone screens. The original DOM is only augmented.
 */
(function () {
  "use strict";

  var bridge = window.RedPhoneAndroid || null;
  var nativeFetch = typeof window.fetch === "function" ? window.fetch.bind(window) : null;

  var ready = false;        // DOM augmented
  var renderWrapped = false; // index.html's global render() goes through update()
  var pendingPush = null;   // snapshot pushed before the DOM was ready
  var lastSnapText = null;  // last good /api/state reply (served while the page is hidden)
  var current = null;       // last rendered snapshot

  // ---------------------------------------------------------------- bridge

  function hasBridge() {
    return !!(bridge && typeof bridge.request === "function");
  }

  /** Synchronous call into MainActivity. Returns {status, text}; never throws. */
  function bridgeCall(method, path, bodyText) {
    var text;
    try {
      text = bridge.request(String(method || "GET").toUpperCase(), String(path), bodyText == null ? "" : String(bodyText));
    } catch (e) {
      return { status: 503, text: '{"error":"bridge_unavailable"}' };
    }
    if (typeof text !== "string" || text === "") return { status: 502, text: '{"error":"empty_reply"}' };
    var status = 200;
    if (text.length < 256 && text.indexOf('"error"') !== -1) {
      try {
        var o = JSON.parse(text);
        if (o && typeof o.error === "string" && typeof o.state !== "string") {
          status = o.error === "not_found" ? 404 : 500;
        }
      } catch (_) {
        status = 500;
      }
    }
    return { status: status, text: text };
  }

  function jsonResponse(res) {
    return new Response(res.text, {
      status: res.status,
      statusText: res.status === 200 ? "OK" : res.status === 404 ? "Not Found" : "Error",
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }

  /** "/api/..." path (+ query) for same-origin API URLs, else null. */
  function apiPath(url) {
    var u;
    try {
      u = new URL(url, location.href);
    } catch (_) {
      return null;
    }
    if (u.origin !== location.origin || u.pathname.indexOf("/api/") !== 0) return null;
    return u.pathname + u.search;
  }

  // ---------------------------------------------------------------- fetch shim

  window.fetch = function (input, init) {
    var isRequest = input && typeof input === "object" && typeof input.url === "string";
    var url = typeof input === "string" ? input : isRequest ? input.url : String(input);
    var path = hasBridge() ? apiPath(url) : null;
    if (path === null) {
      return nativeFetch ? nativeFetch(input, init) : Promise.reject(new TypeError("fetch is unavailable"));
    }
    init = init || {};
    var method = String(init.method || (isRequest ? input.method : "") || "GET").toUpperCase();
    if (init.signal && init.signal.aborted) {
      return Promise.reject(new DOMException("The operation was aborted.", "AbortError"));
    }
    var isState = method === "GET" && path.split("?")[0] === "/api/state";

    function answer(bodyText) {
      var res;
      if (isState && lastSnapText && document.visibilityState === "hidden") {
        // Hidden deck: index.html keeps polling; skip the native round trip (pushes keep this fresh).
        res = { status: 200, text: lastSnapText };
      } else {
        res = bridgeCall(method, path, bodyText);
        if (isState && res.status === 200) lastSnapText = res.text;
        if (ready && !renderWrapped && res.status === 200) {
          // index.html's render() could not be wrapped: keep the Android additions in sync anyway.
          try {
            var st = JSON.parse(res.text);
            if (isSnapshot(st)) update(st);
          } catch (_) {}
        }
      }
      return jsonResponse(res);
    }

    if (init.body != null) {
      // Synchronous on purpose: requests reach the controller in the order the page issued them.
      var b = init.body;
      return Promise.resolve(answer(typeof b === "string" ? b : String(b)));
    }
    if (isRequest && method !== "GET" && method !== "HEAD" && typeof input.text === "function") {
      return input.text().then(answer);
    }
    return Promise.resolve(answer(""));
  };

  /** POST/GET from the Android additions; renders snapshot replies like index.html's post(). */
  function api(method, path, body) {
    if (!hasBridge()) return null;
    var res = bridgeCall(method, path, body == null ? "" : JSON.stringify(body));
    var data = null;
    try {
      data = JSON.parse(res.text);
    } catch (_) {
      return null;
    }
    if (res.status === 200 && isSnapshot(data)) {
      if (method === "GET" && path === "/api/state") lastSnapText = res.text;
      renderSnapshot(data);
    }
    return data;
  }

  function isSnapshot(st) {
    return !!(st && typeof st === "object" && typeof st.state === "string");
  }

  function renderSnapshot(st) {
    if (typeof window.render === "function") window.render(st);
    if (!renderWrapped && ready) update(st);
  }

  // ---------------------------------------------------------------- native -> page hooks

  window.__redphonePush = function (json) {
    var st;
    try {
      st = typeof json === "string" ? JSON.parse(json) : json;
    } catch (_) {
      return;
    }
    if (!isSnapshot(st)) return;
    if (typeof json === "string") lastSnapText = json;
    if (!ready) {
      pendingPush = st;
      return;
    }
    renderSnapshot(st);
  };

  /** Android Back: closes the settings panel first. Returns true when it consumed Back. */
  window.__redphoneBack = function () {
    if (settingsOpen) {
      setSettingsOpen(false);
      return true;
    }
    return false;
  };

  // ---------------------------------------------------------------- DOM helpers

  function $(id) {
    return document.getElementById(id);
  }

  function el(tag, attrs, children) {
    var n = document.createElement(tag);
    if (attrs) {
      for (var k in attrs) {
        if (!Object.prototype.hasOwnProperty.call(attrs, k)) continue;
        if (k === "text") n.textContent = attrs[k];
        else if (k === "html") n.innerHTML = attrs[k];
        else n.setAttribute(k, attrs[k]);
      }
    }
    (children || []).forEach(function (c) {
      if (c) n.appendChild(c);
    });
    return n;
  }

  function chip(text, attrs) {
    var a = attrs || {};
    a["class"] = "chip" + (a["class"] ? " " + a["class"] : "");
    a.type = "button";
    a.text = text;
    return el("button", a);
  }

  function bar(label, kids, extraClass) {
    return el("div", { "class": "util-bar" + (extraClass ? " " + extraClass : "") }, [
      el("span", { "class": "util-label", text: label }),
    ].concat(kids));
  }

  function haptic(ms) {
    try {
      if (navigator.vibrate) navigator.vibrate(ms);
    } catch (_) {}
  }

  // ---------------------------------------------------------------- SPACEBAR · CRADLE pad

  var HINT_ON_HOOK = "HANDSET ON CRADLE — LIFT TO PICK UP";
  var HINT_OFF_HOOK = "REST HANDSET / HOLD TO HANG UP";

  var pad = null;
  var padHint = null;
  var padPointer = null; // pointerId holding the pad, or null
  var padOnHook = false; // last state.on_hook

  function buildCradle(deck) {
    padHint = el("span", { "class": "rp-space-hint", id: "rpSpaceHint", text: HINT_OFF_HOOK });
    pad = el(
      "div",
      {
        "class": "rp-space",
        id: "rpSpace",
        role: "button",
        "aria-pressed": "false",
        "aria-label": "Spacebar cradle. Hold to rest the handset on the cradle, release to lift it.",
      },
      [
        el("span", { "class": "rp-space-label" }, [
          el("span", { "class": "rp-space-led", "aria-hidden": "true" }),
          el("span", { text: "SPACEBAR · CRADLE" }),
          el("span", { "class": "rp-space-led", "aria-hidden": "true" }),
        ]),
        padHint,
      ]
    );
    var section = el("section", { "class": "rp-cradle", id: "rpCradle", "aria-label": "Spacebar cradle" }, [pad]);
    deck.appendChild(section); // after footer.actions

    pad.addEventListener("pointerdown", function (e) {
      if (padPointer !== null) return; // a second finger: the first one is the handset
      if (e.pointerType === "mouse" && e.button !== 0) return;
      e.preventDefault();
      padPointer = e.pointerId;
      try {
        pad.setPointerCapture(e.pointerId);
      } catch (_) {}
      paintPad();
      haptic(10);
      api("POST", "/api/android/cradle", { down: true });
    });

    function release(e) {
      if (padPointer === null) return;
      if (e && e.pointerId !== undefined && e.pointerId !== padPointer) return;
      padPointer = null;
      paintPad();
      haptic(5);
      api("POST", "/api/android/cradle", { down: false });
    }
    pad.addEventListener("pointerup", release);
    pad.addEventListener("pointercancel", release);
    pad.addEventListener("lostpointercapture", release);
    // Deck hidden (activity stopped) while holding = handset lifted. Not window "blur": focus also
    // moves to the voice-browser WebView while a finger may legitimately rest on the pad.
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "hidden") release(null);
    });

    var block = function (e) {
      e.preventDefault();
    };
    pad.addEventListener("contextmenu", block);
    pad.addEventListener("selectstart", block);
    pad.addEventListener("dragstart", block);
    // No long-press selection / haptics / emulated mouse events from the touch itself.
    pad.addEventListener("touchstart", block, { passive: false });
    pad.addEventListener("click", block);
  }

  function paintPad() {
    if (!pad) return;
    var down = padPointer !== null || padOnHook;
    pad.classList.toggle("down", down);
    pad.setAttribute("aria-pressed", down ? "true" : "false");
    padHint.textContent = padOnHook ? HINT_ON_HOOK : HINT_OFF_HOOK;
  }

  // ---------------------------------------------------------------- gear + ANDROID SETTINGS

  var GEAR_SVG =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.43 12.98c.04-.32.07-.65.07-.98s-.03-.66-.07-.98l2.11-1.65a.5.5 0 0 0 .12-.64l-2-3.46a.5.5 0 0 0-.61-.22l-2.49 1a7.3 7.3 0 0 0-1.69-.98l-.38-2.65A.49.49 0 0 0 14 2h-4a.49.49 0 0 0-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1a.5.5 0 0 0-.61.22l-2 3.46a.49.49 0 0 0 .12.64l2.11 1.65c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65a.5.5 0 0 0-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.23.09.49 0 .61-.22l2-3.46a.5.5 0 0 0-.12-.64l-2.11-1.65zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z"/></svg>';

  var PERMS = [
    // [perm key, chip label, target when missing, target when granted]
    ["mic", "MIC", "mic", "app"],
    ["notifications", "NOTIF", "notifications", "notifications"],
    ["full_screen", "FULL-SCREEN", "fsi", "fsi"],
    ["overlay", "OVERLAY", "overlay", "overlay"],
    ["keyboard_cradle", "KEYBOARD CRADLE", "accessibility", "accessibility"],
  ];
  var TOGGLES = [
    ["prox_cradle", "PROX CRADLE"],
    ["any_usb", "ANY USB HEADSET"],
    ["sim_plug", "SIM PLUG"],
  ];

  var gear = null;
  var panel = null;
  var settingsOpen = false;
  var urlInput = null;
  var modelInput = null;
  var saveBtn = null;
  var saveTimer = 0;
  var dirty = { url: false, model: false };
  var phoneAudioEl = null;
  var hardwareEl = null;

  function buildGear(deck) {
    var topbar = deck.querySelector(".topbar");
    if (!topbar) return;
    gear = el("button", {
      "class": "rp-gear",
      id: "rpGear",
      type: "button",
      title: "Android settings",
      "aria-label": "Android settings",
      "aria-expanded": "false",
      "aria-controls": "rpSettings",
      html: GEAR_SVG + '<span class="rp-badge" aria-hidden="true"></span>',
    });
    topbar.appendChild(gear);
    gear.addEventListener("click", function () {
      setSettingsOpen(!settingsOpen);
    });
  }

  function buildSettings(deck) {
    var done = chip("DONE", { id: "rpSettingsDone" });
    done.addEventListener("click", function () {
      setSettingsOpen(false);
    });

    urlInput = el("input", {
      "class": "delay-input rp-text",
      id: "rpOllamaUrl",
      type: "url",
      inputmode: "url",
      autocomplete: "off",
      autocapitalize: "off",
      autocorrect: "off",
      spellcheck: "false",
      enterkeyhint: "done",
      placeholder: "http://127.0.0.1:11434",
      "aria-label": "Ollama URL",
    });
    modelInput = el("input", {
      "class": "delay-input rp-text",
      id: "rpOllamaModel",
      type: "text",
      autocomplete: "off",
      autocapitalize: "off",
      autocorrect: "off",
      spellcheck: "false",
      enterkeyhint: "done",
      placeholder: "auto",
      "aria-label": "Ollama model",
    });
    saveBtn = chip("SAVE", { id: "rpSaveOllama" });

    var toggleChips = TOGGLES.map(function (t) {
      var c = chip(t[1], { "data-rp-toggle": t[0], "aria-pressed": "false" });
      c.addEventListener("click", function () {
        var body = {};
        body[t[0]] = !c.classList.contains("active");
        api("POST", "/api/android/settings", body);
      });
      return c;
    });

    var permChips = PERMS.map(function (p) {
      var c = chip(p[1], { "data-rp-perm": p[0] });
      c.addEventListener("click", function () {
        var granted = c.classList.contains("active");
        api("POST", "/api/android/open", { target: granted ? p[3] : p[2] });
      });
      return c;
    });

    var battery = chip("BATTERY", { id: "rpBattery" });
    battery.addEventListener("click", function () {
      api("POST", "/api/android/open", { target: "battery" });
    });
    var appInfo = chip("APP INFO", { id: "rpAppInfo" });
    appInfo.addEventListener("click", function () {
      api("POST", "/api/android/open", { target: "app" });
    });

    phoneAudioEl = el("span", { "class": "rp-value", id: "rpPhoneAudio", text: "no handset" });
    hardwareEl = el("span", { "class": "rp-value muted", id: "rpHardware", text: "USB: NONE" });

    panel = el("section", { "class": "rp-settings", id: "rpSettings", "aria-label": "Android settings" }, [
      el("div", { "class": "rp-settings-head" }, [
        el("span", { "class": "rp-settings-title", text: "ANDROID SETTINGS" }),
        done,
      ]),
      bar("OLLAMA URL", [el("div", { "class": "chips rp-fill" }, [urlInput])]),
      bar("MODEL", [el("div", { "class": "chips rp-fill" }, [modelInput, saveBtn])]),
      bar("CRADLE / PLUG", [
        el("div", { "class": "chips" }, toggleChips),
        el("span", {
          "class": "rp-note",
          text: "SIM PLUG works with no hardware: the phone's own earpiece + mic become the handset.",
        }),
      ]),
      bar("PERMISSIONS", [el("div", { "class": "chips" }, permChips)]),
      bar("HANDSET", [el("div", { "class": "chips" }, [phoneAudioEl, hardwareEl])]),
      bar("SYSTEM", [el("div", { "class": "chips" }, [battery, appInfo])]),
    ]);
    deck.appendChild(panel);

    saveBtn.addEventListener("click", saveOllama);
    [urlInput, modelInput].forEach(function (inp) {
      inp.addEventListener("input", function () {
        dirty[inp === urlInput ? "url" : "model"] = true;
      });
      inp.addEventListener("keydown", function (e) {
        if (e.key === "Enter") {
          e.preventDefault();
          saveOllama();
        } else if (e.key === "Escape") {
          dirty.url = dirty.model = false;
          inp.blur();
          if (current) updateSettings(current);
        }
      });
      inp.addEventListener("focus", function () {
        panel.classList.add("typing");
      });
      inp.addEventListener("blur", function () {
        panel.classList.remove("typing");
        // The soft keyboard may have scrolled the (overflow:hidden) document; put it back.
        setTimeout(resetScroll, 0);
      });
    });
  }

  function normalizeUrl(raw) {
    var s = String(raw || "").trim();
    if (!s) return { ok: true, value: "" }; // empty = default (Prefs.DEFAULT_OLLAMA_URL)
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) s = "http://" + s;
    try {
      var u = new URL(s);
      if ((u.protocol !== "http:" && u.protocol !== "https:") || !u.hostname) return { ok: false };
    } catch (_) {
      return { ok: false };
    }
    while (s.length > 1 && s.charAt(s.length - 1) === "/") s = s.slice(0, -1);
    return { ok: true, value: s };
  }

  function flashSave(text, cls) {
    clearTimeout(saveTimer);
    saveBtn.textContent = text;
    saveBtn.classList.remove("rp-ok", "rp-err");
    if (cls) saveBtn.classList.add(cls);
    saveTimer = setTimeout(function () {
      saveBtn.textContent = "SAVE";
      saveBtn.classList.remove("rp-ok", "rp-err");
    }, 1400);
  }

  function saveOllama() {
    var url = normalizeUrl(urlInput.value);
    if (!url.ok) {
      flashSave("BAD URL", "rp-err");
      urlInput.focus();
      return;
    }
    var res = api("POST", "/api/android/settings", {
      ollama_url: url.value,
      ollama_model: String(modelInput.value || "").trim(),
    });
    if (isSnapshot(res)) {
      dirty.url = dirty.model = false;
      if (document.activeElement === urlInput || document.activeElement === modelInput) {
        document.activeElement.blur();
      }
      updateSettings(res);
      flashSave("SAVED", "rp-ok");
    } else {
      flashSave("ERROR", "rp-err");
    }
  }

  function setSettingsOpen(open) {
    settingsOpen = !!open;
    if (!panel) return;
    if (!settingsOpen && panel.contains(document.activeElement)) document.activeElement.blur();
    panel.classList.toggle("open", settingsOpen);
    if (!settingsOpen) panel.classList.remove("typing");
    gear.classList.toggle("active", settingsOpen);
    gear.setAttribute("aria-expanded", settingsOpen ? "true" : "false");
    if (settingsOpen) {
      panel.scrollTop = 0;
      if (current) updateSettings(current);
    }
    try {
      if (bridge && typeof bridge.settingsPanel === "function") bridge.settingsPanel(settingsOpen);
    } catch (_) {}
  }

  function updateSettings(st) {
    var a = st && st.android;
    if (!a || !panel) return;
    var perms = a.perms || {};

    if (document.activeElement !== urlInput && !dirty.url) urlInput.value = a.ollama_url || "";
    if (document.activeElement !== modelInput && !dirty.model) modelInput.value = a.ollama_model || "";

    panel.querySelectorAll(".chip[data-rp-toggle]").forEach(function (c) {
      var on = Boolean(a[c.getAttribute("data-rp-toggle")]);
      c.classList.toggle("active", on);
      c.setAttribute("aria-pressed", on ? "true" : "false");
    });
    panel.querySelectorAll(".chip[data-rp-perm]").forEach(function (c) {
      var granted = Boolean(perms[c.getAttribute("data-rp-perm")]);
      c.classList.toggle("active", granted);
      c.setAttribute("aria-label", c.textContent + (granted ? ": granted" : ": missing, tap to grant"));
    });

    phoneAudioEl.textContent = a.phone_audio ? String(a.phone_audio) : "no handset";
    phoneAudioEl.className = "rp-value" + (a.phone_audio ? (a.hardware_present ? " good" : "") : " muted");
    if (a.hardware_present) {
      hardwareEl.textContent = "USB: DETECTED";
      hardwareEl.className = "rp-value good";
    } else if (a.sim_plug) {
      hardwareEl.textContent = "USB: NONE · SIM PLUG";
      hardwareEl.className = "rp-value warn";
    } else {
      hardwareEl.textContent = "USB: NONE";
      hardwareEl.className = "rp-value muted";
    }

    // Amber dot on the gear while a permission the calls need is missing.
    gear.classList.toggle("needs-attention", perms.mic === false || perms.notifications === false);
  }

  // ---------------------------------------------------------------- stage fit (stage shrinks first)

  var RIG_MAX = 164;     // desktop is 148; tall phones get a little more medallion
  var RIG_MIN_COL = 92;  // below this, put the medallion beside the status text
  var RIG_MAX_ROW = 124;
  var deckEl = null;
  var stageEl = null;
  var statusEl = null;
  var fitKey = "";

  function textKey() {
    var ids = ["stateTitle", "stateDetail", "timer", "hookPill"];
    var k = "";
    for (var i = 0; i < ids.length; i++) {
      var n = $(ids[i]);
      k += (n ? n.textContent.length : 0) + ",";
    }
    return k;
  }

  function setRig(px) {
    deckEl.style.setProperty("--rp-rig", Math.max(40, Math.floor(px)) + "px");
  }

  function fitStage() {
    if (!stageEl || !statusEl) return;
    var H = stageEl.clientHeight;
    var W = stageEl.clientWidth;
    if (H <= 0 || W <= 0) return;
    var key = W + "x" + H + "|" + textKey();
    if (key === fitKey) return;
    fitKey = key;

    deckEl.classList.remove("rp-stage-row", "rp-stage-tight");
    var gap = 8;
    var colRig = Math.min(RIG_MAX, H - statusEl.offsetHeight - gap, W);
    if (colRig >= RIG_MIN_COL) {
      setRig(colRig);
      return;
    }
    deckEl.classList.add("rp-stage-row");
    setRig(Math.min(H, W * 0.36, RIG_MAX_ROW));
    if (statusEl.offsetHeight > H) {
      deckEl.classList.add("rp-stage-tight");
      if (statusEl.offsetHeight > H) setRig(Math.min(H, W * 0.3, RIG_MAX_ROW));
    }
  }

  function resetScroll() {
    if (document.scrollingElement && document.scrollingElement.scrollTop) document.scrollingElement.scrollTop = 0;
    if (window.scrollY || window.scrollX) window.scrollTo(0, 0);
  }

  // ---------------------------------------------------------------- render hook

  function update(st) {
    current = st;
    padOnHook = Boolean(st.on_hook);
    paintPad();
    updateSettings(st);
    fitStage();
  }

  function wrapRender() {
    var orig = window.render;
    if (typeof orig !== "function") return false;
    if (orig.__rpWrapped) return (renderWrapped = true);
    var wrapped = function (st) {
      // index.html renders whatever /api/* returned; never feed it an error reply.
      if (!isSnapshot(st)) return undefined;
      var r = orig.apply(this, arguments);
      try {
        update(st);
      } catch (e) {
        if (window.console) console.error("redphone android update failed", e);
      }
      return r;
    };
    wrapped.__rpWrapped = true;
    // index.html declares `function render` at top level: a writable global binding, so every
    // render(...) call in its script (post(), pollState()) now goes through this wrapper.
    window.render = wrapped;
    renderWrapped = window.render === wrapped;
    return renderWrapped;
  }

  function init() {
    deckEl = $("deck");
    if (!deckEl) return;
    stageEl = deckEl.querySelector(".stage");
    statusEl = deckEl.querySelector(".status-block");
    document.documentElement.classList.add("rp-android");

    buildGear(deckEl);
    buildCradle(deckEl);
    buildSettings(deckEl);
    wrapRender();

    if (typeof ResizeObserver === "function" && stageEl) {
      new ResizeObserver(function () {
        fitStage();
      }).observe(stageEl);
    }
    window.addEventListener("resize", function () {
      fitStage();
      resetScroll();
    });
    window.addEventListener("scroll", resetScroll);

    ready = true;
    if (pendingPush) {
      var p = pendingPush;
      pendingPush = null;
      renderSnapshot(p);
    } else {
      // index.html's first poll may have rendered before render() was wrapped.
      api("GET", "/api/state");
    }
    fitStage();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
