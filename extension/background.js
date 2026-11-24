const API_BASE = "http://127.0.0.1:47621";

const AUTH_HOSTS = [
  { match: "auth.openai.com", provider: "chatgpt" },
  { match: "accounts.x.ai", provider: "grok" },
  { match: "x.com/i/flow/login", provider: "grok" },
];

async function notifyAuthIfNeeded(url) {
  if (!url) return;
  const hit = AUTH_HOSTS.find((h) => url.includes(h.match));
  if (!hit) return;
  try {
    await fetch(`${API_BASE}/api/ext/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: hit.provider,
        status: "auth_required",
        detail: "Sign in once in the voice window",
        url,
      }),
    });
  } catch (_err) {}
}

chrome.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
  const url = changeInfo.url || (tab && tab.url) || "";
  if (url) notifyAuthIfNeeded(url);
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || !msg.type) return false;

  if (msg.type === "poll") {
    fetch(`${API_BASE}/api/ext/command`, { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => sendResponse({ ok: true, data }))
      .catch((err) => sendResponse({ ok: false, error: String(err) }));
    return true;
  }

  if (msg.type === "heartbeat") {
    fetch(`${API_BASE}/api/ext/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(msg.payload || {}),
    })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => sendResponse({ ok: true, data }))
      .catch((err) => sendResponse({ ok: false, error: String(err) }));
    return true;
  }

  return false;
});
