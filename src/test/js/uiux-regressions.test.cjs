const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const read = name => fs.readFileSync(require.resolve(`../../main/resources/static/js/${name}.js`), 'utf8');
const {keyboardAction} = require('../../main/resources/static/js/admin-screenshots.js');

test('decision entry point cannot submit an unseen image', async () => {
    const source = read('review');
    const body = source.slice(source.indexOf('async function decide('), source.indexOf('async function errorMessage('));
    let requests = 0;
    const decide = vm.runInNewContext(`(${body.trim()})`, {
        state: {item: {imageId: 'unseen'}, busy: false, imageReady: false},
        reviewActionsDisabled: value => value.busy || !value.item || !value.imageReady,
        cancelQueueSummary() {}, setBusy() {}, requestHeaders: () => ({}), filters: () => ({}),
        fetch: async () => { requests++; return {}; }, responsePayload: async () => null,
        elements: {decisionMessage: {}}
    });
    await decide('ACCEPTED');
    assert.equal(requests, 0);
});

test('review shortcuts respect browser modifiers, dialogs and editable targets', () => {
    const source = read('review');
    const body = source.slice(source.indexOf('document.addEventListener("keydown", event => {'), source.indexOf('setFiltersCollapsed(storedFiltersCollapsed()'));
    let handler, modal = false;
    const decisions = [];
    vm.runInNewContext(body, {
        document: {addEventListener: (_, fn) => { handler = fn; }, querySelector: () => modal},
        elements: {stage: {}}, state: {},
        decide: decision => decisions.push(decision), resetView() {}, zoom() {}, applyTransform() {}
    });
    const event = {key: 'a', target: {tagName: 'BODY'}, preventDefault() {}};
    for (const flag of ['ctrlKey', 'metaKey', 'altKey', 'repeat', 'defaultPrevented', 'shiftKey']) handler({...event, [flag]: true});
    handler({...event, target: {tagName: 'DIV', isContentEditable: true}});
    modal = true;
    handler(event);
    assert.deepEqual(decisions, []);
    modal = false;
    handler(event);
    assert.deepEqual(decisions, ['ACCEPTED']);
});

test('viewer leaves modified browser shortcuts alone', () => {
    for (const flag of ['ctrlKey', 'metaKey', 'altKey', 'repeat', 'defaultPrevented']) {
        for (const key of ['d', 'f', '+', 'ArrowLeft']) assert.equal(keyboardAction({key, [flag]: true}), null);
    }
});

test('restoring selection does not filter the search by its ID', () => {
    const source = read('admin-screenshots');
    const body = source.slice(source.indexOf('function restoreFromUrl('), source.indexOf('function ensureRuleOption('));
    const fields = {};
    const elements = new Proxy(fields, {get: (target, name) => target[name] ||= {value: ''}});
    const restore = vm.runInNewContext(`(${body.trim()})`, {
        URLSearchParams, elements, ensureRuleOption() {},
        setInputFromQuery: (input, params, name, fallback = '') => { input.value = params.get(name) || fallback; },
        writeBoundary: (input, value) => { input.value = value || ''; }, updateCheckedOnlyControls() {}
    });
    assert.equal(restore(new URLSearchParams('reviewState=ALL&selected=image-12')), 'image-12');
    assert.equal(elements.imageId.value, '');
    restore(new URLSearchParams('imageId=explicit-filter&selected=image-12'));
    assert.equal(elements.imageId.value, 'explicit-filter');
});
