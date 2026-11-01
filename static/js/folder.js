const previewVideo = document.querySelector("#preview-video");
const previewStage = document.querySelector("#preview-stage");
const previewBadge = document.querySelector("#preview-badge");
const previewMessage = document.querySelector("#preview-message");
const previewLoading = document.querySelector("#preview-loading");
const previewName = document.querySelector("#preview-name");
const previewCategory = document.querySelector("#preview-category");
const previewType = document.querySelector("#preview-type");
const previewGuide = document.querySelector("#preview-guide");
const previewActions = document.querySelector("#preview-actions");
const fullscreenButton = document.querySelector("#preview-fullscreen");
const openButton = document.querySelector("#preview-open");
const folderSearch = document.querySelector("#folder-search");
const folderEmpty = document.querySelector("#folder-empty");
const folderItems = [...document.querySelectorAll(".folder-item")];

const TYPE_LABELS = { stream: "● AO VIVO", arquivo: "ARQUIVO", externo: "LINK" };
let releaseMedia = () => {};
let selected;
let guideRequest = 0;

function showMessage(icon, text) {
  previewMessage.replaceChildren();
  const symbol = document.createElement("i");
  symbol.className = `bi ${icon}`;
  const label = document.createElement("span");
  label.textContent = text;
  previewMessage.append(symbol, label);
  previewMessage.hidden = false;
}

function stopPreview() {
  releaseMedia();
  releaseMedia = () => {};
  previewVideo.pause();
  previewVideo.removeAttribute("src");
  previewVideo.load();
  previewLoading.hidden = true;
  previewBadge.hidden = true;
}

function playPreview(source, kind) {
  previewMessage.hidden = true;
  previewLoading.hidden = false;
  previewBadge.hidden = false;
  releaseMedia = attachMediaSource(previewVideo, source, kind, previewFailed);
  // Navegadores bloqueiam som automático sem interação; nesse caso a prévia inicia sem áudio.
  previewVideo.play().catch(() => {
    previewVideo.muted = true;
    previewVideo.play().catch(() => {});
  });
}

function previewFailed() {
  previewLoading.hidden = true;
  showMessage("bi-exclamation-triangle", "Prévia indisponível para este canal");
}

function formatTime(seconds) {
  return new Date(seconds * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

async function loadGuide(url) {
  const request = ++guideRequest;
  previewGuide.hidden = true;
  if (!url) return;
  try {
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok || request !== guideRequest) return;
    const guide = await response.json();
    if (request !== guideRequest || (!guide.current && !guide.next)) return;
    previewGuide.replaceChildren();
    [["Agora", guide.current], ["A seguir", guide.next]].forEach(([label, program]) => {
      if (!program) return;
      const heading = document.createElement("small");
      heading.textContent = `${label} · ${formatTime(program.start)}–${formatTime(program.end)}`;
      const title = document.createElement("strong");
      title.textContent = program.title;
      previewGuide.append(heading, title);
    });
    previewGuide.hidden = false;
  } catch (_) {
    // A programação é opcional e não interfere na prévia.
  }
}

function select(item) {
  if (selected === item) return;
  selected?.classList.remove("is-selected");
  selected = item;
  item.classList.add("is-selected");
  const { name, category, type, source, kind, available, playerUrl, epgUrl } = item.dataset;
  previewName.textContent = name;
  previewCategory.textContent = category;
  previewType.textContent = TYPE_LABELS[type] || type;
  previewType.dataset.type = type;
  previewType.hidden = false;
  previewActions.hidden = false;
  openButton.href = playerUrl;
  const external = type === "externo";
  openButton.target = external ? "_blank" : "";
  openButton.rel = external ? "noopener noreferrer" : "";
  openButton.querySelector("span").textContent = external ? "Abrir site" : "Abrir no player";
  fullscreenButton.hidden = external;
  stopPreview();
  if (external) showMessage("bi-box-arrow-up-right", "Link oficial: abre no site de destino");
  else if (available !== "1") showMessage("bi-file-earmark-play", "Arquivo não encontrado na pasta videos");
  else playPreview(source, kind);
  loadGuide(epgUrl);
}

function enterFullscreen() {
  if (!selected) return;
  if (selected.dataset.type === "externo") {
    window.open(selected.dataset.playerUrl, "_blank", "noopener");
    return;
  }
  const request = previewVideo.requestFullscreen || previewVideo.webkitRequestFullscreen;
  if (!request) {
    window.location.href = selected.dataset.playerUrl;
    return;
  }
  previewVideo.muted = false;
  previewVideo.controls = true;
  Promise.resolve(request.call(previewVideo)).catch(() => { window.location.href = selected.dataset.playerUrl; });
}

previewVideo.addEventListener("playing", () => { previewLoading.hidden = true; });
previewVideo.addEventListener("canplay", () => { previewLoading.hidden = true; });
previewVideo.addEventListener("error", () => { if (previewVideo.getAttribute("src")) previewFailed(); });
document.addEventListener("fullscreenchange", () => {
  if (!document.fullscreenElement) previewVideo.controls = false;
});

const taps = createDoubleTapTracker();
folderItems.forEach((item) => {
  item.addEventListener("click", () => {
    const double = taps(item);
    previewVideo.muted = false;
    select(item);
    if (double) enterFullscreen();
  });
});
fullscreenButton.addEventListener("click", enterFullscreen);
previewStage.addEventListener("dblclick", enterFullscreen);

function normalizeSearch(value) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase();
}

folderSearch?.addEventListener("input", () => {
  const terms = normalizeSearch(folderSearch.value).trim().split(/\s+/).filter(Boolean);
  let visible = 0;
  folderItems.forEach((item) => {
    const searchableFields = [
      item.dataset.name,
      item.dataset.category,
      item.dataset.description,
      item.dataset.type,
    ].map(normalizeSearch);
    const match = terms.every((term) => searchableFields.some((field) => field.includes(term)));
    item.parentElement.hidden = !match;
    if (match) visible += 1;
  });
  folderEmpty.hidden = visible > 0;
});

window.addEventListener("beforeunload", () => releaseMedia());

const first = folderItems.find((item) => item.dataset.type !== "externo" && item.dataset.available === "1");
if (first) {
  previewVideo.muted = true;
  select(first);
}
