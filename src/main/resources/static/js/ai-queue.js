const MAX_RULES = 20;
const DEFAULT_GAME_CODE = 'bj_single_deck_ags';

function utcDateValue(value) {
    if (!value) return null;
    const text = String(value).replace(/Z$/, '').replace(/\.\d+$/, '');
    return `${text.length === 16 ? text + ':00' : text}Z`;
}

function localDateValue(value) {
    if (!value) return '';
    return String(value).replace(/Z$/, '').replace(/\.\d+$/, '');
}

function boolValue(value) {
    return value === '' || value == null ? null : value === true || value === 'true';
}

function rulePayload(values = {}, saved = {}) {
    const empty = value => value === '' || value == null;
    const dateValue = field => saved[field] && localDateValue(values[field]) === localDateValue(saved[field])
        ? saved[field] : utcDateValue(values[field]);
    return {
        id: values.id || null,
        name: String(values.name ?? '').trim(),
        enabled: values.enabled === true || values.enabled === 'true',
        priority: Number(values.priority),
        gameCode: String(values.gameCode ?? '').trim(),
        createdFrom: dateValue('createdFrom'),
        createdTo: dateValue('createdTo'),
        tokenId: empty(values.tokenId) ? null : Number(values.tokenId),
        sessionId: empty(values.sessionId) ? null : String(values.sessionId).trim() || null,
        hasUserHand: boolValue(values.hasUserHand)
    };
}

function compareRules(left, right) {
    const priority = Number(left?.priority) - Number(right?.priority);
    if (Number.isFinite(priority) && priority !== 0) return priority;
    const leftId = left?.id == null ? '' : String(left.id);
    const rightId = right?.id == null ? '' : String(right.id);
    return leftId < rightId ? -1 : leftId > rightId ? 1 : 0;
}

function sortRules(rules) {
    return [...(Array.isArray(rules) ? rules : [])].sort(compareRules);
}

function normalizedRule(rule = {}) {
    return rulePayload({
        id: rule.id,
        name: rule.name,
        enabled: rule.enabled,
        priority: rule.priority,
        gameCode: rule.gameCode,
        createdFrom: localDateValue(rule.createdFrom),
        createdTo: localDateValue(rule.createdTo),
        tokenId: rule.tokenId,
        sessionId: rule.sessionId,
        hasUserHand: rule.hasUserHand
    });
}

function settingsSnapshot(data = {}) {
    return {
        enabled: data.enabled === true || data.enabled === 'true',
        rules: (Array.isArray(data.rules) ? data.rules : []).map(normalizedRule)
    };
}

function ruleSummary(values = {}) {
    const name = String(values.name ?? '').trim() || 'Unnamed rule';
    const enabled = values.enabled === true || values.enabled === 'true';
    const priority = values.priority === '' || values.priority == null ? '—' : String(values.priority);
    const selected = [String(values.gameCode || 'no game')];
    if (values.createdFrom) selected.push(`from ${String(values.createdFrom)}`);
    if (values.createdTo) selected.push(`to ${String(values.createdTo)}`);
    if (values.tokenId !== '' && values.tokenId != null) selected.push(`token ${String(values.tokenId)}`);
    if (values.sessionId != null && String(values.sessionId).trim()) selected.push(`session ${String(values.sessionId).trim()}`);
    if (values.hasUserHand !== '' && values.hasUserHand != null) {
        selected.push(`user hand ${values.hasUserHand === true || values.hasUserHand === 'true' ? 'yes' : 'no'}`);
    }
    return `${name} · ${enabled ? 'enabled' : 'disabled'} · priority ${priority} · ${selected.length ? selected.join(', ') : 'any conditions'}`;
}

if (typeof document !== 'undefined') {
    const operations = initializeAiOperations(document);
    const form = document.getElementById('ai-queue-form');
    if (form) initializeAiQueue(form, operations);
}

function initializeAiOperations(doc) {
    const byId = id => doc.getElementById(id);
    const refreshButton = byId('ai-operations-refresh');
    const state = byId('ai-operations-state');
    const message = byId('ai-operations-message');
    if (!refreshButton || !state || !message) return null;
    let lastUpdated = null;

    async function refresh() {
        refreshButton.disabled = true;
        message.textContent = 'Refreshing…';
        try {
            const response = await fetch('/admin/api/ai-queue/operations', {cache: 'no-store'});
            if (response.status === 401 || (response.redirected && response.url.includes('/login'))) {
                const link = byId('ai-operations-sign-in');
                if (link) link.hidden = false;
                throw new Error('Your session has expired. Sign in again.');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data.detail || data.message || `Request failed (${response.status})`);
            state.textContent = data.enabled ? 'Allowed' : 'Paused';
            byId('ai-operations-eligible').textContent = data.hasEligiblePending ? 'Yes' : 'No';
            byId('ai-operations-processing').textContent = String(data.processing);
            byId('ai-operations-failed').textContent = String(data.failed);
            byId('ai-operations-expired').textContent = String(data.expired);
            byId('ai-operations-last-result').textContent = data.lastResult ? formatAiTime(data.lastResult) : '—';
            lastUpdated = formatAiTime(new Date());
            message.textContent = `Updated ${lastUpdated}.`;
            message.dataset.error = 'false';
        } catch (error) {
            message.dataset.error = 'true';
            message.textContent = `${error.message || 'Could not load AI operations.'}${lastUpdated ? ` Displayed values may be out of date; last updated ${lastUpdated}.` : ''}`;
            if (!lastUpdated) state.textContent = 'Unavailable';
        } finally {
            refreshButton.disabled = false;
        }
    }

    refreshButton.addEventListener('click', refresh);
    refresh();
    return {refresh};
}

function formatAiTime(value) {
    return new Intl.DateTimeFormat('en-GB', {timeZone: 'UTC', dateStyle: 'short', timeStyle: 'medium'})
        .format(new Date(value)) + ' UTC';
}

function initializeAiQueue(form, operations) {
    if (!form || form.dataset.aiQueueInitialized === 'true') return;
    const doc = form.ownerDocument || document;
    const byId = id => doc.getElementById(id);
    const rules = byId('ai-rules') || form.querySelector('#ai-rules');
    const enabled = byId('ai-queue-enabled') || form.querySelector('#ai-queue-enabled');
    const message = byId('ai-queue-message') || form.querySelector('#ai-queue-message');
    const summary = byId('ai-queue-state') || form.querySelector('#ai-queue-state');
    const add = byId('ai-rule-add') || form.querySelector('#ai-rule-add');
    const reload = byId('ai-queue-reload') || form.querySelector('#ai-queue-reload');
    const stop = byId('ai-queue-stop') || form.querySelector('#ai-queue-stop');
    const save = byId('ai-queue-save') || form.querySelector('#ai-queue-save');
    const template = byId('ai-rule-template') || form.querySelector('#ai-rule-template');
    const dirtyIndicator = form.querySelector('[data-ai-queue-dirty]') || byId('ai-queue-dirty');
    if (!rules || !enabled || !message || !summary || !add || !reload || !stop || !save || !template) return;

    form.dataset.aiQueueInitialized = 'true';
    form.noValidate = true;
    let current = null;
    let savedDraft = null;
    let busy = false;

    function nodeValues(node) {
        const values = {id: node.dataset.ruleId || null, priority: node.dataset.priority};
        node.querySelectorAll('[name]').forEach(input => {
            values[input.name] = input.type === 'checkbox' ? input.checked : input.value;
        });
        return values;
    }

    function draftSettings() {
        return {
            enabled: enabled.checked,
            rules: Array.from(rules.children, node => rulePayload(nodeValues(node),
                current?.rules.find(saved => saved.id === node.dataset.ruleId)))
        };
    }

    function dirty() {
        return Boolean(current && savedDraft && JSON.stringify(settingsSnapshot(draftSettings())) !== JSON.stringify(savedDraft));
    }

    function setMessage(text, error = false) {
        message.textContent = String(text || '');
        message.dataset.error = String(error);
    }

    function updateDirty() {
        const value = dirty();
        form.dataset.dirty = String(value);
        if (dirtyIndicator) {
            dirtyIndicator.hidden = !value;
            dirtyIndicator.textContent = value ? 'Unsaved changes' : '';
            dirtyIndicator.setAttribute('aria-hidden', String(!value));
        }
        return value;
    }

    function controls() {
        const hasCurrent = Boolean(current);
        save.disabled = busy || !hasCurrent || !dirty();
        stop.disabled = busy || !hasCurrent || !current.enabled;
        add.disabled = busy || !hasCurrent || rules.children.length >= MAX_RULES;
        reload.disabled = busy;
    }

    function refreshControls() {
        updateDirty();
        controls();
    }

    function lockFields(value) {
        form.querySelectorAll('input, select, textarea, button').forEach(control => {
            if (value) {
                if (!control.disabled) control.dataset.aiQueueBusyDisabled = 'true';
                control.disabled = true;
            } else if (control.dataset.aiQueueBusyDisabled === 'true') {
                control.disabled = false;
                delete control.dataset.aiQueueBusyDisabled;
            }
        });
    }

    function updateQueueSummary() {
        if (!current) return;
        const issuing = current.enabled ? 'New assignments allowed' : 'New assignments paused';
        const lease = current.leaseSeconds == null ? '' : ` · response deadline ${current.leaseSeconds / 60} minutes`;
        summary.textContent = `${issuing}${lease}`;
    }

    function updateRuleSummary(node) {
        const target = node.querySelector('[data-rule-summary]');
        if (target) target.textContent = ruleSummary(nodeValues(node));
    }

    function updateRuleSummaries() {
        rules.querySelectorAll('.ai-rule').forEach(updateRuleSummary);
    }

    function renumberRules() {
        Array.from(rules.children).forEach((node, index, nodes) => {
            node.dataset.priority = String(index + 1);
            const position = node.querySelector('[data-rule-position]');
            if (position) position.textContent = String(index + 1);
            const up = node.querySelector('[data-move-rule="up"]');
            const down = node.querySelector('[data-move-rule="down"]');
            if (up) up.disabled = index === 0;
            if (down) down.disabled = index === nodes.length - 1;
            updateRuleSummary(node);
        });
    }

    function moveRule(node, offset, button) {
        if (busy) return;
        const nodes = Array.from(rules.children);
        const index = nodes.indexOf(node);
        const destination = index + offset;
        if (index < 0 || destination < 0 || destination >= nodes.length) return;
        [nodes[index], nodes[destination]] = [nodes[destination], nodes[index]];
        rules.replaceChildren(...nodes);
        renumberRules();
        refreshControls();
        (button.disabled ? node.querySelector('summary') : button)?.focus();
        setMessage(`Rule moved to position ${destination + 1}. Save AI settings to apply this order.`);
    }

    function cloneSettings(data) {
        const value = {...(data || {})};
        value.rules = sortRules((Array.isArray(value.rules) ? value.rules : []).map(rule => ({...rule})));
        value.games = Array.isArray(value.games) ? [...value.games] : [];
        return value;
    }

    function render(data) {
        current = cloneSettings(data);
        savedDraft = settingsSnapshot(current);
        enabled.checked = current.enabled;
        rules.replaceChildren();
        current.rules.forEach(item => row(item));
        updateRuleSummaries();
        updateQueueSummary();
        refreshControls();
    }

    function row(rule = {}) {
        const source = template.content.firstElementChild;
        if (!source) return null;
        const node = source.cloneNode(true);
        const isNew = !rule.id;
        node.dataset.ruleId = rule.id || '';
        node.dataset.newRule = String(isNew);
        if ('open' in node) node.open = isNew || Boolean(rule.invalid);
        const game = node.querySelector('[name="gameCode"]');
        if (game) {
            game.replaceChildren(...current.games.map(code => {
                const option = doc.createElement('option');
                option.value = code;
                option.textContent = code === DEFAULT_GAME_CODE ? 'Single Deck' : code;
                return option;
            }));
        }
        node.querySelectorAll('[name]').forEach(input => {
            const value = rule[input.name];
            if (input.type === 'checkbox') input.checked = value == null ? true : value === true || value === 'true';
            else if (input.type === 'datetime-local') input.value = localDateValue(value);
            else input.value = value ?? (input.name === 'gameCode' ? DEFAULT_GAME_CODE : '');
        });
        node.querySelectorAll('[data-move-rule]').forEach(button =>
            button.addEventListener('click', () => moveRule(node, button.dataset.moveRule === 'up' ? -1 : 1, button)));
        const remove = node.querySelector('[data-remove-rule]');
        if (remove) remove.addEventListener('click', () => {
            if (busy) return;
            node.remove();
            renumberRules();
            refreshControls();
        });
        rules.append(node);
        renumberRules();
        return node;
    }

    async function request(body) {
        const csrfToken = doc.querySelector('meta[name="_csrf"]');
        const csrfHeader = doc.querySelector('meta[name="_csrf_header"]');
        const headers = {'Content-Type': 'application/json'};
        if (csrfToken && csrfHeader && csrfHeader.content) headers[csrfHeader.content] = csrfToken.content;
        const response = await fetch('/admin/api/ai-queue/settings', {
            method: body ? 'PUT' : 'GET',
            cache: 'no-store',
            headers,
            ...(body ? {body: JSON.stringify(body)} : {})
        });
        if (response.status === 401 || (response.redirected && response.url.includes('/login'))) {
            const link = byId('ai-queue-sign-in');
            if (link) link.hidden = false;
            throw new Error('Your session has expired. Sign in again.');
        }
        let data = {};
        try { data = await response.json(); } catch (_) {
            if (response.ok) throw new Error('Could not read AI settings. Sign in again or reload the page.');
        }
        if (!data || typeof data !== 'object') data = {};
        if (!response.ok) {
            const error = new Error(response.status === 409
                ? 'Settings changed in another session. Draft kept; latest revision loaded.'
                : data.detail || data.message || `Request failed (${response.status})`);
            error.status = response.status;
            throw error;
        }
        if (!Array.isArray(data.rules) || !Array.isArray(data.games)
                || typeof data.enabled !== 'boolean' || !Number.isSafeInteger(data.revision)) {
            throw new Error('Unexpected AI settings response. Draft kept; reload the page.');
        }
        return data;
    }

    async function refreshSavedState() {
        const data = await request();
        current = cloneSettings(data);
        savedDraft = settingsSnapshot(current);
        updateQueueSummary();
        refreshControls();
        return data;
    }

    async function perform(action, success, reconcile = render, refreshOperations = false) {
        if (busy) return null;
        busy = true;
        form.setAttribute('aria-busy', 'true');
        lockFields(true);
        controls();
        setMessage('Working…');
        try {
            const data = await action();
            reconcile(data);
            if (refreshOperations) operations?.refresh();
            lockFields(true);
            setMessage(success);
            return data;
        } catch (error) {
            if (!current) summary.textContent = 'Settings unavailable. Select Reload saved settings to retry.';
            let conflictRefreshed = false;
            if (error.status === 409) {
                try { await refreshSavedState(); conflictRefreshed = true; } catch (_) { /* retain the previous revision if refresh fails */ }
            }
            setMessage(error.status === 409 && !conflictRefreshed
                ? 'Settings changed in another session. Draft kept; latest revision could not be loaded.'
                : error.message || 'Could not update AI settings.', true);
            return null;
        } finally {
            busy = false;
            lockFields(false);
            form.removeAttribute('aria-busy');
            refreshControls();
        }
    }

    function validDraft() {
        const elements = form.elements || form.querySelectorAll('input, select, textarea');
        const invalid = Array.from(elements).filter(control =>
            !control.disabled && typeof control.checkValidity === 'function' && !control.checkValidity());
        invalid.forEach(control => {
            const disclosure = control.closest?.('details.ai-rule') || control.closest?.('details');
            if (disclosure) {
                disclosure.open = true;
                disclosure.dataset.invalid = 'true';
            }
        });
        if (!invalid.length) return true;
        if (typeof form.reportValidity === 'function') form.reportValidity();
        return false;
    }

    function discardConfirmed() {
        if (!dirty()) return true;
        const confirmDiscard = (form.ownerDocument?.defaultView || globalThis).confirm;
        return typeof confirmDiscard !== 'function' || confirmDiscard('Discard unsaved changes and reload saved settings?');
    }

    form.addEventListener('input', event => {
        if (busy) return;
        const disclosure = event.target.closest?.('details.ai-rule');
        if (disclosure) {
            delete disclosure.dataset.invalid;
            updateRuleSummary(disclosure);
        }
        refreshControls();
    });
    form.addEventListener('change', event => {
        if (busy) return;
        const disclosure = event.target.closest?.('details.ai-rule');
        if (disclosure) {
            delete disclosure.dataset.invalid;
            updateRuleSummary(disclosure);
        }
        refreshControls();
    });
    add.addEventListener('click', () => {
        if (busy || !current || rules.children.length >= MAX_RULES) return;
        const node = row();
        refreshControls();
        const name = node?.querySelector('[name="name"]');
        if (name) name.focus();
    });
    reload.addEventListener('click', () => {
        if (busy || !discardConfirmed()) return;
        perform(() => request(), 'Loaded saved settings.');
    });
    stop.addEventListener('click', () => {
        if (busy || !current?.enabled) return;
        perform(() => request({revision: current.revision, enabled: false, rules: current.rules}),
            'New claims stopped. Already issued tasks may still finish.', data => {
                current = cloneSettings(data);
                savedDraft = settingsSnapshot(current);
                enabled.checked = false;
                updateRuleSummaries();
                updateQueueSummary();
                refreshControls();
            }, true);
    });
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (busy || !current || !validDraft()) return;
        const draft = draftSettings();
        perform(() => request({revision: current.revision, enabled: draft.enabled, rules: draft.rules}),
            'Saved. The next claim uses these rules; active tasks are unchanged.', render, true);
    });

    const view = form.ownerDocument?.defaultView || (typeof window !== 'undefined' ? window : null);
    if (view?.addEventListener) {
        view.addEventListener('beforeunload', event => {
            if (!dirty()) return;
            event.preventDefault();
            event.returnValue = '';
        });
    }

    perform(() => request(), 'Enabled rules are checked from top to bottom, oldest screenshots within each rule first.');
    return {render, row, draftSettings, dirty};
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = {rulePayload, sortRules, ruleSummary, settingsSnapshot, initializeAiQueue, initializeAiOperations};
}
