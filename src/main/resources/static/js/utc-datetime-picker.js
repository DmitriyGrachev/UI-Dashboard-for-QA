(function (root, factory) {
    const api = factory(root);
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    }
    if (root && typeof root === "object") {
        root.UtcDateTimePicker = api;
    }
})(typeof window !== "undefined" ? window : globalThis, root => {
    "use strict";

    const INVALID_DATE_MESSAGE = "Enter date and time as DD.MM.YYYY HH:mm.";
    const INVALID_RANGE_MESSAGE = "From must be earlier than To.";
    const NORMALIZED_PATTERN =
        /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;
    const DISPLAY_PATTERN =
        /^(\d{2})\.(\d{2})\.(\d{4}) (\d{2}):(\d{2})$/;

    function isValidUtcDateTime(value) {
        if (value === "") return true;
        const match = NORMALIZED_PATTERN.exec(value);
        if (!match) return false;

        const [, yearText, monthText, dayText, hourText, minuteText] = match;
        const year = Number(yearText);
        const month = Number(monthText);
        const day = Number(dayText);
        const hour = Number(hourText);
        const minute = Number(minuteText);
        if (month < 1 || month > 12 || hour > 23 || minute > 59) return false;

        const reconstructed = new Date(0);
        reconstructed.setUTCFullYear(year, month - 1, day);
        reconstructed.setUTCHours(hour, minute, 0, 0);
        return reconstructed.getUTCFullYear() === year
            && reconstructed.getUTCMonth() === month - 1
            && reconstructed.getUTCDate() === day
            && reconstructed.getUTCHours() === hour
            && reconstructed.getUTCMinutes() === minute;
    }

    function validateUtcDateTimeRange(fromValue, toValue) {
        if (!isValidUtcDateTime(fromValue) || !isValidUtcDateTime(toValue)) {
            return INVALID_DATE_MESSAGE;
        }
        if (fromValue && toValue && fromValue >= toValue) {
            return INVALID_RANGE_MESSAGE;
        }
        return null;
    }

    function normalizeDisplayUtcDateTime(value) {
        const trimmed = value.trim();
        if (trimmed === "") return "";
        const match = DISPLAY_PATTERN.exec(trimmed);
        if (!match) return null;

        const [, day, month, year, hour, minute] = match;
        const normalized = `${year}-${month}-${day}T${hour}:${minute}`;
        return isValidUtcDateTime(normalized) ? normalized : null;
    }

    function serializedRange(fromInput, toInput) {
        return `${fromInput.value}|${toInput.value}`;
    }

    function describedByWithError(element, errorId) {
        const current = (element.getAttribute("aria-describedby") || "")
            .split(/\s+/)
            .filter(Boolean);
        if (errorId && !current.includes(errorId)) current.push(errorId);
        return current.join(" ");
    }

    function setElementValidity(element, invalid, errorId) {
        if (!element) return;
        if (invalid) {
            element.setAttribute("aria-invalid", "true");
        } else {
            element.removeAttribute("aria-invalid");
        }
        const describedBy = describedByWithError(element, errorId);
        if (describedBy) element.setAttribute("aria-describedby", describedBy);
    }

    function synchronizeDisplayInput(instance) {
        if (!instance?.altInput) return null;
        const normalized = normalizeDisplayUtcDateTime(instance.altInput.value);
        if (normalized === null) return INVALID_DATE_MESSAGE;
        if (normalized !== instance.input.value) {
            instance.setDate(normalized, false, "Y-m-d\\TH:i");
        }
        return null;
    }

    function createRange({
        fromInput,
        toInput,
        errorElement,
        onCommit = () => {}
    }) {
        if (!fromInput || !toInput) {
            throw new TypeError("Both UTC date-time range inputs are required.");
        }

        let fromPicker = null;
        let toPicker = null;
        let lastCommittedRange = serializedRange(fromInput, toInput);
        const errorId = errorElement?.id || "";

        function showError(message) {
            const invalid = Boolean(message);
            setElementValidity(fromInput, invalid, errorId);
            setElementValidity(toInput, invalid, errorId);
            setElementValidity(fromPicker?.altInput, invalid, errorId);
            setElementValidity(toPicker?.altInput, invalid, errorId);
            if (errorElement) {
                errorElement.textContent = message || "";
                errorElement.hidden = !invalid;
            }
        }

        function validate() {
            const displayMessage = synchronizeDisplayInput(fromPicker)
                || synchronizeDisplayInput(toPicker);
            const message = displayMessage
                || validateUtcDateTimeRange(fromInput.value, toInput.value);
            showError(message);
            return message === null;
        }

        function commitIfChanged() {
            if (!validate()) return;
            const currentRange = serializedRange(fromInput, toInput);
            if (currentRange === lastCommittedRange) return;
            lastCommittedRange = currentRange;
            onCommit();
        }

        const fallbackListeners = [];
        if (typeof root.flatpickr === "function") {
            const options = () => ({
                enableTime: true,
                time_24hr: true,
                minuteIncrement: 1,
                altInput: true,
                altFormat: "d.m.Y H:i",
                dateFormat: "Y-m-d\\TH:i",
                ariaDateFormat: "d.m.Y H:i",
                allowInput: true,
                disableMobile: true,
                wrap: true,
                locale: {
                    firstDayOfWeek: 1
                },
                onKeyDown: (_selectedDates, _dateText, instance, event) => {
                    if (event?.key === "Tab" && event.target === instance.altInput) {
                        commitIfChanged();
                    }
                },
                onClose: commitIfChanged
            });
            fromPicker = root.flatpickr(
                fromInput.closest(".utc-datetime-control"),
                options()
            );
            toPicker = root.flatpickr(
                toInput.closest(".utc-datetime-control"),
                options()
            );
        } else {
            [fromInput, toInput].forEach(input => {
                input.addEventListener("change", commitIfChanged);
                fallbackListeners.push(input);
            });
        }

        return {
            validate,
            clear() {
                if (fromPicker && toPicker) {
                    fromPicker.clear(false);
                    toPicker.clear(false);
                } else {
                    fromInput.value = "";
                    toInput.value = "";
                }
                lastCommittedRange = serializedRange(fromInput, toInput);
                showError(null);
            },
            destroy() {
                fromPicker?.destroy();
                toPicker?.destroy();
                fallbackListeners.forEach(input => {
                    input.removeEventListener("change", commitIfChanged);
                });
            }
        };
    }

    return {
        createRange,
        normalizeDisplayUtcDateTime,
        validateUtcDateTimeRange
    };
});
