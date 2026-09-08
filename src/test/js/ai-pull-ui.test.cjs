const test = require('node:test');
const assert = require('node:assert/strict');
const {aiResultText, aiFilterValues} = require('../../main/resources/static/js/ai-result.js');
const {rulePayload} = require('../../main/resources/static/js/ai-queue.js');
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
test('rule dates are UTC and absent predicates stay null', () => {
    const rule = rulePayload({name:' Example ', enabled:true, priority:'10', createdFrom:'2026-09-03T10:15',
        createdTo:'', tokenId:'0', sessionId:'', notification:'false', hasUserHand:''});
    assert.equal(rule.createdFrom, '2026-09-03T10:15:00Z');
    assert.equal(rule.tokenId, 0);
    assert.equal(rule.notification, false);
    assert.equal(rule.hasUserHand, null);
    assert.equal(rule.name, 'Example');
});
