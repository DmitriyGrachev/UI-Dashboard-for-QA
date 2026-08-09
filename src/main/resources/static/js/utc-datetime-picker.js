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

    const INVALID_DATE_MESSAGE = "Enter date as DD.MM.YYYY and time as HH:mm.";
    const INVALID_RANGE_MESSAGE = "From must be earlier than To.";
    const CANONICAL_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;
    const DISPLAY_DATE_PATTERN = /^(\d{2})\.(\d{2})\.(\d{4})$/;

    function isValidUtcDateTime(value) {
        if (value === "") return true;
        const match = CANONICAL_PATTERN.exec(value);
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

    function parseCanonicalUtcDateTime(value) {
        if (value === "") return {date: "", hour: "00", minute: "00"};
        if (!isValidUtcDateTime(value)) return null;
        const [, year, month, day, hour, minute] = CANONICAL_PATTERN.exec(value);
        return {
            date: `${day}.${month}.${year}`,
            hour,
            minute
        };
    }

    function normalizeSegment(value) {
        const text = String(value ?? "").trim();
        return /^\d{1,2}$/.test(text) ? text.padStart(2, "0") : null;
    }

    function composeCanonicalUtcDateTime(dateValue, hourValue, minuteValue) {
        if (dateValue.trim() === "") return "";
        const dateMatch = DISPLAY_DATE_PATTERN.exec(dateValue.trim());
        const hour = normalizeSegment(hourValue);
        const minute = normalizeSegment(minuteValue);
        if (!dateMatch || hour === null || minute === null) return null;

        const [, day, month, year] = dateMatch;
        const canonical = `${year}-${month}-${day}T${hour}:${minute}`;
        return isValidUtcDateTime(canonical) ? canonical : null;
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

    function requiredElement(boundary, selector) {
        const element = boundary?.querySelector(selector);
        if (!element) throw new TypeError(`Missing UTC boundary element: ${selector}`);
        return element;
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
        if (typeof root.TimeSegmentCombobox?.create !== "function") {
            throw new TypeError("TimeSegmentCombobox must be loaded before UtcDateTimePicker.");
        }

        const errorId = errorElement?.id || "";
        let boundaries = [];
        let lastCommittedRange = serializedRange(fromInput, toInput);

        function showError(message) {
            if (!errorElement) return;
            errorElement.textContent = message || "";
            errorElement.hidden = !message;
        }

        function validate() {
            const messages = boundaries.map(boundary => boundary.synchronize());
            const firstMessage = messages.find(Boolean);
            if (firstMessage) {
                showError(firstMessage);
                return false;
            }

            const rangeMessage = validateUtcDateTimeRange(
                fromInput.value,
                toInput.value
            );
            if (rangeMessage) {
                boundaries.forEach(boundary => boundary.setInvalid(true));
                showError(rangeMessage);
                return false;
            }

            boundaries.forEach(boundary => boundary.setInvalid(false));
            showError(null);
            return true;
        }

        function commitIfChanged() {
            if (!validate()) return;
            const currentRange = serializedRange(fromInput, toInput);
            if (currentRange === lastCommittedRange) return;
            lastCommittedRange = currentRange;
            onCommit();
        }

        function createBoundary(canonicalInput) {
            const boundary = canonicalInput.closest("[data-utc-boundary]");
            const dateInput = requiredElement(boundary, "[data-date-input]");
            const dateWrap = requiredElement(boundary, "[data-date-wrap]");
            const hourInput = requiredElement(
                boundary,
                "[data-hour-segment] [data-segment-input]"
            );
            const hourToggle = requiredElement(
                boundary,
                "[data-hour-segment] [data-segment-toggle]"
            );
            const hourListbox = requiredElement(
                boundary,
                "[data-hour-segment] [data-segment-listbox]"
            );
            const minuteInput = requiredElement(
                boundary,
                "[data-minute-segment] [data-segment-input]"
            );
            const minuteToggle = requiredElement(
                boundary,
                "[data-minute-segment] [data-segment-toggle]"
            );
            const minuteListbox = requiredElement(
                boundary,
                "[data-minute-segment] [data-segment-listbox]"
            );
            const initial = parseCanonicalUtcDateTime(canonicalInput.value)
                || {date: "", hour: "00", minute: "00"};
            dateInput.value = initial.date;
            hourInput.value = initial.hour;
            minuteInput.value = initial.minute;

            const hour = root.TimeSegmentCombobox.create({
                input: hourInput,
                toggle: hourToggle,
                listbox: hourListbox,
                min: 0,
                max: 23,
                onCommit: commitIfChanged,
                onInvalid: commitIfChanged
            });
            const minute = root.TimeSegmentCombobox.create({
                input: minuteInput,
                toggle: minuteToggle,
                listbox: minuteListbox,
                min: 0,
                max: 59,
                onCommit: commitIfChanged,
                onInvalid: commitIfChanged
            });

            function setInvalid(invalid) {
                setElementValidity(dateInput, invalid, errorId);
                setElementValidity(hourInput, invalid, errorId);
                setElementValidity(minuteInput, invalid, errorId);
            }

            function synchronize() {
                if (dateInput.value.trim() === "") {
                    canonicalInput.value = "";
                    setInvalid(false);
                    return null;
                }
                const validTime = hour.validate() && minute.validate();
                const canonical = validTime
                    ? composeCanonicalUtcDateTime(
                        dateInput.value,
                        hour.getValue(),
                        minute.getValue()
                    )
                    : null;
                if (canonical === null) {
                    setInvalid(true);
                    return INVALID_DATE_MESSAGE;
                }
                canonicalInput.value = canonical;
                setInvalid(false);
                return null;
            }

            const picker = typeof root.flatpickr === "function"
                ? root.flatpickr(dateWrap, {
                    enableTime: false,
                    dateFormat: "d.m.Y",
                    ariaDateFormat: "d.m.Y",
                    allowInput: true,
                    disableMobile: true,
                    wrap: true,
                    locale: {firstDayOfWeek: 1},
                    onKeyDown: (_dates, _text, _instance, event) => {
                        if (event?.key === "Tab" || event?.key === "Enter") {
                            commitIfChanged();
                        }
                    },
                    onClose: commitIfChanged
                })
                : null;

            return {
                synchronize,
                setInvalid,
                clear() {
                    picker?.clear(false);
                    dateInput.value = "";
                    hour.reset();
                    minute.reset();
                    canonicalInput.value = "";
                    setInvalid(false);
                },
                destroy() {
                    picker?.destroy();
                    hour.destroy();
                    minute.destroy();
                }
            };
        }

        boundaries = [createBoundary(fromInput), createBoundary(toInput)];

        return {
            validate,
            clear() {
                boundaries.forEach(boundary => boundary.clear());
                lastCommittedRange = serializedRange(fromInput, toInput);
                showError(null);
            },
            destroy() {
                boundaries.forEach(boundary => boundary.destroy());
            }
        };
    }

    return {
        composeCanonicalUtcDateTime,
        createRange,
        parseCanonicalUtcDateTime,
        validateUtcDateTimeRange
    };
});
