const test = require('node:test');
const assert = require('node:assert/strict');
const {aiResultText, aiFilterValues} = require('../../main/resources/static/js/ai-result.js');
const {rulePayload} = require('../../main/resources/static/js/ai-queue.js');
const {buildSearchParams} = require('../../main/resources/static/js/admin-screenshots.js');

test('AI query filters survive cursor pagination including certainty zero', () => {
    const params = buildSearchParams({aiResult:'UNMATCHED', certaintyFrom:0, certaintyTo:50},
        {createdAt:'2026-09-03T00:00:00Z', id:'abc'});
    assert.equal(params.get('aiResult'), 'UNMATCHED');
    assert.equal(params.get('certaintyFrom'), '0');
    assert.equal(params.get('certaintyTo'), '50');
    assert.equal(params.get('cursorId'), 'abc');
});

test('unknown percentages remain unknown and unchecked does not imply mismatch', () => {
    assert.equal(aiResultText(null), 'AI: Unchecked');
    assert.match(aiResultText({status: 'PROCESSING'}), /Unchecked/);
    assert.match(aiResultText({status: 'COMPLETED', valid: false, certainty: null, confidence: 0}), /certainty: — · confidence: 0%/);
    assert.match(aiResultText({status: 'COMPLETED', valid: true, message: '<script>test</script>'}), /<script>test<\/script>/);
});
test('empty AI filters are null; zero certainty is retained', () => {
    assert.deepEqual(aiFilterValues('', '', ''), {aiResult: null, certaintyFrom: null, certaintyTo: null});
    assert.equal(aiFilterValues('MATCHED', '0', '100').certaintyFrom, 0);
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
