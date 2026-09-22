const test = require('node:test');
const assert = require('node:assert/strict');
const {rulePayload, sortRules, ruleSummary, settingsSnapshot, initializeAiQueue, initializeAiOperations} =
    require('../../main/resources/static/js/ai-queue.js');

class FakeElement {
    constructor(tagName, {id = '', className = '', name = '', type = 'text', value = ''} = {}) {
        this.tagName = tagName.toUpperCase();
        this.id = id;
        this.className = className;
        this.name = name;
        this.type = type;
        this.value = value;
        this.checked = false;
        this.disabled = false;
        this.hidden = false;
        this.required = false;
        this.dataset = {};
        this.children = [];
        this.parentNode = null;
        this.ownerDocument = null;
        this.listeners = new Map();
        this.attributes = {};
        this.open = false;
        this.textContent = '';
    }
    append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
    appendChild(node) {
        node.parentNode = this;
        node.ownerDocument = this.ownerDocument;
        this.children.push(node);
        return node;
    }
    replaceChildren(...nodes) {
        if (this.querySelectorAll('*').includes(this.ownerDocument?.activeElement)) {
            this.ownerDocument.activeElement = null;
        }
        this.children = [];
        nodes.forEach(node => this.appendChild(node));
    }
    get firstElementChild() { return this.children[0] || null; }
    get lastElementChild() { return this.children[this.children.length - 1] || null; }
    get elements() { return this.querySelectorAll('input, select, textarea, button'); }
    remove() {
        if (!this.parentNode) return;
        this.parentNode.children = this.parentNode.children.filter(child => child !== this);
        this.parentNode = null;
    }
    cloneNode(deep = false) {
        const copy = new FakeElement(this.tagName, {id: this.id, className: this.className,
            name: this.name, type: this.type, value: this.value});
        copy.checked = this.checked;
        copy.disabled = this.disabled;
        copy.hidden = this.hidden;
        copy.required = this.required;
        copy.dataset = {...this.dataset};
        copy.attributes = {...this.attributes};
        copy.open = this.open;
        copy.textContent = this.textContent;
        if (deep) this.children.forEach(child => copy.appendChild(child.cloneNode(true)));
        return copy;
    }
    addEventListener(type, callback) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(callback);
    }
    dispatchEvent(event) {
        event.target ||= this;
        event.preventDefault ||= (() => { event.defaultPrevented = true; });
        (this.listeners.get(event.type) || []).forEach(callback => callback(event));
        return !event.defaultPrevented;
    }
    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'hidden') this.hidden = true;
    }
    removeAttribute(name) { delete this.attributes[name]; }
    focus() { if (this.ownerDocument) this.ownerDocument.activeElement = this; }
    checkValidity() { return !this.required || Boolean(String(this.value || '').trim()); }
    matches(selector) {
        return selector.split(',').some(part => {
            const text = part.trim();
            if (text === '*') return true;
            if (text.startsWith('#')) return this.id === text.slice(1);
            if (text.startsWith('.')) return this.className.split(/\s+/).includes(text.slice(1));
            const data = text.match(/^\[data-([\w-]+)(?:="([^"]*)")?\]$/);
            if (data) {
                const key = data[1].replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
                return Object.hasOwn(this.dataset, key) && (data[2] == null || this.dataset[key] === data[2]);
            }
            const attr = text.match(/^\[([\w-]+)(?:="([^"]*)")?\]$/);
            if (attr) return this[attr[1]] != null && (attr[2] == null || String(this[attr[1]]) === attr[2]);
            const element = text.match(/^([\w-]+)(?:\.([\w-]+))?$/);
            return Boolean(element) && this.tagName === element[1].toUpperCase()
                && (!element[2] || this.className.split(/\s+/).includes(element[2]));
        });
    }
    querySelectorAll(selector) {
        const found = [];
        const visit = node => {
            node.children.forEach(child => {
                if (child.matches(selector)) found.push(child);
                visit(child);
            });
        };
        visit(this);
        return found;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    closest(selector) {
        let node = this;
        while (node) {
            if (node.matches(selector)) return node;
            node = node.parentNode;
        }
        return null;
    }
}

class FakeDocument {
    constructor(root, view) { this.root = root; this.defaultView = view; this.activeElement = null; }
    createElement(tagName) {
        const node = new FakeElement(tagName);
        node.ownerDocument = this;
        return node;
    }
    getElementById(id) { return this.root.querySelector(`#${id}`); }
    querySelector(selector) {
        if (selector === 'meta[name="_csrf"]') return {content: 'token'};
        if (selector === 'meta[name="_csrf_header"]') return {content: 'X-CSRF'};
        return this.root.querySelector(selector);
    }
    register(node) {
        node.ownerDocument = this;
        node.children.forEach(child => this.register(child));
        if (node.content?.firstElementChild) this.register(node.content.firstElementChild);
    }
}

function fakeQueueDom() {
    const view = {listeners: new Map(), confirm: () => true,
        addEventListener(type, callback) { this.listeners.set(type, callback); }};
    const root = new FakeElement('main');
    const form = new FakeElement('form', {id: 'ai-queue-form'});
    const enabled = new FakeElement('input', {id: 'ai-queue-enabled', type: 'checkbox'});
    const rules = new FakeElement('div', {id: 'ai-rules'});
    const message = new FakeElement('p', {id: 'ai-queue-message'});
    const state = new FakeElement('p', {id: 'ai-queue-state'});
    const dirty = new FakeElement('p', {id: 'ai-queue-dirty'});
    dirty.dataset.aiQueueDirty = '';
    const add = new FakeElement('button', {id: 'ai-rule-add'});
    const save = new FakeElement('button', {id: 'ai-queue-save'});
    const stop = new FakeElement('button', {id: 'ai-queue-stop'});
    const reload = new FakeElement('button', {id: 'ai-queue-reload'});
    form.append(enabled, dirty, rules, add, save, stop, reload, message, state);

    const source = new FakeElement('details', {className: 'ai-rule'});
    const ruleSummaryElement = new FakeElement('summary');
    ruleSummaryElement.dataset.ruleSummary = '';
    const fields = new FakeElement('div', {className: 'ai-rule-fields'});
    const addField = (tagName, name, type = 'text', required = false) => {
        const field = new FakeElement(tagName, {name, type});
        field.required = required;
        fields.append(field);
    };
    const position = new FakeElement('span');
    position.dataset.rulePosition = '';
    fields.append(position);
    addField('input', 'name', 'text', true);
    addField('select', 'gameCode', 'select-one', true);
    addField('input', 'enabled', 'checkbox');
    addField('input', 'createdFrom', 'datetime-local');
    addField('input', 'createdTo', 'datetime-local');
    addField('input', 'tokenId', 'number');
    addField('input', 'sessionId');
    addField('select', 'hasUserHand');
    const up = new FakeElement('button');
    up.dataset.moveRule = 'up';
    const down = new FakeElement('button');
    down.dataset.moveRule = 'down';
    const remove = new FakeElement('button');
    remove.dataset.removeRule = '';
    fields.append(up, down, remove);
    source.append(ruleSummaryElement, fields);
    const template = new FakeElement('template', {id: 'ai-rule-template'});
    template.content = {firstElementChild: source};
    root.append(form, template);
    const document = new FakeDocument(root, view);
    document.register(root);
    return {form, enabled, rules, save, stop, reload, document};
}

test('rules use server priority order', () => {
    const rules = [
        {id: 'b', priority: 2},
        {id: 'a', priority: 1},
        {id: 'z', priority: 3}
    ];
    assert.deepEqual(sortRules(rules).map(rule => rule.id), ['a', 'b', 'z']);
    assert.deepEqual(rules.map(rule => rule.id), ['b', 'a', 'z']);
    assert.deepEqual(sortRules(rules).map(rule => rule.priority), [1, 2, 3]);
});

test('rule activity shows the last batch, safe diagnostic links, and hides stale or draft cursors', async () => {
    const {form, rules, document} = fakeQueueDom();
    const refresh = new FakeElement('button', {id: 'ai-activity-refresh'});
    const message = new FakeElement('p', {id: 'ai-activity-message'});
    form.append(refresh, message);
    const source = document.getElementById('ai-rule-template').content.firstElementChild;
    const cursor = new FakeElement('span'); cursor.dataset.ruleCursor = '';
    const active = new FakeElement('span'); active.dataset.ruleActive = '';
    const details = new FakeElement('div'); details.dataset.ruleActivity = '';
    source.append(cursor, active, details); document.register(document.root);
    const settings = {revision: 1, enabled: true, games: ['bj_igt'], rules: [
        {id: 'a', name: 'First', priority: 1, enabled: true, gameCode: 'bj_igt'},
        {id: 'b', name: 'Default', priority: 2, enabled: true, gameCode: 'bj_igt'}]};
    const imageId = 'a'.repeat(64);
    let revision = 1, failure = false;
    const original = global.fetch;
    const calls = [];
    global.fetch = async (url, options) => {
        calls.push({url, options});
        if (url.endsWith('/settings')) return {ok: true, json: async () => settings};
        assert.equal(url, '/admin/api/ai-queue/operations/activity');
        if (failure) throw new Error('Unavailable');
        return {ok: true, json: async () => ({revision, generatedAt: '2026-09-17T10:00:00Z',
            lastIssuedRuleIds: ['a', 'b'], rules: [{ruleId: 'a', lastIssuedAt: '2026-09-17T09:59:00Z', lastIssuedCount: 3,
                processing: 6, active: 3, expired: 2, expiredImageId: imageId, oldestDeadline: '2026-09-17T09:58:00Z',
                lastErrorAt: '2026-09-17T09:50:00Z', lastErrorCode: 'AI_REJECTED', lastErrorMessage: '<img onerror=alert(1)>',
                lastErrorImageId: imageId}, {ruleId: 'b', processing: 1, active: 1, expired: 0, lastIssuedAt: '2026-09-17T09:59:00Z', lastIssuedCount: 1}]})};
    };
    try {
        const queue = initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(rules.children[0].querySelector('[data-rule-cursor]').hidden, false);
        assert.equal(rules.children[1].querySelector('[data-rule-cursor]').hidden, false);
        assert.equal(rules.children[0].querySelector('[data-rule-active]').textContent, 'Awaiting results: 3');
        assert.equal(rules.children[1].querySelector('[data-rule-active]').hidden, false);
        const activity = rules.children[0].querySelector('[data-rule-activity]');
        assert.ok(activity.querySelectorAll('a').some(link => link.href === '/admin/screenshots?imageId=' + imageId));
        assert.ok(activity.querySelectorAll('p').some(p => p.textContent.includes('<img onerror=alert(1)>')));
        assert.equal(activity.querySelectorAll('img').length, 0);
        assert.ok(activity.querySelectorAll('p').some(p => p.textContent.includes('Unknown deadline: 1')));
        assert.equal(calls.some(call => call.options.method === 'PUT'), false);
        const name = rules.children[0].querySelector('[name="name"]');
        name.value = 'Draft'; form.dispatchEvent({type: 'input', target: name});
        assert.equal(rules.children[0].querySelector('[data-rule-cursor]').hidden, true);
        assert.match(message.textContent, /saved settings/i);
        name.value = 'First'; form.dispatchEvent({type: 'input', target: name});
        revision = 2; await queue.refreshActivity();
        assert.equal(rules.children[0].querySelector('[data-rule-cursor]').hidden, true);
        assert.match(message.textContent, /another session/i);
        revision = 1; failure = true; await queue.refreshActivity();
        assert.equal(rules.children[0].querySelector('[data-rule-cursor]').hidden, true);
        assert.match(message.textContent, /Unavailable/);
    } finally { global.fetch = original; }
});

test('rule summary keeps selected false and zero conditions', () => {
    const summary = ruleSummary({name: '<unsafe>', enabled: false, priority: '1', gameCode: 'bj_igt', tokenId: '0'});
    assert.match(summary, /<unsafe>/);
    assert.match(summary, /disabled/);
    assert.match(summary, /priority 1/);
    assert.match(summary, /bj_igt/);
    assert.match(summary, /token 0/);
});

test('settings snapshot normalizes server dates while preserving disabled values', () => {
    const snapshot = settingsSnapshot({enabled: false, rules: [{id: 'r1', name: ' Rule ', enabled: false,
        priority: 1, gameCode: 'bj_igt', createdFrom: '2026-09-03T10:15:30Z', tokenId: 0, hasUserHand: null}]});
    assert.deepEqual(snapshot, {enabled: false, rules: [{id: 'r1', name: 'Rule', enabled: false,
        priority: 1, gameCode: 'bj_igt', createdFrom: '2026-09-03T10:15:30Z', createdTo: null, tokenId: 0,
        sessionId: null, hasUserHand: null}]});
});

test('rule dates are UTC and absent predicates stay null', () => {
    const rule = rulePayload({name:' Example ', enabled:true, priority:'1', gameCode:'bj_single_deck_ags',
        createdFrom:'2026-09-03T10:15:30', createdTo:'', tokenId:'0', sessionId:'', hasUserHand:''});
    assert.equal(rule.createdFrom, '2026-09-03T10:15:30Z');
    assert.equal(rule.tokenId, 0);
    assert.equal(rule.gameCode, 'bj_single_deck_ags');
    assert.equal(Object.hasOwn(rule, 'notification'), false);
    assert.equal(rule.hasUserHand, null);
    assert.equal(rule.name, 'Example');
});

test('queue renders rules and keeps the draft across stop, save, and reload', async () => {
    const {form, enabled, rules, save, stop, reload, document} = fakeQueueDom();
    const saved = {id: 'saved', name: 'Saved', enabled: true, priority: 1, gameCode: 'bj_igt',
        createdFrom: '2026-09-03T10:15:30.123456Z', createdTo: null, tokenId: 0, sessionId: null, hasUserHand: null};
    const savedDraft = {...saved, name: 'Draft'};
    const calls = [];
    const games = ['bj_single_deck_ags', 'bj_igt'];
    const initial = {revision: 1, enabled: true, rules: [saved], leaseSeconds: 120, games};
    const stopped = {revision: 2, enabled: false, rules: [saved], leaseSeconds: 120, games};
    const afterSave = {revision: 3, enabled: false, rules: [savedDraft], leaseSeconds: 120, games};
    const previousFetch = global.fetch;
    let operationRefreshes = 0;
    global.fetch = async (_url, options) => {
        const body = options.body ? JSON.parse(options.body) : null;
        calls.push(body);
        if (!body) return {ok: true, status: 200, json: async () => calls.length === 1 ? initial : afterSave};
        return {ok: true, status: 200, json: async () => calls.length === 2 ? stopped : afterSave};
    };
    try {
        const queue = initializeAiQueue(form, {refresh: () => operationRefreshes++});
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(form.noValidate, true);
        const rendered = rules.firstElementChild;
        assert.ok(rendered);
        assert.equal(rendered.querySelector('[name="name"]').value, 'Saved');
        assert.equal(rendered.querySelector('[name="gameCode"]').value, 'bj_igt');
        assert.equal(rendered.querySelector('[data-rule-position]').textContent, '1');

        let name = rendered.querySelector('[name="name"]');
        name.value = 'Draft';
        name.dispatchEvent({type: 'input'});
        stop.dispatchEvent({type: 'click'});
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(calls[1].enabled, false);
        assert.equal(calls[1].rules[0].name, 'Saved');
        assert.equal(calls[1].rules[0].createdFrom, saved.createdFrom);
        assert.equal(rules.firstElementChild.querySelector('[name="name"]').value, 'Draft');
        assert.equal(enabled.checked, false);
        assert.equal(operationRefreshes, 1);

        form.dispatchEvent({type: 'submit'});
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(calls[2].rules[0].name, 'Draft');
        assert.equal(calls[2].rules[0].createdFrom, saved.createdFrom);
        assert.equal(rules.firstElementChild.querySelector('[name="name"]').value, 'Draft');
        assert.equal(operationRefreshes, 2);

        name = rules.firstElementChild.querySelector('[name="name"]');
        name.value = 'Reload draft';
        name.dispatchEvent({type: 'input'});
        reload.dispatchEvent({type: 'click'});
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(rules.firstElementChild.querySelector('[name="name"]').value, 'Draft');
    } finally {
        global.fetch = previousFetch;
    }
});

test('move and remove keep priorities unique and sequential without saving', async () => {
    const {form, rules, document} = fakeQueueDom();
    const initial = {revision: 1, enabled: true, rules: [
        {id: 'b', name: 'B', enabled: true, priority: 2, gameCode: 'bj_igt'},
        {id: 'a', name: 'A', enabled: true, priority: 1, gameCode: 'bj_single_deck_ags'}
    ], leaseSeconds: 120, games: ['bj_single_deck_ags', 'bj_igt']};
    const calls = [];
    const previousFetch = global.fetch;
    global.fetch = async (_url, options) => {
        calls.push(options.body ? JSON.parse(options.body) : null);
        return {ok: true, status: 200, json: async () => initial};
    };
    try {
        const queue = initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        const first = rules.children[0];
        const second = rules.children[1];
        second.querySelector('[data-move-rule="up"]').dispatchEvent({type: 'click'});
        assert.deepEqual(Array.from(rules.children, node => node.dataset.ruleId), ['b', 'a']);
        assert.deepEqual(queue.draftSettings().rules.map(rule => rule.priority), [1, 2]);
        assert.equal(rules.children[0].querySelector('[data-rule-position]').textContent, '1');
        assert.equal(rules.children[1].querySelector('[data-rule-position]').textContent, '2');
        assert.equal(calls.length, 1);

        first.querySelector('[data-remove-rule]').dispatchEvent({type: 'click'});
        assert.equal(rules.children.length, 1);
        assert.equal(rules.firstElementChild.querySelector('[data-rule-position]').textContent, '1');
        assert.equal(queue.draftSettings().rules[0].priority, 1);
        assert.equal(calls.length, 1);
    } finally {
        global.fetch = previousFetch;
    }
});

test('reordering preserves focus, draft fields, and saved order across reload', async () => {
    const {form, rules, save, reload, document} = fakeQueueDom();
    let saved = {revision: 1, enabled: true, games: ['bj_igt'], rules: [1, 2, 3].map(i => ({
        id: `r${i}`, name: `Rule ${i}`, enabled: i !== 2, priority: i, gameCode: 'bj_igt',
        createdFrom: '2026-09-03T10:15:30.123456Z', tokenId: i, sessionId: `session-${i}`, hasUserHand: false
    }))};
    let writes = 0;
    const previousFetch = global.fetch;
    global.fetch = async (_url, options) => {
        if (options.body) { writes++; saved = {...saved, ...JSON.parse(options.body), revision: saved.revision + 1}; }
        return {ok: true, status: 200, json: async () => saved};
    };
    const settled = () => new Promise(resolve => setTimeout(resolve, 0));
    try {
        const queue = initializeAiQueue(form);
        await settled();
        document.register(rules);
        const third = rules.children[2];
        const up = third.querySelector('[data-move-rule="up"]');
        up.focus();
        up.dispatchEvent({type: 'click'});
        assert.ok(document.activeElement === up, 'focus stays on Move up');
        up.dispatchEvent({type: 'click'});
        assert.equal(up.disabled, true);
        assert.ok(document.activeElement === third.querySelector('summary'), 'focus stays on the boundary rule');
        const down = third.querySelector('[data-move-rule="down"]');
        down.focus();
        down.dispatchEvent({type: 'click'});
        assert.ok(document.activeElement === down, 'focus stays on Move down');
        assert.deepEqual(queue.draftSettings().rules.map(r => r.id), ['r1', 'r3', 'r2']);
        assert.equal(save.disabled, false);
        assert.equal(writes, 0);
        form.dispatchEvent({type: 'submit'});
        await settled();
        reload.dispatchEvent({type: 'click'});
        await settled();
        assert.equal(writes, 1);
        assert.equal(save.disabled, true);
        assert.deepEqual(queue.draftSettings().rules.map(r => r.id), ['r1', 'r3', 'r2']);
        assert.deepEqual(saved.rules.map(r => r.priority), [1, 2, 3]);
        assert.equal(saved.rules[2].enabled, false);
        assert.equal(saved.rules[2].tokenId, 2);
        assert.equal(saved.rules[2].sessionId, 'session-2');
        assert.equal(saved.rules[2].hasUserHand, false);
        assert.equal(saved.rules[2].createdFrom, '2026-09-03T10:15:30.123456Z');
    } finally { global.fetch = previousFetch; }
});

test('a failed save preserves reordered rules and ignores further moves while saving', async () => {
    const {form, rules, save, document} = fakeQueueDom();
    let saved = {revision: 1, enabled: true, games: ['bj_igt'], rules: [1, 2].map(i => ({
        id: `r${i}`, name: `Rule ${i}`, enabled: true, priority: i, gameCode: 'bj_igt'
    }))};
    const writes = [];
    let finish;
    const previousFetch = global.fetch;
    global.fetch = async (_url, options) => {
        if (!options.body) return {ok: true, status: 200, json: async () => saved};
        writes.push(JSON.parse(options.body));
        return new Promise(resolve => { finish = resolve; });
    };
    const settled = () => new Promise(resolve => setTimeout(resolve, 0));
    try {
        const queue = initializeAiQueue(form);
        await settled();
        rules.children[1].querySelector('[data-move-rule="up"]').dispatchEvent({type: 'click'});
        form.dispatchEvent({type: 'submit'});
        form.dispatchEvent({type: 'submit'});
        rules.children[0].querySelector('[data-move-rule="down"]').dispatchEvent({type: 'click'});
        assert.equal(writes.length, 1);
        assert.equal(save.disabled, true);
        assert.deepEqual(queue.draftSettings().rules.map(r => r.id), ['r2', 'r1']);
        finish({ok: false, status: 503, json: async () => ({detail: 'Try again later'})});
        await settled();
        assert.equal(queue.dirty(), true);
        assert.equal(save.disabled, false);
        assert.equal(document.getElementById('ai-queue-message').textContent, 'Try again later');
        form.dispatchEvent({type: 'submit'});
        assert.equal(writes[1].revision, 1);
        assert.deepEqual(writes[1].rules.map(r => r.id), ['r2', 'r1']);
        saved = {...saved, ...writes[1], revision: 2};
        finish({ok: true, status: 200, json: async () => saved});
        await settled();
        assert.equal(queue.dirty(), false);
        assert.equal(save.disabled, true);
    } finally { global.fetch = previousFetch; }
});

test('a concurrent admin conflict preserves the draft until a confirmed reload', async () => {
    const {form, rules, reload, document} = fakeQueueDom();
    const initial = {revision: 1, enabled: true, games: ['bj_igt'], rules: [1, 2].map(i => ({
        id: `r${i}`, name: `Rule ${i}`, enabled: true, priority: i, gameCode: 'bj_igt'
    }))};
    const latest = {...initial, revision: 2, rules: initial.rules.map(r => ({...r, name: 'Updated by another admin'}))};
    let reads = 0;
    const previousFetch = global.fetch;
    global.fetch = async (_url, options) => options.body
        ? {ok: false, status: 409, json: async () => ({})}
        : {ok: true, status: 200, json: async () => ++reads === 1 ? initial : latest};
    const settled = () => new Promise(resolve => setTimeout(resolve, 0));
    try {
        const queue = initializeAiQueue(form);
        await settled();
        rules.children[1].querySelector('[data-move-rule="up"]').dispatchEvent({type: 'click'});
        form.dispatchEvent({type: 'submit'});
        await settled();
        assert.equal(reads, 2);
        assert.equal(queue.dirty(), true);
        assert.deepEqual(queue.draftSettings().rules.map(r => r.id), ['r2', 'r1']);
        assert.match(document.getElementById('ai-queue-message').textContent, /Draft kept; latest revision loaded/);
        document.defaultView.confirm = () => false;
        reload.dispatchEvent({type: 'click'});
        await settled();
        assert.equal(reads, 2);
        document.defaultView.confirm = () => true;
        reload.dispatchEvent({type: 'click'});
        await settled();
        assert.equal(reads, 3);
        assert.equal(queue.dirty(), false);
        assert.deepEqual(queue.draftSettings().rules.map(r => r.id), ['r1', 'r2']);
        assert.equal(queue.draftSettings().rules[0].name, 'Updated by another admin');
    } finally { global.fetch = previousFetch; }
});

test('new rules default to Single Deck', async () => {
    const {form, rules, document} = fakeQueueDom();
    const initial = {revision: 1, enabled: false, rules: [], leaseSeconds: 120,
        games: ['bj_single_deck_ags', 'bj_igt']};
    const previousFetch = global.fetch;
    global.fetch = async () => ({ok: true, status: 200, json: async () => initial});
    try {
        const queue = initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        document.getElementById('ai-rule-add').dispatchEvent({type: 'click'});
        assert.equal(rules.firstElementChild.querySelector('[name="gameCode"]').value, 'bj_single_deck_ags');
        assert.equal(queue.draftSettings().rules[0].priority, 1);
    } finally {
        global.fetch = previousFetch;
    }
});

test('AI operations load and refresh on demand', async () => {
    const root = new FakeElement('main');
    for (const id of ['ai-operations-state', 'ai-operations-eligible', 'ai-operations-processing',
        'ai-operations-failed', 'ai-operations-expired', 'ai-operations-last-result', 'ai-operations-message']) {
        root.append(new FakeElement('span', {id}));
    }
    const refresh = new FakeElement('button', {id: 'ai-operations-refresh'});
    root.append(refresh);
    const document = new FakeDocument(root, {addEventListener() {}});
    document.register(root);
    let calls = 0;
    const previousFetch = global.fetch;
    global.fetch = async () => ({ok: true, status: 200, json: async () => ({
        enabled: calls++ > 0, hasEligiblePending: true, processing: 3, failed: 2, expired: 1,
        lastResult: '2026-09-09T10:00:00Z'
    })});
    try {
        initializeAiOperations(document);
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(document.getElementById('ai-operations-state').textContent, 'Paused');
        assert.equal(document.getElementById('ai-operations-failed').textContent, '2');
        refresh.dispatchEvent({type: 'click'});
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(document.getElementById('ai-operations-state').textContent, 'Allowed');
        assert.equal(calls, 2);
    } finally {
        global.fetch = previousFetch;
    }
});

test('rule statistics refresh saved counts without saving or discarding a draft', async () => {
    const {form, rules, document} = fakeQueueDom();
    const refresh = new FakeElement('button', {id: 'ai-rule-stats-refresh'});
    const message = new FakeElement('p', {id: 'ai-rule-stats-message'});
    document.root.append(refresh, message);
    const template = document.getElementById('ai-rule-template').content.firstElementChild;
    for (const field of ['remaining', 'processing', 'completed', 'failed']) {
        const node = new FakeElement('strong'); node.dataset.ruleStat = field; template.append(node);
    }
    document.register(document.root);
    let revision = 1, reads = 0, writes = 0, fail = false;
    const previousFetch = global.fetch;
    global.fetch = async (url, options) => {
        if (options.body) writes++;
        if (url.endsWith('/rules')) {
            reads++;
            return {ok: !fail, status: fail ? 503 : 200, json: async () => fail ? {detail: 'Unavailable'} : {
                revision, generatedAt: '2026-09-15T10:00:00Z', rules: [
                    {ruleId: 'r1', remaining: reads, processing: 2, completed: 3, failed: 4}]
            }};
        }
        return {ok: true, status: 200, json: async () => ({revision: 1, enabled: true, games: ['bj_igt'],
            rules: [{id: 'r1', name: 'Rule', enabled: true, priority: 1, gameCode: 'bj_igt'}]})};
    };
    const settled = () => new Promise(resolve => setTimeout(resolve, 0));
    try {
        const queue = initializeAiQueue(form);
        await settled();
        const remaining = rules.firstElementChild.querySelector('[data-rule-stat="remaining"]');
        assert.equal(remaining.textContent, '1');
        await queue.refreshStatistics();
        assert.equal(remaining.textContent, '2');
        const name = rules.firstElementChild.querySelector('[name="name"]');
        name.value = 'Draft';
        form.dispatchEvent({type: 'input', target: name});
        await queue.refreshStatistics();
        assert.equal(remaining.textContent, '—');
        assert.match(message.textContent, /saved settings/);
        assert.equal(name.value, 'Draft');
        assert.equal(writes, 0);
        name.value = 'Rule';
        form.dispatchEvent({type: 'input', target: name});
        revision = 2;
        await queue.refreshStatistics();
        assert.equal(remaining.textContent, '—');
        assert.match(message.textContent, /another session/);
        fail = true;
        await queue.refreshStatistics();
        assert.match(message.textContent, /Unavailable/);
        assert.equal(message.dataset.error, 'true');
        assert.equal(refresh.disabled, false);
    } finally { global.fetch = previousFetch; }
});

test('an expired session HTML response never becomes editable empty settings', async () => {
    const {form, save, document} = fakeQueueDom();
    const previousFetch = global.fetch;
    global.fetch = async () => ({ok: true, status: 200, json: async () => { throw new SyntaxError('HTML login page'); }});
    try {
        initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(save.disabled, true);
        assert.equal(document.getElementById('ai-rule-add').disabled, true);
        assert.equal(document.getElementById('ai-queue-message').dataset.error, 'true');
        assert.doesNotMatch(document.getElementById('ai-queue-state').textContent, /Loading/);
    } finally {
        global.fetch = previousFetch;
    }
});

test('AI settings preserve the server explanation on failure', async () => {
    const {form, document} = fakeQueueDom();
    const previousFetch = global.fetch;
    global.fetch = async () => ({ok: false, status: 503, json: async () => ({detail: 'Temporarily unavailable. Try again.'})});
    try {
        initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(document.getElementById('ai-queue-message').textContent, 'Temporarily unavailable. Try again.');
        assert.doesNotMatch(document.getElementById('ai-queue-state').textContent, /Loading/);
    } finally {
        global.fetch = previousFetch;
    }
});
