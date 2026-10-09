const CONFIG_KEY = "pureTvVodSyncConfig";
const ENDPOINT_KEY = "pureTvVodSyncEndpoint";
const endpointInput = document.querySelector("#endpoint");
const codeInput = document.querySelector("#code");
const webLinkCodeInput = document.querySelector("#webLinkCode");
const statusBox = document.querySelector("#status");
const WEB_SESSION_KDF_ITERATIONS = 310_000;

function isPrivateIpv4(host) {
  const parts = host.split(".").map(Number);
  if (parts.length !== 4 || parts.some((value) => !Number.isInteger(value) || value < 0 || value > 255)) return false;
  return parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 169 && parts[1] === 254);
}

// Firefox match patterns do not support explicit ports. Permission grants are
// scoped to the TV host there; the actual HTTP requests still use the TV port.
function hostPermissionPattern(endpoint) {
  const url = new URL(endpoint);
  return `${url.protocol}//${url.hostname}/*`;
}

function setStatus(message) {
  statusBox.textContent = message;
}

async function loadConfig() {
  const stored = await chrome.storage.local.get([CONFIG_KEY, ENDPOINT_KEY, "pureTvVodSyncLastUpdate"]);
  const config = stored[CONFIG_KEY];
  if (config || stored[ENDPOINT_KEY]) {
    endpointInput.value = config?.endpoint || stored[ENDPOINT_KEY];
  }
  if (config) {
    codeInput.value = config.pairingCode;
  }
  const last = stored.pureTvVodSyncLastUpdate;
  if (last) {
    setStatus(`Last sent VOD ${last.vodId} at ${new Date(last.at).toLocaleTimeString()}.`);
  } else if (config) {
    setStatus("Paired. Twitch homepage history syncs automatically; an open VOD syncs about every 8 seconds.");
  }
}

document.querySelector("#connect").addEventListener("click", async () => {
  try {
    const endpoint = new URL(endpointInput.value.trim()).origin;
    if (!isPrivateIpv4(new URL(endpoint).hostname)) throw new Error("Use the TV's private IPv4 address from PureTV Settings.");
    if (!new URL(endpointInput.value.trim()).port) throw new Error("Include the port shown by PureTV (47821).");
    const pairingCode = codeInput.value.trim().toUpperCase();
    if (!/^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8,12}$/.test(pairingCode)) {
      throw new Error("Enter the 8-character pairing code shown in PureTV Settings.");
    }

    const granted = await chrome.permissions.request({ origins: [hostPermissionPattern(endpoint)] });
    if (!granted) throw new Error("Network access permission was not granted.");

    setStatus("Checking the TV…");
    const response = await fetchTv(endpoint, "/v1/health", { cache: "no-store", credentials: "omit" });
    if (!response.ok) throw new Error(`TV receiver returned HTTP ${response.status}. Enable browser sync in TV Settings.`);
    await chrome.storage.local.set({ [CONFIG_KEY]: { endpoint, pairingCode }, [ENDPOINT_KEY]: endpoint });
    setStatus("Paired. Twitch homepage history syncs automatically; an open VOD syncs about every 8 seconds.");
  } catch (error) {
    setStatus(error.message || "Could not pair with the TV.");
  }
});

document.querySelector("#linkSession").addEventListener("click", async () => {
  try {
    const endpoint = new URL(endpointInput.value.trim()).origin;
    if (!isPrivateIpv4(new URL(endpoint).hostname)) throw new Error("Use the TV's private IPv4 address from PureTV Settings.");
    if (!new URL(endpointInput.value.trim()).port) throw new Error("Include the port shown by PureTV (47821).");
    const linkCode = webLinkCodeInput.value.trim().toUpperCase();
    if (!/^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{10}$/.test(linkCode)) {
      throw new Error("Enter the 10-character one-time code shown in PureTV Settings.");
    }

    // Keep the TV address even when the user only wants direct TV resume and
    // does not configure ongoing desktop-to-TV position sync.
    await chrome.storage.local.set({ [ENDPOINT_KEY]: endpoint });

    const granted = await chrome.permissions.request({
      permissions: ["cookies"],
      origins: [hostPermissionPattern(endpoint)]
    });
    if (!granted) throw new Error("Cookie or TV network access permission was not granted.");

    setStatus("Checking your Twitch browser session…");
    const cookie = await chrome.cookies.get({ url: "https://www.twitch.tv/", name: "auth-token" });
    if (!cookie?.value) throw new Error("No Twitch website session found. Sign in to Twitch in this browser first.");

    setStatus("Starting an encrypted sign-in handoff…");
    const challengeResponse = await fetchTv(endpoint, "/v1/web-session/challenge", {
      method: "GET",
      credentials: "omit",
      cache: "no-store"
    });
    if (!challengeResponse.ok) throw new Error(`TV receiver returned HTTP ${challengeResponse.status}. Enable browser sync in TV Settings.`);
    const challenge = await challengeResponse.json();
    if (!challenge?.challengeId || !challenge?.salt) throw new Error("TV returned an invalid sign-in challenge.");

    const encoder = new TextEncoder();
    const baseKey = await crypto.subtle.importKey("raw", encoder.encode(linkCode), "PBKDF2", false, ["deriveKey"]);
    const key = await crypto.subtle.deriveKey(
      {
        name: "PBKDF2",
        salt: base64ToBytes(challenge.salt),
        iterations: WEB_SESSION_KDF_ITERATIONS,
        hash: "SHA-256"
      },
      baseKey,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt"]
    );
    const iv = crypto.getRandomValues(new Uint8Array(12));
    let sessionToken = cookie.value;
    const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, encoder.encode(sessionToken));
    sessionToken = "";
    cookie.value = "";
    const response = await fetchTv(endpoint, "/v1/web-session", {
      method: "POST",
      // text/plain avoids a CORS preflight; the receiver parses the JSON body
      // as text, so the payload format remains unchanged.
      headers: { "Content-Type": "text/plain;charset=UTF-8" },
      body: JSON.stringify({
        challengeId: challenge.challengeId,
        iv: bytesToBase64(iv),
        ciphertext: bytesToBase64(new Uint8Array(ciphertext))
      }),
      credentials: "omit",
      cache: "no-store"
    });
    if (!response.ok) throw new Error(`TV could not link the Twitch session (HTTP ${response.status}). Refresh the one-time code and try again.`);
    webLinkCodeInput.value = "";
    setStatus("Linked. PureTV now has its own encrypted Twitch website session and can sync VOD resume while this computer is off.");
  } catch (error) {
    setStatus(error.message || "Could not link the Twitch website session to the TV.");
  }
});

function base64ToBytes(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function bytesToBase64(bytes) {
  let binary = "";
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary);
}

document.querySelector("#disconnect").addEventListener("click", async () => {
  const stored = await chrome.storage.local.get(CONFIG_KEY);
  const config = stored[CONFIG_KEY];
  const endpoint = config?.endpoint || endpointInput.value.trim();
  await chrome.storage.local.remove([CONFIG_KEY, ENDPOINT_KEY, "pureTvVodSyncLastUpdate"]);
  if (endpoint) {
    const origin = hostPermissionPattern(endpoint);
    await chrome.permissions.remove({ origins: [origin] });
  }
  endpointInput.value = "";
  codeInput.value = "";
  setStatus("Disconnected.");
});

loadConfig();

async function fetchTv(endpoint, path, options) {
  try {
    return await fetch(`${endpoint}${path}`, options);
  } catch (error) {
    throw new Error(
      `Firefox could not reach ${endpoint}${path}. Confirm the TV address is reachable in Firefox and grant the extension access to that TV host. (${error.message || "network request failed"})`
    );
  }
}
