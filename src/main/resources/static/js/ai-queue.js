const MAX_RULES = 20;

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
        createdFrom: dateValue('createdFrom'),
        createdTo: dateValue('createdTo'),
        tokenId: empty(values.tokenId) ? null : Number(values.tokenId),
        sessionId: empty(values.sessionId) ? null : String(values.sessionId).trim() || null,
        notification: boolValue(values.notification),
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
        createdFrom: localDateValue(rule.createdFrom),
        createdTo: localDateValue(rule.createdTo),
        tokenId: rule.tokenId,
        sessionId: rule.sessionId,
        notification: rule.notification,
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
    const selected = [];
    if (values.createdFrom) selected.push(`from ${String(values.createdFrom)}`);
    if (values.createdTo) selected.push(`to ${String(values.createdTo)}`);
    if (values.tokenId !== '' && values.tokenId != null) selected.push(`token ${String(values.tokenId)}`);
    if (values.sessionId != null && String(values.sessionId).trim()) selected.push(`session ${String(values.sessionId).trim()}`);
    if (values.notification !== '' && values.notification != null) {
        selected.push(`notification ${values.notification === true || values.notification === 'true' ? 'yes' : 'no'}`);
    }
    if (values.hasUserHand !== '' && values.hasUserHand != null) {
        selected.push(`user hand ${values.hasUserHand === true || values.hasUserHand === 'true' ? 'yes' : 'no'}`);
    }
    return `${name} · ${enabled ? 'enabled' : 'disabled'} · priority ${priority} · ${selected.length ? selected.join(', ') : 'any conditions'}`;
}

if (typeof document !== 'undefined') {
    const form = document.getElementById('ai-queue-form');
    if (form) initializeAiQueue(form);
}

function initializeAiQueue(form) {
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
        const values = {id: node.dataset.ruleId || null};
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
        const issuing = current.enabled ? 'Issuing enabled' : 'Issuing paused';
        const game = current.game === 'SINGLE_DECK' || current.sourceGame === 'bj_single_deck_ags' ? 'Single Deck' : (current.game || 'AI queue');
        const lease = current.leaseSeconds == null ? '' : ` · lease ${current.leaseSeconds}s`;
        summary.textContent = `${issuing} · ${game}${lease} · revision ${current.revision}`;
    }

    function updateRuleSummary(node) {
        const target = node.querySelector('[data-rule-summary]');
        if (target) target.textContent = ruleSummary(nodeValues(node));
    }

    function updateRuleSummaries() {
        rules.querySelectorAll('.ai-rule').forEach(updateRuleSummary);
    }

    function reorderRules(target) {
        if (target?.name !== 'priority') return;
        const nodes = Array.from(rules.children);
        const entries = nodes.map(node => {
            const priority = node.querySelector('[name="priority"]');
            const value = String(priority?.value ?? '').trim();
            return {node, ...nodeValues(node), valid: Boolean(value) && Number.isFinite(Number(value))
                && (typeof priority?.checkValidity !== 'function' || priority.checkValidity())};
        });
        if (!entries.every(entry => entry.valid)) return;
        const ordered = sortRules(entries).map(entry => entry.node);
        if (!ordered.some((node, index) => node !== nodes[index])) return;
        const active = doc.activeElement;
        rules.replaceChildren(...ordered);
        if (active?.focus) active.focus();
    }

    function cloneSettings(data) {
        const value = {...(data || {})};
        value.rules = sortRules((Array.isArray(value.rules) ? value.rules : []).map(rule => ({...rule})));
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
        node.querySelectorAll('[name]').forEach(input => {
            const value = rule[input.name];
            if (input.type === 'checkbox') input.checked = value == null ? true : value === true || value === 'true';
            else if (input.type === 'datetime-local') input.value = localDateValue(value);
            else input.value = value ?? (input.name === 'priority' ? (rules.children.length + 1) * 10 : '');
        });
        const remove = node.querySelector('[data-remove-rule]');
        if (remove) remove.addEventListener('click', () => {
            if (busy) return;
            node.remove();
            refreshControls();
        });
        rules.append(node);
        updateRuleSummary(node);
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
        let data = {};
        try { data = await response.json(); } catch (_) {
            if (response.ok) throw new Error('Could not read AI settings. Sign in again or reload the page.');
        }
        if (!data || typeof data !== 'object') data = {};
        if (!response.ok) {
            const error = new Error(response.status === 409
                ? 'Settings changed in another session. Draft kept; latest revision loaded.'
                : data.message || `Request failed (${response.status})`);
            error.status = response.status;
            throw error;
        }
        if (!Array.isArray(data.rules) || typeof data.enabled !== 'boolean' || !Number.isSafeInteger(data.revision)) {
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

    async function perform(action, success, reconcile = render) {
        if (busy) return null;
        busy = true;
        form.setAttribute('aria-busy', 'true');
        lockFields(true);
        controls();
        setMessage('Working…');
        try {
            const data = await action();
            reconcile(data);
            lockFields(true);
            setMessage(success);
            return data;
        } catch (error) {
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
        reorderRules(event.target);
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
            });
    });
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (busy || !current || !validDraft()) return;
        const draft = draftSettings();
        perform(() => request({revision: current.revision, enabled: draft.enabled, rules: draft.rules}),
            'Saved. The next claim uses these rules; active tasks are unchanged.');
    });

    const view = form.ownerDocument?.defaultView || (typeof window !== 'undefined' ? window : null);
    if (view?.addEventListener) {
        view.addEventListener('beforeunload', event => {
            if (!dirty()) return;
            event.preventDefault();
            event.returnValue = '';
        });
    }

    perform(() => request(), 'Rules are checked in priority order (smaller number first, oldest screenshot first).');
    return {render, row, draftSettings, dirty};
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = {rulePayload, sortRules, ruleSummary, settingsSnapshot, initializeAiQueue};
}
