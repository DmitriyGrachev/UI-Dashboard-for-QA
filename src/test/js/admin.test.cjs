const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const modulePath = path.resolve(
    __dirname,
    "../../main/resources/static/js/admin.js"
);
const moduleExists = fs.existsSync(modulePath);
const adminApi = moduleExists ? require(modulePath) : {};

test("admin date range initializer exists", () => {
    assert.equal(moduleExists, true);
    assert.equal(typeof adminApi.initializeRejectedExportDateRange, "function");
});

test("admin export submits only a valid UTC date range", {
    skip: typeof adminApi.initializeRejectedExportDateRange !== "function"
}, () => {
    const listeners = new Map();
    const elements = {
        "rejected-export-form": {
            addEventListener(name, listener) {
                listeners.set(name, listener);
            }
        },
        "processed-from": {id: "processed-from"},
        "processed-to": {id: "processed-to"},
        "rejected-export-date-error": {id: "rejected-export-date-error"}
    };
    const document = {
        getElementById(id) {
            return elements[id] ?? null;
        }
    };
    let valid = false;
    const range = {validate: () => valid};
    const calls = [];
    const pickerApi = {
        createRange(options) {
            calls.push(options);
            return range;
        }
    };

    const result = adminApi.initializeRejectedExportDateRange(document, pickerApi);

    assert.equal(result, range);
    assert.equal(calls[0].fromInput, elements["processed-from"]);
    assert.equal(calls[0].toInput, elements["processed-to"]);
    assert.equal(calls[0].errorElement, elements["rejected-export-date-error"]);

    let prevented = false;
    listeners.get("submit")({preventDefault: () => prevented = true});
    assert.equal(prevented, true);

    valid = true;
    prevented = false;
    listeners.get("submit")({preventDefault: () => prevented = true});
    assert.equal(prevented, false);
});
