function initializeRejectedExportDateRange(documentObject, pickerApi) {
    const form = documentObject.getElementById("rejected-export-form");
    const fromInput = documentObject.getElementById("processed-from");
    const toInput = documentObject.getElementById("processed-to");
    const errorElement = documentObject.getElementById("rejected-export-date-error");
    if (!form || !fromInput || !toInput || !pickerApi) return null;

    const range = pickerApi.createRange({
        fromInput,
        toInput,
        errorElement
    });
    form.addEventListener("submit", event => {
        if (!range.validate()) event.preventDefault();
    });
    return range;
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        initializeRejectedExportDateRange
    };
}

if (typeof document !== "undefined") {
    initializeRejectedExportDateRange(document, window.UtcDateTimePicker);
}
