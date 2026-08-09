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
    assert.equal(typeof pickerApi.validateUtcDateTimeRange, "function");
    assert.equal(typeof pickerApi.normalizeDisplayUtcDateTime, "function");
    assert.equal(typeof pickerApi.createRange, "function");
});

test("typed 24-hour display value normalizes without timezone conversion", {
    skip: typeof pickerApi.normalizeDisplayUtcDateTime !== "function"
}, () => {
    const normalize = pickerApi.normalizeDisplayUtcDateTime;

    assert.equal(normalize(""), "");
    assert.equal(normalize("03.08.2026 02:05"), "2026-08-03T02:05");
    assert.equal(normalize("31.02.2026 02:05"), null);
    assert.equal(normalize("03.08.2026 24:00"), null);
    assert.equal(normalize("08/03/2026 02:05"), null);
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
        "Enter date and time as DD.MM.YYYY HH:mm."
    );
    assert.equal(
        validate("2026-08-03T24:00", ""),
        "Enter date and time as DD.MM.YYYY HH:mm."
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

function fakeInput(value = "") {
    const input = fakeElement(value);
    const wrapper = {input};
    input.closest = () => wrapper;
    return {input, wrapper};
}

test("Flatpickr range uses the approved 24-hour configuration and commits once", {
    skip: typeof pickerApi.createRange !== "function"
}, () => {
    const from = fakeInput();
    const to = fakeInput();
    const error = fakeElement();
    error.id = "date-error";
    error.hidden = true;
    const calls = [];
    const instances = [];
    const previousFlatpickr = globalThis.flatpickr;

    globalThis.flatpickr = (wrapper, options) => {
        calls.push({wrapper, options});
        const instance = {
            input: wrapper.input,
            altInput: fakeElement(wrapper.input.value),
            selectedDates: wrapper.input.value ? [new Date()] : [],
            clear() {
                this.input.value = "";
                this.altInput.value = "";
                this.selectedDates = [];
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
            fromInput: from.input,
            toInput: to.input,
            errorElement: error,
            onCommit: () => commits++
        });

        assert.equal(calls.length, 2);
        const options = calls[0].options;
        assert.equal(options.enableTime, true);
        assert.equal(options.time_24hr, true);
        assert.equal(options.minuteIncrement, 1);
        assert.equal(options.altInput, true);
        assert.equal(options.altFormat, "d.m.Y H:i");
        assert.equal(options.dateFormat, "Y-m-d\\TH:i");
        assert.equal(options.allowInput, true);
        assert.equal(options.disableMobile, true);
        assert.equal(options.wrap, true);
        assert.equal(options.locale.firstDayOfWeek, 1);

        from.input.value = "2026-08-03T02:00";
        instances[0].altInput.value = "03.08.2026 02:00";
        instances[0].selectedDates = [new Date("2026-08-03T02:00:00Z")];
        options.onClose();
        options.onClose();
        assert.equal(commits, 1);

        to.input.value = "2026-08-03T02:00";
        instances[1].altInput.value = "03.08.2026 02:00";
        instances[1].selectedDates = [new Date("2026-08-03T02:00:00Z")];
        calls[1].options.onClose();
        assert.equal(commits, 1);
        assert.equal(range.validate(), false);
        assert.equal(error.hidden, false);
        assert.equal(error.textContent, "From must be earlier than To.");

        to.input.value = "2026-08-03T03:00";
        instances[1].altInput.value = "03.08.2026 03:00";
        calls[1].options.onClose();
        assert.equal(commits, 2);
        assert.equal(range.validate(), true);
        assert.equal(error.hidden, true);

        range.clear();
        assert.equal(from.input.value, "");
        assert.equal(to.input.value, "");
        assert.equal(commits, 2);

        range.destroy();
        assert.equal(instances[0].destroyed, true);
        assert.equal(instances[1].destroyed, true);
    } finally {
        globalThis.flatpickr = previousFlatpickr;
    }
});

test("non-empty unparsed display value is invalid", {
    skip: typeof pickerApi.createRange !== "function"
}, () => {
    const from = fakeInput();
    const to = fakeInput();
    const error = fakeElement();
    error.id = "date-error";
    const previousFlatpickr = globalThis.flatpickr;
    const instances = [];

    globalThis.flatpickr = wrapper => {
        const instance = {
            input: wrapper.input,
            altInput: fakeElement(),
            selectedDates: [],
            clear() {},
            destroy() {}
        };
        instances.push(instance);
        return instance;
    };

    try {
        const range = pickerApi.createRange({
            fromInput: from.input,
            toInput: to.input,
            errorElement: error
        });
        instances[0].altInput.value = "not a date";

        assert.equal(range.validate(), false);
        assert.equal(error.textContent, "Enter date and time as DD.MM.YYYY HH:mm.");
    } finally {
        globalThis.flatpickr = previousFlatpickr;
    }
});

test("Tab commits a typed display value before focus enters the calendar", () => {
    const from = fakeInput();
    const to = fakeInput();
    const error = fakeElement();
    error.id = "date-error";
    const previousFlatpickr = globalThis.flatpickr;
    const calls = [];
    const instances = [];

    globalThis.flatpickr = (wrapper, options) => {
        calls.push({wrapper, options});
        const instance = {
            input: wrapper.input,
            altInput: fakeElement(wrapper.input.value),
            selectedDates: [],
            setDate(value) {
                this.input.value = value;
                this.selectedDates = value ? [new Date(`${value}:00Z`)] : [];
            },
            clear() {},
            destroy() {}
        };
        instances.push(instance);
        return instance;
    };

    try {
        let commits = 0;
        pickerApi.createRange({
            fromInput: from.input,
            toInput: to.input,
            errorElement: error,
            onCommit: () => commits++
        });
        assert.equal(typeof calls[0].options.onKeyDown, "function");

        instances[0].altInput.value = "03.08.2026 02:05";
        calls[0].options.onKeyDown(
            [],
            "",
            instances[0],
            {key: "Tab", target: instances[0].altInput}
        );

        assert.equal(from.input.value, "2026-08-03T02:05");
        assert.equal(commits, 1);
        assert.equal(error.hidden, true);

        instances[0].altInput.value = "31.02.2026 02:05";
        calls[0].options.onKeyDown(
            [],
            "",
            instances[0],
            {key: "Tab", target: instances[0].altInput}
        );

        assert.equal(from.input.value, "2026-08-03T02:05");
        assert.equal(instances[0].altInput.value, "31.02.2026 02:05");
        assert.equal(commits, 1);
        assert.equal(error.textContent, "Enter date and time as DD.MM.YYYY HH:mm.");
    } finally {
        globalThis.flatpickr = previousFlatpickr;
    }
});
