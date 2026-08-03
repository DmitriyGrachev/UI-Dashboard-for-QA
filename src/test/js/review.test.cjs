const test = require("node:test");
const assert = require("node:assert/strict");

const {
    formatUtcDate,
    readStoredScale,
    readStoredFilters,
    toUtcIso,
    writeStoredScale,
    writeStoredFilters
} = require("../../main/resources/static/js/review.js");

test("datetime-local value is treated as UTC without timezone conversion", () => {
    assert.equal(toUtcIso("2026-08-03T02:00"), "2026-08-03T02:00:00Z");
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
