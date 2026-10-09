const CONFIG_KEY = "pureTvVodSyncConfig";

function isPrivateIpv4(host) {
  const parts = host.split(".").map(Number);
  if (parts.length !== 4 || parts.some((value) => !Number.isInteger(value) || value < 0 || value > 255)) return false;
  return parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 169 && parts[1] === 254);
}

function parseEndpoint(value) {
  const url = new URL(value);
  if (url.protocol !== "http:" || !isPrivateIpv4(url.hostname) || url.pathname !== "/") {
    throw new Error("Use the private IPv4 address shown in PureTV Settings.");
  }
  return url.origin;
}

async function getConfig() {
  const stored = await chrome.storage.local.get(CONFIG_KEY);
  const config = stored[CONFIG_KEY];
  if (!config?.endpoint || !config?.pairingCode) return null;
  return config;
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === "getTvVodPosition") {
    getConfig().then(async (config) => {
      if (!config) {
        sendResponse({ ok: false, reason: "not-paired" });
        return;
      }
      const endpoint = parseEndpoint(config.endpoint);
      const response = await fetch(`${endpoint}/v1/vod-position?vodId=${encodeURIComponent(message.vodId)}`, {
        method: "GET",
        headers: { "Authorization": `Bearer ${config.pairingCode}` },
        credentials: "omit",
        cache: "no-store"
      });
      if (!response.ok) throw new Error(`TV returned HTTP ${response.status}`);
      const body = await response.json();
      sendResponse({ ok: true, positionMs: Number(body.positionMs) || 0 });
    }).catch((error) => sendResponse({ ok: false, reason: error.message }));
    return true;
  }
  if (message?.type !== "syncVodPosition") return false;
  getConfig().then(async (config) => {
    if (!config) {
      sendResponse({ ok: false, reason: "not-paired" });
      return;
    }
    const endpoint = parseEndpoint(config.endpoint);
    const response = await fetch(`${endpoint}/v1/vod-position`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${config.pairingCode}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ vodId: message.vodId, positionMs: message.positionMs }),
      credentials: "omit",
      cache: "no-store"
    });
    if (!response.ok) throw new Error(`TV returned HTTP ${response.status}`);
    await chrome.storage.local.set({
      pureTvVodSyncLastUpdate: {
        at: Date.now(),
        vodId: message.vodId,
        positionMs: message.positionMs
      }
    });
    sendResponse({ ok: true });
  }).catch((error) => sendResponse({ ok: false, reason: error.message }));
  return true;
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type !== "getSyncStatus") return false;
  Promise.all([
    chrome.storage.local.get([CONFIG_KEY, "pureTvVodSyncLastUpdate"]),
    chrome.permissions.getAll()
  ]).then(([stored, permissions]) => sendResponse({
    paired: Boolean(stored[CONFIG_KEY]),
    lastUpdate: stored.pureTvVodSyncLastUpdate ?? null,
    optionalOrigins: permissions.origins ?? []
  }));
  return true;
});
