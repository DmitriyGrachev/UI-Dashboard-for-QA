const test = require("node:test");
const assert = require("node:assert/strict");

const {
    buildSearchParams,
    copyShareLink,
    createTechnicalReport,
    formatUtcDate,
    formatLoadedCount,
    keyboardAction,
    loadPageThenSummary,
    nextSearchSequence,
    presetRange,
    resolveSearchFilters,
    storageSupportsTemporaryLink
} = require("../../main/resources/static/js/admin-screenshots.js");

test("search parameters keep exact filters and omit empty values", () => {
    const params = buildSearchParams({
        createdFrom: "2026-08-27T10:00",
        createdTo: "2026-08-28T10:00",
        reviewState: "CHECKED",
        gameCode: "bj_americas_ags",
        tokenId: "32",
        sessionId: " 37_session ",
        imageId: "",
        fileName: "bug.png",
        decision: "REJECTED",
        reviewedBy: " Daria ",
        storageState: "B2_ONLY",
        parseStatus: "ERROR",
        notification: "false",
        hasUserHand: "true"
    });

    assert.equal(params.get("createdFrom"), "2026-08-27T10:00:00Z");
    assert.equal(params.get("createdTo"), "2026-08-28T10:00:00Z");
    assert.equal(params.get("reviewState"), "CHECKED");
    assert.equal(params.get("tokenId"), "32");
    assert.equal(params.get("sessionId"), "37_session");
    assert.equal(params.get("decision"), "REJECTED");
    assert.equal(params.get("reviewedBy"), "Daria");
    assert.equal(params.get("notification"), "false");
    assert.equal(params.has("imageId"), false);
});

test("unchecked search never sends checked-only filters", () => {
    const params = buildSearchParams({
        reviewState: "UNCHECKED",
        decision: "REJECTED",
        reviewedBy: "Andrey"
    });

    assert.equal(params.get("reviewState"), "UNCHECKED");
    assert.equal(params.has("decision"), false);
    assert.equal(params.has("reviewedBy"), false);
});

test("quick ranges use UTC boundaries without browser timezone conversion", () => {
    assert.deepEqual(
        presetRange(new Date("2026-08-28T12:34:56Z"), {hours: 24}),
        {from: "2026-08-27T12:35", to: "2026-08-28T12:35"}
    );
});

test("keyboard shortcuts ignore form controls", () => {
    assert.equal(keyboardAction({key: "ArrowRight", tagName: "BODY"}), "next");
    assert.equal(keyboardAction({key: "d", tagName: "DIV"}), "download");
    assert.equal(keyboardAction({key: "0", tagName: "BODY"}), "resetZoom");
    assert.equal(keyboardAction({key: "d", tagName: "INPUT"}), null);
});

test("technical report is concise and includes review and parsing data", () => {
    const report = createTechnicalReport({
        imageId: "abc",
        fileName: "case.png",
        gameCode: "bj_americas_ags",
        tokenId: 32,
        sessionId: "32_session",
        fileCreatedAt: "2026-08-28T10:00:00Z",
        dealerCards: "Eight",
        activeUserCards: "Three_Three_Ten",
        inactiveUserCards: null,
        buttonsRaw: "bSbH",
        notification: false,
        parseStatus: "SUCCESS",
        reviewState: "CHECKED",
        decision: "REJECTED",
        reviewedBy: "Daria",
        reviewedAt: "2026-08-28T11:00:00Z",
        storageState: "B2_ONLY"
    });

    assert.match(report, /File: case\.png/);
    assert.match(report, /Active hand: Three_Three_Ten/);
    assert.match(report, /Review: CHECKED · REJECTED · Daria/);
    assert.match(report, /Storage: B2_ONLY/);
});

test("temporary links are offered only for current cloud copies", () => {
    assert.equal(storageSupportsTemporaryLink("B2_ONLY"), true);
    assert.equal(storageSupportsTemporaryLink("BOTH"), true);
    assert.equal(storageSupportsTemporaryLink("LOCAL_ONLY"), false);
    assert.equal(storageSupportsTemporaryLink("MISSING"), false);
});

test("UTC dates are formatted consistently", () => {
    assert.equal(formatUtcDate(null), "—");
    assert.match(formatUtcDate("2026-08-28T10:00:00Z"), /28\/08\/2026/);
    assert.match(formatUtcDate("2026-08-28T10:00:00Z"), /UTC/);
});

test("page results render before the independent summary starts", async () => {
    const events = [];
    const page = {items: [{imageId: "first"}], nextCreatedAt: null, nextId: null};

    const result = await loadPageThenSummary(
        async () => {
            events.push("page");
            return page;
        },
        async () => events.push("summary")
    );

    assert.equal(result, page);
    assert.deepEqual(events, ["page", "summary"]);
});

test("a summary startup failure never discards an already loaded page", async () => {
    const page = {items: [{imageId: "first"}], nextCreatedAt: null, nextId: null};

    const result = await loadPageThenSummary(
        async () => page,
        () => {
            throw new Error("summary unavailable");
        }
    );

    assert.equal(result, page);
});

test("loaded count distinguishes browser rows from the matching total", () => {
    assert.equal(formatLoadedCount(50, null), "50 loaded");
    assert.equal(formatLoadedCount(50, 1_500_000), "Loaded 50 of 1,500,000");
});

test("copy link writes the exact current explorer URL", async () => {
    const copied = [];
    await copyShareLink(
        {writeText: async value => copied.push(value)},
        "https://validator.example/admin/screenshots?reviewState=CHECKED&selected=abc"
    );

    assert.deepEqual(copied, [
        "https://validator.example/admin/screenshots?reviewState=CHECKED&selected=abc"
    ]);
});

test("loading another page keeps the active summary generation", () => {
    assert.equal(nextSearchSequence(7, true), 7);
    assert.equal(nextSearchSequence(7, false), 8);
});

test("loading another page keeps the filters of the applied search", () => {
    const applied = {gameCode: "bj_igt", reviewState: "CHECKED"};
    const edited = {gameCode: "bj_playtech", reviewState: "UNCHECKED"};

    assert.equal(resolveSearchFilters(edited, applied, true), applied);
    assert.equal(resolveSearchFilters(edited, applied, false), edited);
});
