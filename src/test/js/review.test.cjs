const test = require("node:test");
const assert = require("node:assert/strict");

const {
    createReviewDateRange,
    formatUtcDate,
    readStoredScale,
    readStoredFilters,
    toUtcIso,
    toOptionalLong,
    toOptionalBoolean,
    writeStoredScale,
    writeStoredFilters
} = require("../../main/resources/static/js/review.js");

test("review delegates date range behavior to the shared picker", () => {
    assert.equal(typeof createReviewDateRange, "function");
    const controller = {clear() {}};
    const calls = [];
    const pickerApi = {
        createRange(options) {
            calls.push(options);
            return controller;
        }
    };
    const elements = {
        createdFrom: {id: "created-from"},
        createdTo: {id: "created-to"},
        dateRangeError: {id: "review-date-range-error"}
    };
    const applyFilters = () => {};

    const result = createReviewDateRange(pickerApi, elements, applyFilters);

    assert.equal(result, controller);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].fromInput, elements.createdFrom);
    assert.equal(calls[0].toInput, elements.createdTo);
    assert.equal(calls[0].errorElement, elements.dateRangeError);
    assert.equal(calls[0].onCommit, applyFilters);
});

test("canonical date-time value is treated as UTC without timezone conversion", () => {
    assert.equal(toUtcIso("2026-08-03T02:00"), "2026-08-03T02:00:00Z");
});

test("tri-state select value becomes an optional boolean filter", () => {
    assert.equal(toOptionalBoolean(""), null);
    assert.equal(toOptionalBoolean("true"), true);
    assert.equal(toOptionalBoolean("false"), false);
});

test("token id becomes an optional exact integer filter", () => {
    assert.equal(toOptionalLong(""), null);
    assert.equal(toOptionalLong("37"), 37);
});

test("review filters survive a page navigation through session storage", () => {
    const values = new Map();
    const storage = {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value)
    };
    const filters = {
        createdFrom: "2026-08-03T02:00",
        createdTo: "2026-08-03T04:00",
        tokenId: "37",
        sessionId: "39_session",
        gameCode: "bj_igt",
        notification: "false"
    };

    writeStoredFilters(storage, "review-filters", filters);

    assert.deepEqual(readStoredFilters(storage, "review-filters"), filters);
});

test("image scale survives screenshot and page navigation", () => {
    const values = new Map();
    const storage = {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value)
    };

    writeStoredScale(storage, "review-scale", 0.8);

    assert.equal(readStoredScale(storage, "review-scale"), 0.8);
});

test("queue range date is always formatted in UTC", () => {
    assert.equal(
        formatUtcDate("2026-08-02T23:00:00Z"),
        "02/08/2026, 23:00:00 UTC"
    );
});
