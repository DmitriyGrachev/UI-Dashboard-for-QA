const test = require('node:test');
const assert = require('node:assert/strict');
const {aiResultText, aiFilterValues} = require('../../main/resources/static/js/ai-result.js');
const {buildSearchParams} = require('../../main/resources/static/js/admin-screenshots.js');

test('AI state survives cursor pagination without retired certainty filters', () => {
    const params = buildSearchParams({aiResult:'CHECKED', certaintyFrom:0, certaintyTo:50},
        {createdAt:'2026-09-03T00:00:00Z', id:'abc'});
    assert.equal(params.get('aiResult'), 'CHECKED');
    assert.equal(params.has('certaintyFrom'), false);
    assert.equal(params.has('certaintyTo'), false);
    assert.equal(params.get('cursorId'), 'abc');
});

test('unknown percentages remain unknown and unchecked does not imply mismatch', () => {
    assert.equal(aiResultText(null), 'AI: Unchecked');
    assert.match(aiResultText({status: 'PROCESSING'}), /Unchecked/);
    assert.match(aiResultText({status: 'COMPLETED', valid: false, certainty: null, confidence: 0}), /certainty: — · confidence: 0%/);
    assert.match(aiResultText({status: 'COMPLETED', valid: true, message: '<script>test</script>'}), /<script>test<\/script>/);
});
test('AI filtering defaults to all and sends only the selected AI state', () => {
    assert.deepEqual(aiFilterValues(''), {aiResult: null});
    for (const state of ['CHECKED', 'MATCHED', 'UNMATCHED', 'UNCHECKED']) {
        assert.deepEqual(aiFilterValues(state), {aiResult: state});
    }
});
test('AI verdict and confidence filters are sent to the screenshot search', () => {
    const params = buildSearchParams({aiVerdict:'LOW_CONFIDENCE', confidenceFrom:20, confidenceTo:60});
    assert.equal(params.get('aiVerdict'), 'LOW_CONFIDENCE');
    assert.equal(params.get('confidenceFrom'), '20');
    assert.equal(params.get('confidenceTo'), '60');
});

test('AI card distinguishes uncertainty, failure, mismatch and zero confidence', () => {
    const {aiPresentation} = require('../../main/resources/static/js/ai-result.js');
    assert.equal(aiPresentation(null).label, 'Not checked');
    assert.equal(aiPresentation({status:'PROCESSING'}).label, 'Assigned to AI');
    assert.equal(aiPresentation({status:'FAILED'}).label, 'Check failed');
    assert.equal(aiPresentation({status:'COMPLETED', verdict:'LOW_CONFIDENCE'}).tone, 'warning');
    assert.equal(aiPresentation({status:'COMPLETED', verdict:'MISMATCH'}).tone, 'danger');
    assert.equal(aiPresentation({status:'COMPLETED', verdict:'MATCH', confidence:0}).confidence, 0);
});

function testDocument() {
    const doc = {activeElement: null, createElement: tag => ({tagName: tag, ownerDocument: doc,
        children: [], dataset: {}, attributes: {}, textContent: '',
        append(...children) { this.children.push(...children); },
        replaceChildren(...children) { this.children = children; },
        setAttribute(name, value) { this.attributes[name] = value; },
        addEventListener() {}, focus() { doc.activeElement = this; }
    })};
    return doc;
}

test('AI diagnostics show assignment attempts even before a result or error exists', () => {
    const {renderAiResult} = require('../../main/resources/static/js/ai-result.js');
    const container = testDocument().createElement('section');
    for (const [status, attemptCount] of [['PENDING', 0], ['PROCESSING', 2], ['FAILED', 3], ['COMPLETED', 1]]) {
        renderAiResult(container, {status, attemptCount, verdict: 'MISMATCH', lastErrorMessage: '<img onerror=alert(1)>'});
        const details = container.children.find(node => node.tagName === 'details');
        assert.ok(details, status);
        assert.ok(details.children.some(node => node.textContent === `Assignment attempts: ${attemptCount}`), status);
        assert.equal(details.children[0].tagName, 'summary');
        assert.equal(details.children[0].textContent, 'Check details');
        assert.ok(container.children.every(node => node.tagName !== 'img'));
    }
    renderAiResult(container, null);
    assert.equal(container.children.some(node => node.tagName === 'details'), false);
});

test('screenshot list and grid distinguish operator and AI states without extra requests', () => {
    const source = require('node:fs').readFileSync(require.resolve('../../main/resources/static/js/admin-screenshots.js'), 'utf8');
    const code = source.slice(source.indexOf('    function storageLabel('), source.indexOf('    function updateNavigation('));
    for (const view of ['list', 'grid']) {
        const document = testDocument();
        const results = document.createElement('div');
        const items = [null, 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'].map((aiStatus, i) => ({
            imageId: String(i), aiStatus, reviewState: i === 1 ? 'CHECKED' : 'UNCHECKED',
            fileName: 'screenshot.png', gameCode: 'bj_igt', storageState: 'LOCAL_ONLY'
        }));
        const state = {items, selectedIndex: 2};
        const focused = document.createElement('button');
        focused.dataset.index = '2';
        focused.closest = () => focused;
        document.activeElement = focused;
        require('node:vm').runInNewContext(code + '\nrenderResults();', {
            document, state, elements: {results}, byId: () => ({value: view}),
            formatUtcDate: () => '14/09/2026 UTC', selectResult() {}
        });
        assert.equal(results.children.length, 5);
        for (const [i, row] of results.children.entries()) {
            const statuses = row.children.find(node => node.className === 'screenshot-result-statuses');
            assert.ok(statuses, view);
            assert.equal(statuses.children[0].textContent, i === 1 ? 'Operator: Checked' : 'Operator: Unchecked');
            assert.equal(statuses.children[1].textContent, ['AI: Not checked', 'AI: Not checked', 'AI: Assigned', 'AI: Checked', 'AI: Failed'][i]);
            assert.equal(row.attributes['aria-pressed'], String(i === 2));
            assert.equal(row.children.filter(node => node.tagName === 'img').length, view === 'grid' ? 1 : 0);
        }
        assert.equal(document.activeElement, results.children[2]);
    }
});
