const video = document.querySelector("#video-player");
const loading = document.querySelector("#player-loading");
const errorBox = document.querySelector("#player-error");
const volumeDown = document.querySelector("#volume-down");
const volumeUp = document.querySelector("#volume-up");
const volumeToggle = document.querySelector("#volume-toggle");
const volumeLevel = document.querySelector("#volume-level");

if (video) {
  const source = video.dataset.source;
  let hls;

  const savedVolume = Number(window.localStorage.getItem("iptv-volume"));
  const savedMuted = window.localStorage.getItem("iptv-muted") === "true";
  video.volume = Number.isFinite(savedVolume) ? Math.min(1, Math.max(0, savedVolume)) : 1;
  video.muted = savedMuted;

  function updateVolumeDisplay() {
    if (volumeLevel) volumeLevel.textContent = video.muted ? "Mudo" : `${Math.round(video.volume * 100)}%`;
    const icon = volumeToggle?.querySelector("i");
    if (icon) {
      icon.className = `bi ${video.muted || video.volume === 0 ? "bi-volume-mute-fill" : "bi-volume-up-fill"}`;
    }
    window.localStorage.setItem("iptv-volume", String(video.volume));
    window.localStorage.setItem("iptv-muted", String(video.muted));
  }

  function changeVolume(amount) {
    video.muted = false;
    video.volume = Math.min(1, Math.max(0, Math.round((video.volume + amount) * 10) / 10));
    updateVolumeDisplay();
  }

  volumeDown?.addEventListener("click", () => changeVolume(-0.1));
  volumeUp?.addEventListener("click", () => changeVolume(0.1));
  volumeToggle?.addEventListener("click", () => {
    video.muted = !video.muted;
    updateVolumeDisplay();
  });
  video.addEventListener("volumechange", updateVolumeDisplay);
  updateVolumeDisplay();

  function ready() {
    loading?.setAttribute("hidden", "");
  }

  function fail() {
    loading?.setAttribute("hidden", "");
    errorBox?.removeAttribute("hidden");
  }

  if (/\.m3u8(?:$|\?)/i.test(source)) {
    if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = source;
    } else if (window.Hls?.isSupported()) {
      hls = new window.Hls({ enableWorker: true });
      hls.loadSource(source);
      hls.attachMedia(video);
      hls.on(window.Hls.Events.ERROR, (_, data) => {
        if (data.fatal) fail();
      });
    } else {
      fail();
    }
  } else {
    video.src = source;
  }

  video.addEventListener("loadeddata", ready, { once: true });
  video.addEventListener("canplay", ready, { once: true });
  if (new URLSearchParams(window.location.search).get("autoplay") === "1") {
    video.addEventListener("canplay", () => video.play().catch(() => {}), { once: true });
  }
  video.addEventListener("error", fail);
  window.addEventListener("beforeunload", () => hls?.destroy());
}
