// Liga uma fonte ao elemento <video> conforme o formato informado pelo servidor:
// "hls" (.m3u8), "mpegts"/"flv" (streams IPTV via mpegts.js) ou "file" (MP4/WebM nativo).
// Retorna uma função que libera o player.
function attachMediaSource(video, source, kind, onFatal) {
  if (kind === "mpegts" || kind === "flv") {
    if (!window.mpegts?.getFeatureList().mseLivePlayback) {
      onFatal();
      return () => {};
    }
    const player = window.mpegts.createPlayer(
      { type: kind, isLive: true, url: source },
      { enableStashBuffer: false, liveBufferLatencyChasing: true, lazyLoad: false },
    );
    player.on(window.mpegts.Events.ERROR, onFatal);
    player.attachMediaElement(video);
    player.load();
    return () => {
      player.pause();
      player.unload();
      player.detachMediaElement();
      player.destroy();
    };
  }
  if (kind === "hls" && !video.canPlayType("application/vnd.apple.mpegurl")) {
    if (!window.Hls?.isSupported()) {
      onFatal();
      return () => {};
    }
    const hls = new window.Hls({ enableWorker: true });
    hls.loadSource(source);
    hls.attachMedia(video);
    hls.on(window.Hls.Events.ERROR, (_, data) => { if (data.fatal) onFatal(); });
    return () => hls.destroy();
  }
  video.src = source;
  return () => {
    video.removeAttribute("src");
    video.load();
  };
}
