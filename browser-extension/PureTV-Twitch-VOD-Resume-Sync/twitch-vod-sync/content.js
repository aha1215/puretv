(() => {
  const intervalMs = 8_000;
  const lastSentByVod = new Map();
  const tvResumeChecked = new Set();
  const tvResumeInFlight = new Set();

  function vodIdFromPath() {
    const match = location.pathname.match(/(?:^|\/)videos\/(\d+)(?:\/|$)/);
    return match?.[1] ?? null;
  }

  function sendPosition(vodId, positionMs, force = false) {
    if (!vodId || !Number.isFinite(positionMs) || positionMs < 0) return;
    positionMs = Math.floor(positionMs);
    const previous = lastSentByVod.get(vodId) ?? -1;
    if (!force && positionMs - previous < 7_000) return;
    lastSentByVod.set(vodId, positionMs);
    chrome.runtime.sendMessage({ type: "syncVodPosition", vodId, positionMs }).catch(() => {});
  }

  function sendCurrentPosition(video, force = false) {
    const seconds = video.currentTime;
    sendPosition(vodIdFromPath(), seconds * 1_000, force);
  }

  function durationSecondsFromText(value) {
    const parts = value.trim().split(":").map(Number);
    if ((parts.length !== 2 && parts.length !== 3) || parts.some((part) => !Number.isInteger(part))) return null;
    if (parts.slice(1).some((part) => part < 0 || part > 59) || parts[0] < 0) return null;
    return parts.length === 3
      ? parts[0] * 3_600 + parts[1] * 60 + parts[2]
      : parts[0] * 60 + parts[1];
  }

  function syncHomepageContinueWatching() {
    for (const bar of document.querySelectorAll('[role="progressbar"][aria-valuenow]')) {
      let card = bar;
      let vodId = null;
      let durationSeconds = null;
      for (let depth = 0; card && depth < 10; depth++, card = card.parentElement) {
        const videoLink = Array.from(card.querySelectorAll("a[href]")).find((anchor) => {
          const match = anchor.getAttribute("href")?.match(/(?:^|\/)videos\/(\d+)(?:[/?#]|$)/);
          if (!match) return false;
          vodId = match[1];
          return true;
        });
        const durationNode = Array.from(card.querySelectorAll("*"))
          .find((node) => node.children.length === 0 && durationSecondsFromText(node.textContent || "") != null);
        durationSeconds = durationSecondsFromText(durationNode?.textContent || "");
        if (videoLink && durationSeconds != null) break;
      }

      const progressPercent = Number(bar.getAttribute("aria-valuenow"));
      if (!vodId || durationSeconds == null || !Number.isFinite(progressPercent) || progressPercent <= 0 || progressPercent > 100) continue;
      // Twitch's signed-in Continue Watching row exposes the remote checkpoint
      // as a percentage plus the VOD's displayed duration. This lets the TV
      // history catch up from the homepage without opening the VOD or handling
      // browser credentials.
      sendPosition(vodId, durationSeconds * progressPercent * 10);
    }
  }

  function observe(video) {
    if (video.dataset.pureTvResumeSync === "1") return;
    video.dataset.pureTvResumeSync = "1";
    video.addEventListener("loadedmetadata", () => sendCurrentPosition(video, true));
    video.addEventListener("seeked", () => sendCurrentPosition(video, true));
    video.addEventListener("pause", () => sendCurrentPosition(video, true));
    video.addEventListener("timeupdate", () => sendCurrentPosition(video));
  }

  function syncFromTvOnVodLoad(video, vodId) {
    if (tvResumeChecked.has(vodId) || tvResumeInFlight.has(vodId) || video.readyState < 1) return;
    tvResumeInFlight.add(vodId);
    void (async () => {
      let gotTvPosition = false;
      // Twitch can restore its own account position after loadedmetadata. Compare
      // a few times during startup so a later Twitch seek cannot undo a TV-ahead
      // resume. Once startup settles, user seeks are left alone.
      for (const waitMs of [0, 1_200, 1_800, 3_500]) {
        if (waitMs > 0) await new Promise((resolve) => window.setTimeout(resolve, waitMs));
        if (vodIdFromPath() !== vodId || document.querySelector("video") !== video) return;

        const result = await chrome.runtime.sendMessage({ type: "getTvVodPosition", vodId }).catch(() => null);
        if (!result?.ok || !Number.isFinite(result.positionMs)) continue;
        gotTvPosition = true;
        const durationMs = Number.isFinite(video.duration) ? video.duration * 1_000 : Infinity;
        const targetMs = Math.min(Math.max(0, result.positionMs), durationMs);
        const browserPositionMs = Math.max(0, Math.floor(video.currentTime * 1_000));
        // Keep the farther position. A TV checkpoint behind the browser is never
        // applied, while a TV checkpoint ahead moves Twitch's active VOD forward.
        if (targetMs > browserPositionMs + 5_000) video.currentTime = targetMs / 1_000;
      }
      if (gotTvPosition) tvResumeChecked.add(vodId);
    })().catch(() => {}).finally(() => tvResumeInFlight.delete(vodId));
  }

  function sample() {
    const vodId = vodIdFromPath();
    if (!vodId) {
      if (location.pathname === "/") syncHomepageContinueWatching();
      return;
    }
    const video = document.querySelector("video");
    if (!video) return;
    observe(video);
    syncFromTvOnVodLoad(video, vodId);
    // Polling covers Twitch SPA route changes and player reuse without reading
    // page storage, cookies, chat, or account credentials.
    sendCurrentPosition(video);
  }

  sample();
  window.setInterval(sample, intervalMs);
})();
