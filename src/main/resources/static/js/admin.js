function initializeRejectedExportDateRange(documentObject, pickerApi) {
    const form = documentObject.getElementById("rejected-export-form");
    const fromInput = documentObject.getElementById("processed-from");
    const toInput = documentObject.getElementById("processed-to");
    const errorElement = documentObject.getElementById("rejected-export-date-error");
    const status = documentObject.getElementById("rejected-export-status");
    if (!form || !fromInput || !toInput || !pickerApi) return null;

    const range = pickerApi.createRange({
        fromInput,
        toInput,
        errorElement
    });
    form.addEventListener("submit", event => {
        if (!range.validate()) {
            event.preventDefault();
            if (status) status.textContent = "";
            return;
        }
        if (status) status.textContent = "Download requested. Check your browser’s Downloads for progress or errors.";
    });
    return range;
}

function legacyAdminDestination(path, hash) {
    if (path !== '/admin') return null;
    if (hash === '#rejected-export-title') return '/admin/rejects';
    if (hash === '#ai-queue-title') return '/admin/ai-queue';
    return null;
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        initializeRejectedExportDateRange,
        legacyAdminDestination
    };
}

if (typeof document !== "undefined") {
    const destination = legacyAdminDestination(window.location.pathname, window.location.hash);
    if (destination) window.location.replace(destination);
    initializeRejectedExportDateRange(document, window.UtcDateTimePicker);
}
