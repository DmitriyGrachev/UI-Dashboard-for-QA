function rulePayload(values) {
    const date = value => value ? `${value.length === 16 ? value + ':00' : value}Z` : null;
    const bool = value => value === '' ? null : value === 'true';
    return {id: values.id || null, name: values.name.trim(), enabled: values.enabled,
        priority: Number(values.priority), createdFrom: date(values.createdFrom), createdTo: date(values.createdTo),
        tokenId: values.tokenId === '' ? null : Number(values.tokenId), sessionId: values.sessionId.trim() || null,
        notification: bool(values.notification), hasUserHand: bool(values.hasUserHand)};
}

if (typeof module !== 'undefined' && module.exports) module.exports = {rulePayload};

if (typeof document !== 'undefined') {
    const form = document.getElementById('ai-queue-form');
    if (form) initializeAiQueue(form);
}

function initializeAiQueue(form) {
    const rules = document.getElementById('ai-rules');
    const enabled = document.getElementById('ai-queue-enabled');
    const message = document.getElementById('ai-queue-message');
    const summary = document.getElementById('ai-queue-state');
    const add = document.getElementById('ai-rule-add');
    const reload = document.getElementById('ai-queue-reload');
    const stop = document.getElementById('ai-queue-stop');
    const save = document.getElementById('ai-queue-save');
    let current = null;
    let busy = false;

    function controls() {
        save.disabled = busy || !current;
        stop.disabled = busy || !current?.enabled;
        add.disabled = busy || !current || rules.children.length >= 20;
        reload.disabled = busy;
    }
    async function request(body) {
        const response = await fetch('/admin/api/ai-queue/settings', {
            method: body ? 'PUT' : 'GET', cache: 'no-store',
            headers: {'Content-Type': 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]:
                    document.querySelector('meta[name="_csrf"]').content},
            ...(body ? {body: JSON.stringify(body)} : {})
        });
        const data = await response.json();
        if (!response.ok) throw new Error(response.status === 409
            ? 'Settings changed in another session. Reload and review before saving.'
            : data.message || `Request failed (${response.status})`);
        return data;
    }
    function row(rule = {}) {
        const node = document.getElementById('ai-rule-template').content.firstElementChild.cloneNode(true);
        node.dataset.ruleId = rule.id || '';
        node.querySelectorAll('[name]').forEach(input => {
            const value = rule[input.name];
            if (input.type === 'checkbox') input.checked = value ?? true;
            else if (input.type === 'datetime-local') input.value = value?.replace(/Z$/, '') || '';
            else input.value = value ?? (input.name === 'priority' ? (rules.children.length + 1) * 10 : '');
        });
        node.querySelector('[data-remove-rule]').addEventListener('click', () => {node.remove(); controls();});
        rules.append(node);
        controls();
    }
    function render(data) {
        current = data;
        enabled.checked = data.enabled;
        rules.replaceChildren();
        data.rules.forEach(row);
        summary.textContent = `${data.enabled ? 'Issuing enabled' : 'Issuing paused'} · Single Deck · lease ${data.leaseSeconds}s · revision ${data.revision}`;
    }
    async function perform(action, success) {
        if (busy) return;
        busy = true; controls(); message.textContent = 'Working…';
        try {render(await action()); message.textContent = success;}
        catch (error) {message.textContent = error.message || 'Could not update AI settings.';}
        finally {busy = false; controls();}
    }
    add.addEventListener('click', () => row());
    reload.addEventListener('click', () => perform(() => request(), 'Loaded saved settings.'));
    stop.addEventListener('click', () => perform(() => request({revision: current.revision, enabled: false, rules: current.rules}),
        'New claims stopped. Already issued tasks may still finish.'));
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (!current || !form.reportValidity()) return;
        const items = Array.from(rules.children, node => {
            const values = {id: node.dataset.ruleId};
            node.querySelectorAll('[name]').forEach(input => {
                values[input.name] = input.type === 'checkbox' ? input.checked : input.value;
            });
            return rulePayload(values);
        });
        perform(() => request({revision: current.revision, enabled: enabled.checked, rules: items}),
            'Saved. The next claim uses these rules; active tasks are unchanged.');
    });
    perform(() => request(), 'Rules are checked in priority order (smaller number first), oldest screenshot first.');
}
