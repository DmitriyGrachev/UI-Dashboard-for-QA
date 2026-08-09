const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const modulePath = path.resolve(
    __dirname,
    "../../main/resources/static/js/utc-datetime-picker.js"
);
const moduleExists = fs.existsSync(modulePath);
const pickerApi = moduleExists ? require(modulePath) : {};

test("shared UTC date-time picker module exists", () => {
    assert.equal(moduleExists, true);
    assert.equal(typeof pickerApi.parseCanonicalUtcDateTime, "function");
    assert.equal(typeof pickerApi.composeCanonicalUtcDateTime, "function");
    assert.equal(typeof pickerApi.validateUtcDateTimeRange, "function");
    assert.equal(typeof pickerApi.createRange, "function");
});

test("canonical UTC value splits into visible date, hour and minute", {
    skip: typeof pickerApi.parseCanonicalUtcDateTime !== "function"
}, () => {
    assert.deepEqual(
        pickerApi.parseCanonicalUtcDateTime("2026-08-09T02:30"),
        {date: "09.08.2026", hour: "02", minute: "30"}
    );
    assert.deepEqual(
        pickerApi.parseCanonicalUtcDateTime(""),
        {date: "", hour: "00", minute: "00"}
    );
    assert.equal(pickerApi.parseCanonicalUtcDateTime("2026-02-30T02:30"), null);
});

test("visible date and time compose without timezone conversion", {
    skip: typeof pickerApi.composeCanonicalUtcDateTime !== "function"
}, () => {
    const compose = pickerApi.composeCanonicalUtcDateTime;

    assert.equal(compose("", "00", "00"), "");
    assert.equal(compose("09.08.2026", "02", "30"), "2026-08-09T02:30");
    assert.equal(compose("31.02.2026", "02", "30"), null);
    assert.equal(compose("09.08.2026", "24", "00"), null);
    assert.equal(compose("08/09/2026", "02", "30"), null);
});

test("UTC range validation accepts empty and one-sided boundaries", {
    skip: typeof pickerApi.validateUtcDateTimeRange !== "function"
}, () => {
    const validate = pickerApi.validateUtcDateTimeRange;

    assert.equal(validate("", ""), null);
    assert.equal(validate("2026-08-03T02:00", ""), null);
    assert.equal(validate("", "2026-08-03T03:00"), null);
    assert.equal(validate("2026-08-03T23:59", "2026-08-04T00:00"), null);
});

test("UTC range validation rejects invalid and unordered values", {
    skip: typeof pickerApi.validateUtcDateTimeRange !== "function"
}, () => {
    const validate = pickerApi.validateUtcDateTimeRange;

    assert.equal(
        validate("2026-02-30T02:00", ""),
        "Enter date as DD.MM.YYYY and time as HH:mm."
    );
    assert.equal(
        validate("2026-08-03T24:00", ""),
        "Enter date as DD.MM.YYYY and time as HH:mm."
    );
    assert.equal(
        validate("2026-08-03T02:00", "2026-08-03T02:00"),
        "From must be earlier than To."
    );
    assert.equal(
        validate("2026-08-03T03:00", "2026-08-03T02:00"),
        "From must be earlier than To."
    );
});

function fakeElement(value = "") {
    const attributes = new Map();
    const listeners = new Map();
    return {
        value,
        hidden: false,
        textContent: "",
        setAttribute(name, attributeValue) {
            attributes.set(name, String(attributeValue));
        },
        removeAttribute(name) {
            attributes.delete(name);
        },
        getAttribute(name) {
            return attributes.get(name) ?? null;
        },
        addEventListener(name, listener) {
            listeners.set(name, listener);
        },
        removeEventListener(name) {
            listeners.delete(name);
        },
        dispatch(name) {
            listeners.get(name)?.();
        }
    };
}

function fakeBoundary(canonicalValue = "") {
    const canonical = fakeElement(canonicalValue);
    const dateInput = fakeElement();
    const dateWrap = {input: dateInput};
    const hourInput = fakeElement("00");
    const minuteInput = fakeElement("00");
    const hourToggle = fakeElement();
    const minuteToggle = fakeElement();
    const hourListbox = fakeElement();
    const minuteListbox = fakeElement();
    const elements = new Map([
        ["[data-date-input]", dateInput],
        ["[data-date-wrap]", dateWrap],
        ["[data-hour-segment] [data-segment-input]", hourInput],
        ["[data-hour-segment] [data-segment-toggle]", hourToggle],
        ["[data-hour-segment] [data-segment-listbox]", hourListbox],
        ["[data-minute-segment] [data-segment-input]", minuteInput],
        ["[data-minute-segment] [data-segment-toggle]", minuteToggle],
        ["[data-minute-segment] [data-segment-listbox]", minuteListbox]
    ]);
    const boundary = {
        querySelector(selector) {
            return elements.get(selector) ?? null;
        }
    };
    canonical.closest = () => boundary;
    return {canonical, dateInput, dateWrap, hourInput, minuteInput};
}

function fakeTimeComboboxApi() {
    const controllers = [];
    return {
        controllers,
        create({input, min, max, onCommit}) {
            let accepted = input.value;
            const normalize = value => {
                if (!/^\d{1,2}$/.test(value)) return null;
                const number = Number(value);
                if (number < min || number > max) return null;
                return String(number).padStart(2, "0");
            };
            const controller = {
                getValue: () => normalize(input.value),
                setValue(value, notify = false) {
                    const normalized = normalize(value);
                    if (normalized === null) return false;
                    const changed = normalized !== accepted;
                    accepted = normalized;
                    input.value = normalized;
                    if (changed && notify) onCommit(normalized);
                    return true;
                },
                validate() {
                    return this.setValue(input.value, false);
                },
                reset() {
                    accepted = "00";
                    input.value = "00";
                },
                destroy() {
                    this.destroyed = true;
                },
                commit(value) {
                    return this.setValue(value, true);
                }
            };
            controllers.push(controller);
            return controller;
        }
    };
}

test("split UTC range uses date-only calendars and commits canonical values once", () => {
    const from = fakeBoundary();
    const to = fakeBoundary("2026-08-09T04:00");
    const error = fakeElement();
    error.id = "date-error";
    error.hidden = true;
    const calls = [];
    const instances = [];
    const previousFlatpickr = globalThis.flatpickr;
    const previousTimeApi = globalThis.TimeSegmentCombobox;
    const timeApi = fakeTimeComboboxApi();

    globalThis.TimeSegmentCombobox = timeApi;
    globalThis.flatpickr = (wrapper, options) => {
        calls.push({wrapper, options});
        const instance = {
            input: wrapper.input,
            clear() {
                this.input.value = "";
            },
            setDate(value) {
                this.input.value = value;
            },
            destroy() {
                this.destroyed = true;
            }
        };
        instances.push(instance);
        return instance;
    };

    try {
        let commits = 0;
        const range = pickerApi.createRange({
            fromInput: from.canonical,
            toInput: to.canonical,
            errorElement: error,
            onCommit: () => commits++
        });

        assert.equal(calls.length, 2);
        assert.equal(calls[0].options.enableTime, false);
        assert.equal(calls[0].options.dateFormat, "d.m.Y");
        assert.equal(calls[0].options.allowInput, true);
        assert.equal(calls[0].options.disableMobile, true);
        assert.equal(calls[0].options.wrap, true);
        assert.equal(calls[0].options.locale.firstDayOfWeek, 1);
        assert.equal(to.dateInput.value, "09.08.2026");
        assert.equal(to.hourInput.value, "04");
        assert.equal(to.minuteInput.value, "00");

        from.dateInput.value = "09.08.2026";
        timeApi.controllers[0].commit("02");
        assert.equal(from.canonical.value, "2026-08-09T02:00");
        assert.equal(commits, 1);
        timeApi.controllers[1].commit("30");
        assert.equal(commits, 2);
        calls[0].options.onClose();
        calls[0].options.onClose();
        assert.equal(from.canonical.value, "2026-08-09T02:30");
        assert.equal(commits, 2);

        to.dateInput.value = "09.08.2026";
        timeApi.controllers[2].commit("02");
        timeApi.controllers[3].commit("30");
        calls[1].options.onClose();
        assert.equal(range.validate(), false);
        assert.equal(error.textContent, "From must be earlier than To.");
        assert.equal(commits, 2);

        timeApi.controllers[2].commit("03");
        calls[1].options.onClose();
        assert.equal(to.canonical.value, "2026-08-09T03:30");
        assert.equal(range.validate(), true);
        assert.equal(commits, 3);

        from.dateInput.value = "31.02.2026";
        calls[0].options.onKeyDown([], "", instances[0], {key: "Tab"});
        assert.equal(range.validate(), false);
        assert.equal(error.textContent, "Enter date as DD.MM.YYYY and time as HH:mm.");

        range.clear();
        assert.equal(from.canonical.value, "");
        assert.equal(to.canonical.value, "");
        assert.equal(from.dateInput.value, "");
        assert.equal(from.hourInput.value, "00");
        assert.equal(from.minuteInput.value, "00");

        range.destroy();
        assert.equal(instances[0].destroyed, true);
        assert.equal(timeApi.controllers.every(controller => controller.destroyed), true);
    } finally {
        globalThis.flatpickr = previousFlatpickr;
        globalThis.TimeSegmentCombobox = previousTimeApi;
    }
});
