(() => {
  const host = location.hostname;
  const PROVIDER = host.includes("chatgpt.com")
    ? "chatgpt"
    : host.includes("grok.com")
    ? "grok"
    : null;

  if (!PROVIDER) return;

  let micActive = false;
  let lastClickAt = 0;
  let lastSessionId = null;
  let clickedStartForSession = null;
  let clickedStopForSession = null;
  let lastReportedStatus = "";
  let cleanUiEnabled = true;

  window.addEventListener("redphone-mic-state", (e) => {
    micActive = Boolean(e.detail && e.detail.active);
  });

  // Inject matching Red Phone dark crimson theme + minimal voice-orb isolation CSS
  const styleEl = document.createElement("style");
  styleEl.id = "redphone-skin-style";
  styleEl.textContent = `
    html.redphone-themed,
    html.redphone-themed body {
      background:
        radial-gradient(circle at 50% 34%, rgba(255, 42, 66, 0.28), transparent 58%),
        radial-gradient(circle at 82% 90%, rgba(160, 14, 34, 0.22), transparent 54%),
        #0b0709 !important;
      color: #f8eaec !important;
      overflow: hidden !important;
    }

    html.redphone-themed::after {
      content: "";
      position: fixed;
      inset: 0;
      pointer-events: none;
      z-index: 2147483640;
      background: repeating-linear-gradient(
        180deg,
        rgba(255, 255, 255, 0.016) 0px,
        rgba(255, 255, 255, 0.016) 1px,
        transparent 1px,
        transparent 3px
      );
      border: 1px solid rgba(255, 76, 98, 0.28);
    }

    /* When voice call is live + clean mode active: hide everything except the voice orb chain */
    html.redphone-voice-live.redphone-clean-ui body * {
      visibility: hidden !important;
    }

    html.redphone-voice-live.redphone-clean-ui body *::before,
    html.redphone-voice-live.redphone-clean-ui body *::after {
      display: none !important;
      background: none !important;
    }

    html.redphone-voice-live.redphone-clean-ui [data-redphone-keep="1"],
    html.redphone-voice-live.redphone-clean-ui [data-redphone-keep="1"] * {
      visibility: visible !important;
    }

    html.redphone-voice-live.redphone-clean-ui [data-redphone-ancestor="1"] {
      visibility: visible !important;
      transform: none !important;
      filter: none !important;
      perspective: none !important;
      contain: none !important;
      will-change: auto !important;
      background: transparent !important;
      background-image: none !important;
      background-color: transparent !important;
      border-color: transparent !important;
      box-shadow: none !important;
    }

    /* Strip chrome inside voice ancestors while preserving the reactive bubble */
    html.redphone-voice-live.redphone-clean-ui nav,
    html.redphone-voice-live.redphone-clean-ui header,
    html.redphone-voice-live.redphone-clean-ui aside,
    html.redphone-voice-live.redphone-clean-ui footer,
    html.redphone-voice-live.redphone-clean-ui [role="navigation"],
    html.redphone-voice-live.redphone-clean-ui [role="banner"] {
      visibility: hidden !important;
      opacity: 0 !important;
      pointer-events: none !important;
    }

    html.redphone-voice-live.redphone-clean-ui [data-redphone-orb="1"] {
      position: fixed !important;
      top: 50% !important;
      left: 50% !important;
      transform: translate(-50%, -50%) !important;
      width: 170px !important;
      height: 170px !important;
      z-index: 2147483630 !important;
      filter: drop-shadow(0 0 36px rgba(255, 42, 66, 0.55)) !important;
    }

    #redphone-bridge-badge,
    #redphone-bridge-badge * {
      visibility: visible !important;
    }
  `;
  document.documentElement.appendChild(styleEl);

  // Subtle interactive badge (click to toggle CLEAN / FULL page view)
  const badge = document.createElement("button");
  badge.id = "redphone-bridge-badge";
  badge.type = "button";
  badge.title = "Click to toggle clean orb-only view vs full browser UI";
  Object.assign(badge.style, {
    position: "fixed",
    right: "12px",
    bottom: "12px",
    zIndex: "2147483647",
    padding: "5px 10px",
    borderRadius: "999px",
    background: "rgba(18, 8, 10, 0.88)",
    border: "1px solid rgba(255, 62, 77, 0.7)",
    color: "#ffd7da",
    fontFamily: '"JetBrains Mono", monospace',
    fontSize: "10.5px",
    fontWeight: "700",
    letterSpacing: "0.06em",
    cursor: "pointer",
    boxShadow: "0 6px 20px rgba(0,0,0,0.45)",
    transition: "all 0.2s ease",
  });
  badge.textContent = "☎ RED PHONE · STANDBY";
  badge.addEventListener("click", () => {
    cleanUiEnabled = !cleanUiEnabled;
    document.documentElement.classList.toggle("redphone-clean-ui", cleanUiEnabled);
  });
  document.documentElement.appendChild(badge);

  function setBadge(text, accent) {
    badge.textContent = `${text}${cleanUiEnabled ? "" : " · FULL"}`;
    if (accent === "live") {
      badge.style.borderColor = "#2ee59d";
      badge.style.color = "#c8ffe9";
    } else if (accent === "warn") {
      badge.style.borderColor = "#ffbe3b";
      badge.style.color = "#fff1c7";
    } else {
      badge.style.borderColor = "rgba(255, 62, 77, 0.7)";
      badge.style.color = "#ffd7da";
    }
  }

  function clearOrbMarkers() {
    document
      .querySelectorAll("[data-redphone-keep], [data-redphone-ancestor], [data-redphone-orb]")
      .forEach((el) => {
        el.removeAttribute("data-redphone-keep");
        el.removeAttribute("data-redphone-ancestor");
        el.removeAttribute("data-redphone-orb");
      });
  }

  function findVoiceOrbElement() {
    // Prefer largest visible canvas (ChatGPT & Grok both render their reactive voice orb on a canvas/WebGL sphere)
    let best = null;
    let bestArea = 0;

    for (const c of document.querySelectorAll("canvas")) {
      const r = c.getBoundingClientRect();
      const area = r.width * r.height;
      if (r.width >= 32 && r.height >= 32 && area > bestArea) {
        best = c;
        bestArea = area;
      }
    }
    if (best) return best;

    // Fallback: large circular SVG or animated voice visualizer container
    for (const el of document.querySelectorAll('svg, [class*="voice" i], [class*="orb" i], [class*="wave" i], [data-testid*="voice" i]')) {
      if (el.id === "redphone-bridge-badge") continue;
      if (el.tagName === "BUTTON") continue;
      const r = el.getBoundingClientRect();
      const area = r.width * r.height;
      if (r.width >= 70 && r.height >= 70 && r.width <= window.innerWidth * 0.92 && area > bestArea) {
        best = el;
        bestArea = area;
      }
    }
    return best;
  }

  let lastOrbSeenAt = 0;

  function applyVoiceSkin(isLive, isAuth) {
    const html = document.documentElement;
    if (isAuth) {
      html.classList.remove("redphone-themed", "redphone-voice-live", "redphone-clean-ui");
      clearOrbMarkers();
      return;
    }

    html.classList.add("redphone-themed");
    html.classList.toggle("redphone-clean-ui", cleanUiEnabled);

    if (!isLive) {
      lastOrbSeenAt = 0;
      html.classList.remove("redphone-voice-live");
      clearOrbMarkers();
      return;
    }

    const orb = findVoiceOrbElement();
    if (!orb) {
      // Keep clean overlay active for 1.5s grace period so React canvas swaps don't flash white
      if (Date.now() - lastOrbSeenAt > 1500) {
        html.classList.remove("redphone-voice-live");
      }
      return;
    }

    lastOrbSeenAt = Date.now();
    if (orb.getAttribute("data-redphone-orb") !== "1") {
      clearOrbMarkers();
      orb.setAttribute("data-redphone-orb", "1");
      orb.setAttribute("data-redphone-keep", "1");
      let p = orb.parentElement;
      while (p && p !== document.documentElement) {
        p.setAttribute("data-redphone-ancestor", "1");
        p = p.parentElement;
      }
    }
    html.classList.add("redphone-voice-live");
  }

  function isVisible(el) {
    if (!el || el.disabled) return false;
    const style = window.getComputedStyle(el);
    if (style.display === "none" || parseFloat(style.opacity) === 0) {
      return false;
    }
    const r = el.getBoundingClientRect();
    return r.width > 4 && r.height > 4;
  }

  function allButtons() {
    return Array.from(document.querySelectorAll('button, [role="button"]')).filter(
      (b) => b.id !== "redphone-bridge-badge" && isVisible(b)
    );
  }

  function btnMeta(el) {
    return [
      el.getAttribute("aria-label") || "",
      el.getAttribute("title") || "",
      el.getAttribute("data-testid") || "",
      el.innerText || el.textContent || "",
    ]
      .join(" ")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }

  function findByPatterns(patterns, exclude = []) {
    const btns = allButtons();
    for (const pat of patterns) {
      for (const b of btns) {
        const meta = btnMeta(b);
        if (exclude.some((ex) => ex.test(meta))) continue;
        if (pat.test(meta)) return b;
      }
    }
    return null;
  }

  function detectAuthRequired() {
    const url = location.href.toLowerCase();
    if (
      url.includes("auth.openai.com") ||
      url.includes("accounts.x.ai") ||
      url.includes("/login") ||
      url.includes("/signin")
    ) {
      return true;
    }

    const bodyText = (document.body?.innerText || "").slice(0, 1500).toLowerCase();
    const hasLoginBtn = Boolean(
      findByPatterns([/\bsign in\b/, /\blog in\b/, /\bcontinue with google\b/])
    );
    if (hasLoginBtn && !findStartButton() && !detectLiveActive()) {
      return bodyText.includes("log in") || bodyText.includes("sign in") || bodyText.includes("sign up");
    }
    return false;
  }

  function findStartButton() {
    if (PROVIDER === "chatgpt") {
      const direct = document.querySelector('button[data-testid="composer-speech-button"]');
      if (direct && isVisible(direct)) return direct;
      return findByPatterns(
        [
          /composer-speech-button/,
          /\bstart voice\b/,
          /\buse voice\b/,
          /\bvoice mode\b/,
          /\bvoice\b/,
        ],
        [/\bstop\b/, /\bend\b/, /\bclose\b/, /\bdictat/, /\bsettings\b/]
      );
    }

    if (PROVIDER === "grok") {
      return findByPatterns(
        [
          /\bstart voice\b/,
          /\bvoice mode\b/,
          /\benter voice\b/,
          /\bspeak\b/,
          /\bvoice\b/,
        ],
        [/\bstop\b/, /\bend\b/, /\bclose\b/, /\bmute\b/, /\bsettings\b/, /\bselect voice\b/]
      );
    }
    return null;
  }

  function findStopButton() {
    if (PROVIDER === "chatgpt") {
      return findByPatterns([
        /close-voice/,
        /\bend voice\b/,
        /\bclose voice\b/,
        /\bhang up\b/,
        /\bend call\b/,
        /\bexit voice\b/,
      ]);
    }
    if (PROVIDER === "grok") {
      return findByPatterns([
        /\bend voice\b/,
        /\bstop voice\b/,
        /\bhang up\b/,
        /\bend call\b/,
        /\bclose voice\b/,
        /\bdisconnect\b/,
      ]);
    }
    return null;
  }

  function detectLiveActive() {
    if (micActive) return true;
    if (findStopButton()) return true;
    return false;
  }

  function sendMsg(msg) {
    return new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage(msg, (resp) => {
          if (chrome.runtime.lastError) resolve({ ok: false });
          else resolve(resp || { ok: false });
        });
      } catch (_err) {
        resolve({ ok: false });
      }
    });
  }

  async function report(status, detail, sessionId) {
    if (status === lastReportedStatus && status !== "connected") return;
    lastReportedStatus = status;
    await sendMsg({
      type: "heartbeat",
      payload: {
        provider: PROVIDER,
        status,
        detail,
        session_id: sessionId || lastSessionId || "",
        url: location.href,
        mic_active: micActive,
      },
    });
  }

  async function tick() {
    const res = await sendMsg({ type: "poll" });
    if (!res || !res.ok || !res.data) {
      setBadge("☎ RED PHONE · OFFLINE", "idle");
      return;
    }

    const cmd = res.data;
    if (cmd.provider !== PROVIDER) {
      setBadge("☎ RED PHONE · STANDBY", "idle");
      applyVoiceSkin(false, false);
      return;
    }

    const sid = cmd.session_id || "";
    if (sid !== lastSessionId) {
      lastSessionId = sid;
      clickedStartForSession = null;
      clickedStopForSession = null;
      lastReportedStatus = "";
    }

    const live = detectLiveActive();
    const authNeeded = !live && detectAuthRequired();
    applyVoiceSkin(live, authNeeded);

    if (!cmd.want_active) {
      if (live && clickedStopForSession !== sid) {
        const stopBtn = findStopButton();
        if (stopBtn) {
          clickedStopForSession = sid;
          stopBtn.click();
          setBadge("☎ RED PHONE · HANGING UP", "idle");
          await report("ended", "Clicked stop button", sid);
          return;
        }
      }
      setBadge("☎ RED PHONE · STANDBY", "idle");
      return;
    }

    if (live) {
      setBadge("☎ LIVE", "live");
      await report("connected", micActive ? "Microphone stream live" : "Voice session active", sid);
      return;
    }

    if (authNeeded) {
      setBadge("☎ SIGN IN", "warn");
      await report("auth_required", "Please sign in once in this voice window", sid);
      return;
    }

    const startBtn = findStartButton();
    if (startBtn) {
      const now = Date.now();
      if (clickedStartForSession !== sid || now - lastClickAt > 5000) {
        clickedStartForSession = sid;
        lastClickAt = now;
        startBtn.click();
        setBadge("☎ PICKING UP...", "warn");
        await report("connecting", `Clicked: ${btnMeta(startBtn).slice(0, 48)}`, sid);
      } else {
        setBadge("☎ CONNECTING...", "warn");
      }
    } else {
      setBadge("☎ LOADING...", "warn");
      await report("loading", "Waiting for voice controls on page", sid);
    }
  }

  setInterval(tick, 140);
  setTimeout(tick, 100);
})();
