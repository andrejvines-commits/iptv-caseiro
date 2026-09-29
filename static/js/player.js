const video = document.querySelector("#video-player");
const loading = document.querySelector("#player-loading");
const errorBox = document.querySelector("#player-error");
const volumeDown = document.querySelector("#volume-down");
const volumeUp = document.querySelector("#volume-up");
const volumeToggle = document.querySelector("#volume-toggle");
const volumeLevel = document.querySelector("#volume-level");
const playerShell = document.querySelector("#player-shell");
const playToggle = document.querySelector("#play-toggle");
const programGuide = document.querySelector("#program-guide");

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

  function updatePlayButton() {
    const paused = video.paused;
    const icon = playToggle?.querySelector("i");
    if (icon) icon.className = `bi ${paused ? "bi-play-fill" : "bi-pause-fill"}`;
    if (playToggle) {
      playToggle.title = paused ? "Continuar" : "Pausar";
      playToggle.setAttribute("aria-label", playToggle.title);
    }
  }

  playToggle?.addEventListener("click", () => {
    if (video.paused) video.play().catch(() => {});
    else video.pause();
  });
  video.addEventListener("play", updatePlayButton);
  video.addEventListener("pause", updatePlayButton);
  updatePlayButton();

  let controlsTimer;
  function resetControlsTimer() {
    if (!playerShell) return;
    playerShell.classList.remove("controls-hidden");
    window.clearTimeout(controlsTimer);
    controlsTimer = window.setTimeout(() => playerShell.classList.add("controls-hidden"), 3000);
  }
  ["pointermove", "pointerdown", "touchstart", "keydown"].forEach((eventName) => {
    playerShell?.addEventListener(eventName, resetControlsTimer, { passive: true });
  });
  resetControlsTimer();

  function formatTime(seconds) {
    return new Date(seconds * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function appendProgram(label, program) {
    if (!programGuide || !program) return;
    const heading = document.createElement("small");
    heading.textContent = `${label} · ${formatTime(program.start)}–${formatTime(program.end)}`;
    const title = document.createElement("strong");
    title.textContent = program.title;
    programGuide.append(heading, title);
  }

  async function loadProgramGuide() {
    if (!programGuide?.dataset.url) return;
    try {
      const response = await fetch(programGuide.dataset.url, { headers: { Accept: "application/json" } });
      if (!response.ok) return;
      const guide = await response.json();
      if (!guide.current && !guide.next) return;
      programGuide.replaceChildren();
      appendProgram("Agora", guide.current);
      appendProgram("A seguir", guide.next);
      programGuide.hidden = false;
    } catch (_) {
      // O guia é opcional e nunca interfere na reprodução.
    }
  }
  loadProgramGuide();

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
