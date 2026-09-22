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
const {filterStatus} = require("../../main/resources/static/js/admin-screenshots.js");

test('session links use only exact game and session, with safe encoding', () => {
    const {sessionExplorerUrl} = require('../../main/resources/static/js/admin-screenshots.js');
    assert.equal(sessionExplorerUrl({gameCode:'bj_igt', sessionId:'table A&B', imageId:'x', createdFrom:'old'}),
        '/admin/screenshots?gameCode=bj_igt&sessionId=table+A%26B');
    for (const details of [null, {}, {gameCode:'bj_igt',sessionId:' '}, {sessionId:'s'}]) assert.equal(sessionExplorerUrl(details), null);
});

test('saved relative ranges resolve in UTC on application, retaining legacy exact dates', () => {
    const {resolveSavedFilter} = require('../../main/resources/static/js/admin-screenshots.js');
    const legacy = {query: 'createdFrom=2026-09-01T00%3A00%3A00Z&sessionId=a'};
    assert.equal(resolveSavedFilter(legacy).toString(), legacy.query);
    for (const field of ['created', 'reviewed']) {
        const saved = {query: 'confidenceFrom=0&createdFrom=old&reviewedTo=old', relativeDate: {preset: 'today', field}};
        const resolved = resolveSavedFilter(saved, new Date('2026-10-01T00:00:10Z'));
        assert.equal(resolved.get(field + 'From'), '2026-10-01T00:00:00Z');
        assert.equal(resolved.get(field + 'To'), '2026-10-02T00:00:00Z');
        assert.equal(resolved.get('confidenceFrom'), '0');
        assert.equal(resolved.has((field === 'created' ? 'reviewed' : 'created') + 'From'), false);
        saved.relativeDate.preset = 'yesterday';
        assert.equal(resolveSavedFilter(saved, new Date('2026-10-01T00:00Z')).get(field + 'From'), '2026-09-30T00:00:00Z');
        saved.relativeDate.preset = 'last24h';
        assert.equal(resolveSavedFilter(saved, new Date('2026-10-01T00:00Z')).get(field + 'To'), '2026-10-01T00:00:00Z');
    }
    assert.throws(() => resolveSavedFilter({query: '', relativeDate: {preset: 'invalid', field: 'created'}}));
    assert.throws(() => resolveSavedFilter({query: 'reviewState=UNCHECKED', relativeDate: {preset: 'today', field: 'reviewed'}}));
});

test('CSV uses all current search filters without page size or cursor', () => {
    const {csvExportUrl} = require('../../main/resources/static/js/admin-screenshots.js');
    const filters = {gameCode: 'bj_igt', sessionId: 'a & b', aiResult: 'CHECKED', confidenceFrom: '0',
        confidenceTo: '95', reviewedFrom: '2026-09-15T00:00', notification: false, tokenId: 0};
    const expected = buildSearchParams(filters);
    expected.delete('limit');
    const actual = new URL(csvExportUrl(filters), 'http://localhost');
    assert.equal(actual.pathname, '/admin/api/screenshots/export.csv');
    assert.equal(actual.searchParams.toString(), expected.toString());
    assert.equal(actual.searchParams.has('cursorId'), false);
    assert.equal(actual.searchParams.has('limit'), false);
});

test("restoring selection keeps the original search scope", () => {
    const source = require('node:fs').readFileSync(require.resolve('../../main/resources/static/js/admin-screenshots.js'), 'utf8');
    const restore = source.slice(source.indexOf('function restoreFromUrl('), source.indexOf('function ensureRuleOption('));
    const elements = new Proxy({}, {get: (target, name) => target[name] ||= {value: ''}});
    const restoreFromUrl = require('node:vm').runInNewContext(`(${restore.trim()})`, {
        elements, URLSearchParams, ensureRuleOption() {},
        window: {location: {search: '?sessionId=session-a&selected=image-51'}},
        writeBoundary: (input, value) => { input.value = value; },
        setInputFromQuery: (input, params, name, fallback = '') => { input.value = params.get(name) ?? fallback; },
        updateCheckedOnlyControls() {}
    });
    assert.equal(restoreFromUrl(), 'image-51');
    const params = buildSearchParams({imageId: elements.imageId.value, sessionId: elements.sessionId.value});
    assert.equal(params.get('imageId'), null);
    assert.equal(params.get('sessionId'), 'session-a');
});

test("filter status distinguishes an edited draft from the displayed search", () => {
    assert.equal(filterStatus({gameCode: "bj_playtech"}, {gameCode: "bj_igt"}, false),
        "Changes not applied. Select Search.");
    assert.equal(filterStatus({sessionId: " 37_session ", reviewState: "ALL"}, {sessionId: "37_session"}, false),
        "Filters applied.");
    assert.equal(filterStatus({}, {}, true), "Searching…");
    assert.equal(filterStatus({}, null, false), "Select Search to load screenshots.");
});

test("viewer shortcuts do not hijack a focused disclosure", () => {
    assert.equal(keyboardAction({key: "ArrowRight", tagName: "SUMMARY"}), null);
});

test("segmented operator review sends its selected value and clears checked-only filters", () => {
    const source = require('node:fs').readFileSync(require.resolve('../../main/resources/static/js/admin-screenshots.js'), 'utf8');
    const bindings = source.slice(source.indexOf('const elements ='), source.indexOf('const detailElements ='));
    const filters = source.slice(source.indexOf('function currentFilters()'), source.indexOf('function updateSearchControls()'));
    const fields = new Map();
    const reviewInputs = {value: 'ALL'};
    fields.set('review-state', {tagName: 'FIELDSET'});
    fields.set('screenshot-filter-form', {elements: {namedItem: name => name === 'reviewState' ? reviewInputs : null}});
    const byId = id => {
        if (!fields.has(id)) fields.set(id, {value: '', disabled: false});
        return fields.get(id);
    };
    const controller = require('node:vm').runInNewContext(`(() => {
        ${bindings}
        ${filters}
        return {currentFilters, updateCheckedOnlyControls};
    })()`, {byId});
    for (const value of ['ALL', 'CHECKED', 'UNCHECKED']) {
        reviewInputs.value = value;
        fields.get('explorer-decision').value = 'ACCEPTED';
        fields.get('explorer-reviewed-by').value = 'reviewer';
        controller.updateCheckedOnlyControls();
        const params = buildSearchParams(controller.currentFilters());
        assert.equal(params.get('reviewState'), value);
        assert.equal(fields.get('explorer-decision').disabled, value === 'UNCHECKED');
        assert.equal(fields.get('explorer-reviewed-by').disabled, value === 'UNCHECKED');
        assert.equal(params.get('decision'), value === 'UNCHECKED' ? null : 'ACCEPTED');
        assert.equal(params.get('reviewedBy'), value === 'UNCHECKED' ? null : 'reviewer');
    }
    reviewInputs.value = 'CHECKED';
    fields.get('explorer-date-field').value = 'reviewed';
    fields.get('explorer-created-from').value = '2026-09-09T00:00';
    fields.get('explorer-created-to').value = '2026-09-10T00:00';
    const reviewed = buildSearchParams(controller.currentFilters());
    assert.equal(reviewed.get('reviewedFrom'), '2026-09-09T00:00:00Z');
    assert.equal(reviewed.get('reviewedTo'), '2026-09-10T00:00:00Z');
    assert.equal(reviewed.has('createdFrom'), false);
    reviewInputs.value = 'UNCHECKED';
    controller.updateCheckedOnlyControls();
    assert.equal(fields.get('explorer-date-field').value, 'created');
});

test("finishing pagination cannot override a newer screenshot selection", async () => {
    const source = require('node:fs').readFileSync(require.resolve('../../main/resources/static/js/admin-screenshots.js'), 'utf8');
    const functionSource = source.slice(source.indexOf('async function nextResult()'), source.indexOf('function previousResult()'));
    for (const changed of [false, true]) {
        const state = {items: Array(50), selectedIndex: 49, selectionSequence: 1, nextCursor: {}};
        const selected = [];
        let finishPage;
        const page = new Promise(resolve => { finishPage = resolve; });
        const nextResult = require('node:vm').runInNewContext(`(${functionSource.trim()})`, {
            state,
            search: () => page,
            selectResult: index => selected.push(index)
        });
        const pending = nextResult();
        if (changed) {
            state.selectedIndex = 2;
            state.selectionSequence++;
        }
        state.items.push({});
        finishPage();
        await pending;
        assert.deepEqual(selected, changed ? [] : [50]);
    }
});

test("screenshot selection and navigation keep zoom until an explicit reset", async () => {
    const source = require('node:fs').readFileSync(require.resolve('../../main/resources/static/js/admin-screenshots.js'), 'utf8');
    const navigation = source.slice(source.indexOf('async function selectResult('), source.indexOf('async function openTemporaryLink('));
    const stopDragging = source.slice(source.indexOf('const stopDragging ='), source.indexOf('elements.stage.addEventListener("pointerup"'));
    const state = {items: [{imageId: 'a'}, {imageId: 'b'}, {imageId: 'c'}], selectedIndex: 0,
        selectionSequence: 0, scale: 1, x: 12, y: -7, dragging: true};
    const elements = Object.fromEntries(['image', 'zoomValue', 'fileSummary', 'viewerMessage',
        'detailContent', 'detailPlaceholder', 'detailReviewState', 'download', 'temporaryLink',
        'copyLink', 'copyReport', 'openSession', 'stage'].map(name => [name, {
        style: {}, setAttribute() {}, removeAttribute() {}, classList: {remove() {}}
    }]));
    const viewer = require('node:vm').runInNewContext(`(() => {
        ${navigation}
        ${stopDragging}
        return {selectResult, nextResult, previousResult, setScale, resetZoom};
    })()`, {
        state, elements, storageSupportsTemporaryLink,
        renderResults() {}, updateNavigation() {}, writeSearchUrl() {}, renderDetails() {},
        fetchJson: async () => ({imageUrl: '/image.png', downloadUrl: '/download', storageState: 'LOCAL_ONLY'})
    });
    viewer.setScale(1.7);
    for (const [navigate, index] of [
        [() => viewer.selectResult(2), 2],
        [() => viewer.previousResult(), 1],
        [() => viewer.nextResult(), 2]
    ]) {
        await navigate();
        assert.equal(state.selectedIndex, index);
        assert.equal(state.scale, 1.7);
        assert.equal(elements.image.style.transform, 'translate(12px, -7px) scale(1.7)');
        assert.equal(elements.zoomValue.textContent, '170%');
        assert.equal(state.dragging, false);
    }
    viewer.resetZoom();
    assert.equal(elements.image.style.transform, 'translate(0px, 0px) scale(1)');
    assert.equal(elements.zoomValue.textContent, '100%');
});

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

test("today and yesterday use whole UTC days across month and year boundaries", () => {
    const now = new Date("2026-01-01T00:15:00+03:00");
    assert.deepEqual(presetRange(now, {day: "today"}),
        {from: "2025-12-31T00:00", to: "2026-01-01T00:00"});
    assert.deepEqual(presetRange(now, {day: "yesterday"}),
        {from: "2025-12-30T00:00", to: "2025-12-31T00:00"});
});

test("review date filters are sent in UTC and omitted for unchecked screenshots", () => {
    const filters = {reviewedFrom: "2026-09-09T00:00", reviewedTo: "2026-09-10T00:00"};
    const params = buildSearchParams(filters);
    assert.equal(params.get("reviewedFrom"), "2026-09-09T00:00:00Z");
    assert.equal(params.get("reviewedTo"), "2026-09-10T00:00:00Z");
    const unchecked = buildSearchParams({...filters, reviewState: "UNCHECKED"});
    assert.equal(unchecked.has("reviewedFrom"), false);
    assert.equal(unchecked.has("reviewedTo"), false);
});

test("copied report includes AI verdict, confidence, explanation and failure details", () => {
    const report = createTechnicalReport({ai: {status: "COMPLETED", valid: false,
        verdict: "MISMATCH", confidence: 0, certainty: 95,
        message: "Dealer card differs", checkedAt: "2026-09-10T10:00:00Z"}});
    assert.match(report, /MISMATCH/);
    assert.match(report, /confidence: 0%/);
    assert.match(report, /Dealer card differs/);
    assert.match(report, /2026-09-10T10:00:00Z/);
    assert.match(createTechnicalReport({ai: {status: "FAILED", lastErrorCode: "AI_REJECTED",
        lastErrorMessage: "Unreadable image"}}), /AI_REJECTED[\s\S]*Unreadable image/);
    assert.match(createTechnicalReport({}), /AI: Unchecked/);
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

test("copy link explicitly targets its screenshot without changing the current search", async () => {
    const copied = [];
    await copyShareLink(
        {writeText: async value => copied.push(value)},
        "https://validator.example/admin/screenshots?reviewState=CHECKED&selected=abc"
    );

    assert.deepEqual(copied, [
        "https://validator.example/admin/screenshots?reviewState=CHECKED&selected=abc&imageId=abc"
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

test('saved filters persist, restore exact filter values, replace and delete without selected IDs', () => {
    const source = require('node:fs').readFileSync(require.resolve('../../main/resources/static/js/admin-screenshots.js'), 'utf8');
    const setup = source.slice(source.indexOf('    const savedSelect ='), source.indexOf('    const selectedId = restoreFromUrl();'));
    const fields = new Map();
    const byId = id => {
        if (!fields.has(id)) fields.set(id, {value:'', listeners:{}, options:[], focus(){},
            addEventListener(type, fn){this.listeners[type]=fn;}, replaceChildren(...items){this.options=items;this.value='';}, add(item){this.options.push(item);}});
        return fields.get(id);
    };
    const store = new Map();
    let restored;
    let searches = 0;
    const context = {byId, localStorage:{getItem:key=>store.get(key),setItem:(key,value)=>store.set(key,value)},
        Option:function(text,value){this.text=text;this.value=value;}, URLSearchParams,
        elements:{form:{reportValidity:()=>true}, datePreset:{value:''}, dateField:{value:'reviewed'}}, dateRange:{validate:()=>true}, state:{}, buildSearchParams,
        resolveSavedFilter: require('../../main/resources/static/js/admin-screenshots.js').resolveSavedFilter,
        currentFilters:()=>({sessionId:'session-a', aiVerdict:'MISMATCH', reviewedFrom:'2026-09-10T00:00'}),
        restoreFromUrl:params=>{restored=params;},search:()=>{searches++;}};
    const run = () => require('node:vm').runInNewContext(setup, {...context});
    run();
    byId('saved-filter-name').value='My filter';
    byId('save-filter').listeners.click();
    run();
    assert.equal(byId('saved-filter-select').options[1].text, 'My filter');
    byId('saved-filter-select').value='0';
    byId('saved-filter-select').listeners.change();
    assert.equal(restored.get('sessionId'),'session-a');
    assert.equal(restored.get('aiVerdict'),'MISMATCH');
    assert.equal(restored.get('reviewedFrom'),'2026-09-10T00:00:00Z');
    assert.equal(restored.has('selected'),false);
    assert.equal(searches,1);
    context.elements.datePreset.value = 'today';
    byId('save-filter').listeners.click();
    const relative = JSON.parse(store.values().next().value)[0];
    assert.deepEqual(relative.relativeDate, {preset:'today', field:'reviewed'});
    assert.equal(new URLSearchParams(relative.query).has('reviewedFrom'), false);
    byId('saved-filter-select').listeners.change();
    assert.equal(restored.get('reviewedFrom').slice(0,10), new Date().toISOString().slice(0,10));
    byId('save-filter').listeners.click();
    assert.equal(byId('saved-filter-select').options.length,2);
    byId('delete-filter').listeners.click();
    run();
    assert.equal(byId('saved-filter-select').options.length,1);
    context.localStorage.setItem = () => { throw new Error('Storage disabled'); };
    run();
    byId('save-filter').listeners.click();
    assert.match(byId('saved-filter-message').textContent, /not saved/);
});
