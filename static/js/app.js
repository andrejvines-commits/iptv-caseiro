// Detecta dois cliques no mesmo item; também funciona com o botão OK do controle remoto da Smart TV.
function createDoubleTapTracker(interval = 450) {
  let lastTarget;
  let lastAt = 0;
  return (target) => {
    const now = performance.now();
    const double = target === lastTarget && now - lastAt <= interval;
    lastTarget = double ? undefined : target;
    lastAt = now;
    return double;
  };
}

// Um clique filtra o catálogo pela pasta; dois cliques abrem a pasta com prévia.
const folderTaps = createDoubleTapTracker(400);
let folderNavigation;
document.querySelectorAll(".category-link[data-folder-url]").forEach((link) => {
  link.addEventListener("click", (event) => {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) return;
    event.preventDefault();
    window.clearTimeout(folderNavigation);
    if (folderTaps(link)) {
      window.location.href = link.dataset.folderUrl;
    } else {
      document.querySelectorAll(".category-link.active").forEach((item) => item.classList.remove("active"));
      link.classList.add("active");
      folderNavigation = window.setTimeout(() => { window.location.href = link.href; }, 400);
    }
  });
});

document.querySelectorAll("form[data-confirm]").forEach((form) => {
  form.addEventListener("submit", (event) => {
    if (!window.confirm(form.dataset.confirm)) event.preventDefault();
  });
});

const typeSelect = document.querySelector("#channel-type");
const sourceInput = document.querySelector("#channel-source");
const sourceHelp = document.querySelector("#source-help");

function updateSourceHelp() {
  if (!typeSelect || !sourceInput || !sourceHelp) return;
  const isFile = typeSelect.value === "arquivo";
  const isExternal = typeSelect.value === "externo";
  sourceInput.placeholder = isFile
    ? "video.mp4"
    : (isExternal ? "https://site-oficial.com/ao-vivo" : "https://exemplo.com/stream.m3u8");
  sourceHelp.textContent = isFile
    ? "Nome do arquivo dentro da pasta videos."
    : (isExternal
      ? "Página oficial aberta em uma nova aba. O login é feito somente no site de destino."
      : "URL HTTP/HTTPS autorizada, incluindo HLS .m3u8.");
}

typeSelect?.addEventListener("change", updateSourceHelp);
updateSourceHelp();

const selectAll = document.querySelector("#select-all");
const channelCheckboxes = [...document.querySelectorAll(".channel-checkbox")];

selectAll?.addEventListener("change", () => {
  channelCheckboxes.forEach((checkbox) => { checkbox.checked = selectAll.checked; });
});

channelCheckboxes.forEach((checkbox) => {
  checkbox.addEventListener("change", () => {
    selectAll.checked = channelCheckboxes.every((item) => item.checked);
    selectAll.indeterminate = !selectAll.checked && channelCheckboxes.some((item) => item.checked);
  });
});

const adminSelectAll = document.querySelector("#admin-select-all");
const adminCheckboxes = [...document.querySelectorAll(".admin-channel-checkbox:not(:disabled)")];
const bulkDeleteButton = document.querySelector("#bulk-delete-button");
const bulkSelectedCount = document.querySelector("#bulk-selected-count");

function updateBulkSelection() {
  const selectedCount = adminCheckboxes.filter((checkbox) => checkbox.checked).length;
  if (bulkSelectedCount) bulkSelectedCount.textContent = String(selectedCount);
  if (bulkDeleteButton) bulkDeleteButton.disabled = selectedCount === 0;
  if (adminSelectAll) {
    adminSelectAll.checked = adminCheckboxes.length > 0 && selectedCount === adminCheckboxes.length;
    adminSelectAll.indeterminate = selectedCount > 0 && selectedCount < adminCheckboxes.length;
  }
}

adminSelectAll?.addEventListener("change", () => {
  adminCheckboxes.forEach((checkbox) => { checkbox.checked = adminSelectAll.checked; });
  updateBulkSelection();
});
adminCheckboxes.forEach((checkbox) => checkbox.addEventListener("change", updateBulkSelection));
updateBulkSelection();
