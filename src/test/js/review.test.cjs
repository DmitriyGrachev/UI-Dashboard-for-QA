const test = require("node:test");
const assert = require("node:assert/strict");

const {
    createImagePreload,
    createImageAvailabilityRetryUrl,
    createImageRetryPlan,
    createImageAvailabilityUrl,
    isLoginRedirect,
    imageAvailabilityAction,
    createReviewDateRange,
    formatUtcDate,
    loadClaimThenSummary,
    prepareViewForNextItem,
    readStoredScale,
    readStoredFilters,
    reviewActionsDisabled,
    scheduleLatestTimer,
    toUtcIso,
    toOptionalLong,
    toOptionalBoolean,
    writeStoredScale,
    writeStoredFilters
} = require("../../main/resources/static/js/review.js");

test("preload reuses the same image without assigning src a second time", () => {
    const images = [];
    const preload = createImagePreload(() => {
        const image = {sources: [], removeAttribute(name) { if (name === "src") this.cancelled = true; },
            set src(value) { this.sources.push(value); }};
        images.push(image);
        return image;
    });
    preload.start("next", "/api/images/next/content");
    preload.start("next", "/api/images/next/content");
    assert.equal(images.length, 1);
    assert.equal(preload.take("next"), images[0]);
    assert.deepEqual(images[0].sources, ["/api/images/next/content"]);
    assert.equal(images[0].cancelled, undefined);
    assert.equal(preload.take("next"), null);
});

test("mismatch, empty hints, and clear discard the one speculative download", () => {
    const images = [];
    const preload = createImagePreload(() => {
        const image = {removeAttribute() { this.cancelled = true; }};
        images.push(image);
        return image;
    });
    preload.start("a", "/a");
    assert.equal(preload.take("b"), null);
    assert.equal(images[0].cancelled, true);
    preload.start("b", "/b");
    preload.start("c", "/c");
    assert.equal(images[1].cancelled, true);
    preload.clear();
    assert.equal(images[2].cancelled, true);
    preload.start(null, null);
    preload.start(undefined, undefined);
    assert.equal(images.length, 3);
});

test("a late preload error cannot cancel its replacement or an adopted image", () => {
    const images = [];
    const preload = createImagePreload(() => {
        const image = {removeAttribute() { this.cancelled = true; }};
        images.push(image);
        return image;
    });
    preload.start("a", "/a");
    const lateError = images[0].onerror;
    preload.start("b", "/b");
    lateError();
    assert.equal(preload.take("b"), images[1]);
    assert.equal(images[1].onerror, null);
    assert.equal(images[1].cancelled, undefined);
});

test("a failed preload is discarded so the real assignment can use normal retries", () => {
    const image = {removeAttribute() { this.cancelled = true; }};
    const preload = createImagePreload(() => image);
    preload.start("a", "/a");
    image.onerror();
    assert.equal(preload.take("a"), null);
    assert.equal(image.cancelled, true);
});


test("queue summary starts only after the screenshot claim is rendered", async () => {
    const events = [];
    let finishSummary;
    const summaryFinished = new Promise(resolve => {
        finishSummary = resolve;
    });

    const loaded = await loadClaimThenSummary(
        async () => {
            events.push("claim");
            return true;
        },
        async () => {
            events.push("summary");
            await summaryFinished;
        },
        true
    );

    assert.equal(loaded, true);
    assert.deepEqual(events, ["claim", "summary"]);
    finishSummary();
});

test("rapid automatic filter changes run only the latest request", () => {
    const timers = new Map();
    const cleared = [];
    let nextId = 1;
    const timerApi = {
        setTimeout(action) {
            const id = nextId++;
            timers.set(id, action);
            return id;
        },
        clearTimeout(id) {
            cleared.push(id);
            timers.delete(id);
        }
    };
    const applied = [];

    const first = scheduleLatestTimer(timerApi, null, () => applied.push("first"), 400);
    const second = scheduleLatestTimer(timerApi, first, () => applied.push("second"), 400);
    timers.get(second)();

    assert.deepEqual(cleared, [first]);
    assert.deepEqual(applied, ["second"]);
});

test("image loading retries twice with a fresh broker URL", () => {
    assert.deepEqual(
        createImageRetryPlan("/api/images/image-1/content", 0),
        {
            attempt: 1,
            delayMs: 250,
            url: "/api/images/image-1/content?_imageRetry=1"
        }
    );
    assert.deepEqual(
        createImageRetryPlan("/api/images/image-1/content?download=true", 1),
        {
            attempt: 2,
            delayMs: 1000,
            url: "/api/images/image-1/content?download=true&_imageRetry=2"
        }
    );
    assert.equal(createImageRetryPlan("/api/images/image-1/content", 2), null);
});

test("image availability uses an encoded same-origin endpoint", () => {
    assert.equal(
        createImageAvailabilityUrl("image/1 with spaces"),
        "/api/images/image%2F1%20with%20spaces/availability"
    );
    assert.equal(
        createImageAvailabilityRetryUrl("/api/images/image-1/content?source=cloud"),
        "/api/images/image-1/content?source=cloud&_imageRetry=availability"
    );
});

test("availability statuses select retry, advance, or hold", () => {
    assert.equal(imageAvailabilityAction(204), "retry");
    assert.equal(imageAvailabilityAction(404), "advance");
    assert.equal(imageAvailabilityAction(503), "hold");
});

test("login redirects are recognized after fetch follows a 302", () => {
    assert.equal(
        isLoginRedirect({redirected: true, url: "https://app.example/login"}),
        true
    );
    assert.equal(
        isLoginRedirect({redirected: true, url: "https://app.example/review"}),
        false
    );
});

test("review decisions stay disabled until the screenshot is visible", () => {
    assert.equal(reviewActionsDisabled({busy: false, item: {imageId: "1"}, imageReady: false}), true);
    assert.equal(reviewActionsDisabled({busy: false, item: {imageId: "1"}, imageReady: true}), false);
    assert.equal(reviewActionsDisabled({busy: true, item: {imageId: "1"}, imageReady: true}), true);
    assert.equal(reviewActionsDisabled({busy: false, item: null, imageReady: true}), true);
});

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

test("next screenshot keeps the operator image position", () => {
    const view = {
        x: 42,
        y: -180,
        scale: 0.5,
        dragging: true
    };

    prepareViewForNextItem(view);

    assert.deepEqual(view, {
        x: 42,
        y: -180,
        scale: 0.5,
        dragging: false
    });
});

test("queue range date is always formatted in UTC", () => {
    assert.equal(
        formatUtcDate("2026-08-02T23:00:00Z"),
        "02/08/2026, 23:00:00 UTC"
    );
});
