const test = require("node:test");
const assert = require("node:assert/strict");

const {
    buildSearchParams,
    createTechnicalReport,
    formatUtcDate,
    keyboardAction,
    presetRange,
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
