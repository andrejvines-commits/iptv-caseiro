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
