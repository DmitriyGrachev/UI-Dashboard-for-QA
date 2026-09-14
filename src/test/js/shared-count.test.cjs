const {test} = require('node:test');
const assert = require('node:assert/strict');
const {startSharedCountPolling} = require('../../main/resources/static/js/review.js');
const fs = require('node:fs');
const vm = require('node:vm');
const review = require('../../main/resources/static/js/review.js');

// Exercise the existing request/render functions together without exporting internals for tests.
function countPage() {
    const source = fs.readFileSync(require.resolve('../../main/resources/static/js/review.js'), 'utf8');
    const section = (from, to) => source.slice(source.indexOf(from), source.indexOf(to));
    const element = () => ({textContent: '—', title: '', removeAttribute(name) { delete this[name]; }});
    const pending = [], redirects = [];
    const context = {
        ...review, AbortController, console: {warn() {}},
        document: {hidden: false}, window: {location: {replace: url => redirects.push(url)}},
        state: {summaryController: null, remaining: null}, remainingCountEnabled: true,
        elements: {remainingCount: element(), remainingStatus: element(), queueOldestDate: element(), queueNewestDate: element()},
        filter: {sessionId: 'a'}, requestHeaders: () => ({}), claim: async () => true,
        filters: () => context.filter,
        fetch: (_url, options) => new Promise((resolve, reject) => pending.push({options, resolve, reject}))
    };
    vm.runInNewContext([
        section('    function cancelQueueSummary()', '    async function decide('),
        section('    async function errorMessage(', '    function renderItem('),
        section('    function updateRemaining(', '    function clearMetadata(')
    ].join('\n'), context);
    return {...context, pending, redirects, setFilter: value => { context.filter = value; }};
}

function response(payload, status = 200, type = 'application/json') {
    return {ok: status >= 200 && status < 300, status, headers: {get: () => type}, json: async () => payload};
}

const settled = () => new Promise(resolve => setImmediate(resolve));

test('shared count polls visible tabs and refreshes when a hidden tab returns', () => {
    let tick, onVisibility, calls = 0, cleared = false;
    const doc = {hidden:false, addEventListener:(_, fn) => onVisibility=fn,
        removeEventListener:(_, fn) => assert.equal(fn, onVisibility)};
    const timers = {setInterval:(fn, ms) => {tick=fn; assert.equal(ms, 5000); return 1;},
        clearInterval:id => {assert.equal(id, 1); cleared=true;}};
    const stop = startSharedCountPolling(() => calls++, doc, timers);
    tick();
    doc.hidden=true; tick(); onVisibility();
    assert.equal(calls, 1);
    doc.hidden=false; onVisibility();
    assert.equal(calls, 2);
    stop(); assert.equal(cleared, true);
});

test('a late response from a replaced filter cannot overwrite the new count', async () => {
    const page = countPage();
    const oldRequest = page.refreshQueueSummary();
    page.setFilter({sessionId: 'b'});
    await page.loadQueue({replaceCurrent: true});
    assert.equal(page.pending.length, 2);
    assert.equal(page.pending[0].options.signal.aborted, true);
    assert.equal(JSON.parse(page.pending[1].options.body).sessionId, 'b');
    assert.equal(page.elements.remainingCount.textContent, '—');
    page.pending[0].resolve(response({remaining: 999})); // Some transports finish despite cancellation.
    await oldRequest;
    assert.equal(page.elements.remainingCount.textContent, '—');
    await page.refreshQueueSummary();
    assert.equal(page.pending.length, 2, 'old finally must not unlock the current request');
    page.pending[1].resolve(response({remaining: 7, asOf: '2026-09-14T10:00:00Z'}));
    await settled();
    assert.equal(page.elements.remainingCount.textContent, '7');
    assert.match(page.elements.remainingCount.title, /10:00:00 UTC/);
});

test('loading, refresh failure, and recovery preserve a useful count without duplicate requests', async () => {
    const page = countPage();
    let request = page.refreshQueueSummary();
    await page.refreshQueueSummary();
    assert.equal(page.pending.length, 1);
    page.pending[0].resolve(response({remaining: null, refreshing: true}, 202));
    await request;
    assert.equal(page.elements.remainingCount.textContent, '—');
    assert.equal(page.elements.remainingStatus.textContent, 'Updating…');
    request = page.refreshQueueSummary();
    page.pending[1].resolve(response({remaining: 10, refreshing: false, failed: false}));
    await request;
    request = page.refreshQueueSummary();
    page.pending[2].resolve(response({detail: 'Database unavailable'}, 503));
    await request;
    assert.equal(page.elements.remainingCount.textContent, '10');
    assert.equal(page.elements.remainingStatus.textContent, 'Count unavailable; retrying…');
    request = page.refreshQueueSummary();
    page.pending[3].resolve(response({remaining: 12, refreshing: false, failed: false}));
    await request;
    assert.equal(page.elements.remainingCount.textContent, '12');
    assert.equal(page.elements.remainingStatus.textContent, 'Shared count');
});

test('hidden pages skip counts and an expired session redirects instead of showing a false zero', async () => {
    const page = countPage();
    page.document.hidden = true;
    await page.refreshQueueSummary();
    assert.equal(page.pending.length, 0);
    page.document.hidden = false;
    const request = page.refreshQueueSummary();
    page.pending[0].resolve(response({}, 401));
    await request;
    assert.deepEqual(page.redirects, ['/login?expired']);
    assert.equal(page.elements.remainingCount.textContent, '—');
});
