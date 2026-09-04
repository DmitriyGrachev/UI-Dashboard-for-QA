const test = require('node:test');
const assert = require('node:assert/strict');
const {rulePayload, sortRules, ruleSummary, settingsSnapshot, initializeAiQueue} =
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
    addField('input', 'name', 'text', true);
    addField('input', 'priority', 'number', true);
    addField('input', 'enabled', 'checkbox');
    addField('input', 'createdFrom', 'datetime-local');
    addField('input', 'createdTo', 'datetime-local');
    addField('input', 'tokenId', 'number');
    addField('input', 'sessionId');
    addField('select', 'notification');
    addField('select', 'hasUserHand');
    const remove = new FakeElement('button');
    remove.dataset.removeRule = '';
    fields.append(remove);
    source.append(ruleSummaryElement, fields);
    const template = new FakeElement('template', {id: 'ai-rule-template'});
    template.content = {firstElementChild: source};
    root.append(form, template);
    const document = new FakeDocument(root, view);
    document.register(root);
    return {form, enabled, rules, save, stop, reload, document};
}

test('rules use server priority then id order without renumbering', () => {
    const rules = [
        {id: 'b', priority: 10},
        {id: 'a', priority: 10},
        {id: 'z', priority: 20}
    ];
    assert.deepEqual(sortRules(rules).map(rule => rule.id), ['a', 'b', 'z']);
    assert.deepEqual(rules.map(rule => rule.id), ['b', 'a', 'z']);
    assert.deepEqual(sortRules(rules).map(rule => rule.priority), [10, 10, 20]);
});

test('rule summary keeps selected false and zero conditions', () => {
    const summary = ruleSummary({name: '<unsafe>', enabled: false, priority: '0', tokenId: '0', notification: 'false'});
    assert.match(summary, /<unsafe>/);
    assert.match(summary, /disabled/);
    assert.match(summary, /priority 0/);
    assert.match(summary, /token 0/);
    assert.match(summary, /notification no/);
});

test('settings snapshot normalizes server dates while preserving disabled values', () => {
    const snapshot = settingsSnapshot({enabled: false, rules: [{id: 'r1', name: ' Rule ', enabled: false,
        priority: 5, createdFrom: '2026-09-03T10:15:30Z', tokenId: 0, notification: false, hasUserHand: null}]});
    assert.deepEqual(snapshot, {enabled: false, rules: [{id: 'r1', name: 'Rule', enabled: false,
        priority: 5, createdFrom: '2026-09-03T10:15:30Z', createdTo: null, tokenId: 0, sessionId: null,
        notification: false, hasUserHand: null}]});
});

test('rule dates are UTC and absent predicates stay null', () => {
    const rule = rulePayload({name:' Example ', enabled:true, priority:'10', createdFrom:'2026-09-03T10:15:30',
        createdTo:'', tokenId:'0', sessionId:'', notification:'false', hasUserHand:''});
    assert.equal(rule.createdFrom, '2026-09-03T10:15:30Z');
    assert.equal(rule.tokenId, 0);
    assert.equal(rule.notification, false);
    assert.equal(rule.hasUserHand, null);
    assert.equal(rule.name, 'Example');
});

test('queue renders rules and keeps the draft across stop, save, and reload', async () => {
    const {form, enabled, rules, save, stop, reload, document} = fakeQueueDom();
    const saved = {id: 'saved', name: 'Saved', enabled: true, priority: 5, createdFrom: '2026-09-03T10:15:30.123456Z',
        createdTo: null, tokenId: 0, sessionId: null, notification: false, hasUserHand: null};
    const savedDraft = {...saved, name: 'Draft'};
    const calls = [];
    const initial = {revision: 1, enabled: true, rules: [saved], leaseSeconds: 120, game: 'SINGLE_DECK'};
    const stopped = {revision: 2, enabled: false, rules: [saved], leaseSeconds: 120, game: 'SINGLE_DECK'};
    const afterSave = {revision: 3, enabled: false, rules: [savedDraft], leaseSeconds: 120, game: 'SINGLE_DECK'};
    const previousFetch = global.fetch;
    global.fetch = async (_url, options) => {
        const body = options.body ? JSON.parse(options.body) : null;
        calls.push(body);
        if (!body) return {ok: true, status: 200, json: async () => calls.length === 1 ? initial : afterSave};
        return {ok: true, status: 200, json: async () => calls.length === 2 ? stopped : afterSave};
    };
    try {
        initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(form.noValidate, true);
        const rendered = rules.firstElementChild;
        assert.ok(rendered);
        assert.equal(rendered.querySelector('[name="name"]').value, 'Saved');
        assert.equal(String(rendered.querySelector('[name="priority"]').value), '5');

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

        form.dispatchEvent({type: 'submit'});
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(calls[2].rules[0].name, 'Draft');
        assert.equal(calls[2].rules[0].createdFrom, saved.createdFrom);
        assert.equal(rules.firstElementChild.querySelector('[name="name"]').value, 'Draft');

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

test('committed priority edits reorder existing draft nodes without saving', async () => {
    const {form, rules, document} = fakeQueueDom();
    const initial = {revision: 1, enabled: true, rules: [
        {id: 'b', name: 'B', enabled: true, priority: 20},
        {id: 'a', name: 'A', enabled: true, priority: 10}
    ], leaseSeconds: 120, game: 'SINGLE_DECK'};
    const calls = [];
    const previousFetch = global.fetch;
    global.fetch = async (_url, options) => {
        calls.push(options.body ? JSON.parse(options.body) : null);
        return {ok: true, status: 200, json: async () => initial};
    };
    try {
        initializeAiQueue(form);
        await new Promise(resolve => setTimeout(resolve, 0));
        const first = rules.children[0];
        const second = rules.children[1];
        assert.equal(first.dataset.ruleId, 'a');
        assert.equal(second.dataset.ruleId, 'b');
        second.open = true;
        second.querySelector('[name="name"]').value = 'Draft B';
        const priority = second.querySelector('[name="priority"]');
        priority.value = '5';
        priority.ownerDocument = document;
        priority.focus();
        form.dispatchEvent({type: 'change', target: priority});
        assert.deepEqual(Array.from(rules.children, node => node.dataset.ruleId), ['b', 'a']);
        assert.equal(rules.children[0], second);
        assert.equal(rules.children[1], first);
        assert.equal(second.querySelector('[name="name"]').value, 'Draft B');
        assert.equal(second.open, true);
        assert.equal(document.activeElement, priority);
        assert.equal(calls.length, 1);

        priority.value = '10';
        form.dispatchEvent({type: 'change', target: priority});
        assert.deepEqual(Array.from(rules.children, node => node.dataset.ruleId), ['a', 'b']);

        priority.value = '';
        form.dispatchEvent({type: 'change', target: priority});
        assert.deepEqual(Array.from(rules.children, node => node.dataset.ruleId), ['a', 'b']);
        assert.equal(calls.length, 1);
    } finally {
        global.fetch = previousFetch;
    }
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
    } finally {
        global.fetch = previousFetch;
    }
});
