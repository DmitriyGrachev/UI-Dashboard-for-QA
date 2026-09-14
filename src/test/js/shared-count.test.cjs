const {test} = require('node:test');
const assert = require('node:assert/strict');
const {startSharedCountPolling} = require('../../main/resources/static/js/review.js');

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
