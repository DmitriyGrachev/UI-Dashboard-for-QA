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
    initializeAiFailures(document);
    const form = document.getElementById('ai-queue-form');
    if (form) initializeAiQueue(form, operations);
}

function failureExplorerUrl(group = null) {
    const params = new URLSearchParams({aiTaskStatus: 'FAILED'});
    if (group) {
        params.set(group.ruleId == null ? 'issuedRuleMissing' : 'issuedRuleId', group.ruleId ?? 'true');
        params.set(group.errorCode == null ? 'aiErrorMissing' : 'aiErrorCode', group.errorCode ?? 'true');
    }
    return '/admin/screenshots?' + params;
}

function initializeAiFailures(doc) {
    const panel = doc.getElementById('ai-failures');
    if (!panel) return;
    const refresh = doc.getElementById('ai-failures-refresh');
    const message = doc.getElementById('ai-failures-message');
    const list = doc.getElementById('ai-failures-groups');
    const total = doc.getElementById('ai-failures-total');
    let loaded = false;
    async function load() {
        if (refresh.disabled) return;
        refresh.disabled = true; panel.setAttribute('aria-busy', 'true');
        message.textContent = 'Loading failed tasks…'; message.dataset.error = 'false';
        list.replaceChildren(); total.hidden = true;
        try {
            const response = await fetch('/admin/api/ai-queue/operations/failures', {cache: 'no-store'});
            if (response.status === 401 || (response.redirected && response.url.includes('/login'))) {
                doc.getElementById('ai-failures-sign-in').hidden = false;
                throw new Error('Your session has expired. Sign in again.');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data.detail || data.message || `Request failed (${response.status})`);
            if (!Array.isArray(data.groups) || !Number.isSafeInteger(data.total) || !data.generatedAt) {
                throw new Error('Could not read failed-task summary.');
            }
            total.href = failureExplorerUrl(); total.textContent = `View all ${data.total.toLocaleString('en-US')} failed tasks`;
            total.hidden = data.total === 0;
            const labels = {AI_REJECTED: 'Rejected by AI service', DELIVERY_NOT_CONFIGURED: 'Image delivery is not configured',
                IMAGE_UNAVAILABLE: 'Image source unavailable', DELIVERY_UNAVAILABLE: 'Image delivery unavailable', LEASE_EXPIRED: 'Assignment deadline exceeded'};
            data.groups.forEach(group => {
                const row = doc.createElement('tr');
                const reason = doc.createElement('td'), rule = doc.createElement('td'), count = doc.createElement('td');
                reason.textContent = group.errorCode == null ? 'Reason unavailable'
                    : Object.hasOwn(labels, group.errorCode) ? `${labels[group.errorCode]} (${group.errorCode})` : group.errorCode;
                rule.textContent = group.ruleName || (group.ruleId ? `Rule ${group.ruleId} (name unavailable)` : 'No recorded rule');
                const link = doc.createElement('a'); link.href = failureExplorerUrl(group);
                link.textContent = Number(group.count).toLocaleString('en-US');
                link.setAttribute('aria-label', `${group.count} failed tasks: ${reason.textContent}, ${rule.textContent}`);
                count.append(link); row.append(reason, rule, count); list.append(row);
            });
            message.textContent = (data.total ? '' : 'No failed AI tasks. ') + `Updated ${formatAiTime(data.generatedAt)}. Refresh to update.`;
            loaded = true;
        } catch (error) {
            message.dataset.error = 'true';
            message.textContent = `${error.message || 'Could not load failed tasks.'} Select Refresh failures to retry.`;
        } finally { refresh.disabled = false; panel.setAttribute('aria-busy', 'false'); }
    }
    panel.addEventListener('toggle', () => { if (panel.open && !loaded) void load(); });
    refresh.addEventListener('click', load);
    initializeAiFailureLog(doc);
    const openFromHash = () => { if (doc.defaultView?.location?.hash === '#ai-failures') panel.open = true; };
    doc.defaultView?.addEventListener?.('hashchange', openFromHash);
    doc.querySelectorAll('a[href="#ai-failures"], a[href="/admin/ai-queue#ai-failures"]').forEach(link =>
        link.addEventListener('click', () => { panel.open = true; }));
    openFromHash();
    return {refresh: load};
}

function initializeAiFailureLog(doc) {
    const list = doc.getElementById('ai-failure-log');
    if (!list) return;
    const panel = doc.getElementById('ai-failures'), refresh = doc.getElementById('ai-failures-refresh');
    const more = doc.getElementById('ai-failure-log-more'), onlyAi = doc.getElementById('ai-failure-ai-only');
    const message = doc.getElementById('ai-failure-log-message');
    let cursor = null, busy = false, loaded = false;
    function entry(item) {
        const row = doc.createElement('li'); row.className = 'ai-failure-entry';
        const href = '/admin/screenshots?imageId=' + encodeURIComponent(item.imageId);
        const preview = doc.createElement('a'); preview.className = 'ai-failure-preview'; preview.href = href;
        const image = doc.createElement('img'); image.loading = 'lazy'; image.decoding = 'async';
        image.alt = 'Inspect ' + item.fileName; image.width = 160; image.height = 90;
        image.src = `/admin/api/screenshots/${encodeURIComponent(item.imageId)}/thumbnail`;
        image.addEventListener('error', () => { preview.textContent = 'Preview unavailable · open details'; }, {once: true});
        preview.append(image);
        const detail = doc.createElement('div'); detail.className = 'ai-failure-detail';
        const heading = doc.createElement('div'); heading.className = 'ai-failure-heading';
        const source = doc.createElement('strong'); source.className = 'ai-failure-source';
        source.textContent = item.errorCode === 'AI_REJECTED' ? 'Rejected by AI service'
            : ['DELIVERY_UNAVAILABLE', 'DELIVERY_NOT_CONFIGURED', 'IMAGE_UNAVAILABLE'].includes(item.errorCode)
                ? 'Image delivery failure' : 'AI task failed';
        const time = doc.createElement('span');
        time.textContent = item.failedAt ? formatAiTime(item.failedAt) : 'Error time unavailable';
        heading.append(source, time);
        const reason = doc.createElement('p'); reason.className = 'ai-failure-reason';
        reason.textContent = item.errorMessage || 'No error message was recorded.';
        const file = doc.createElement('a'); file.href = href; file.textContent = item.fileName || item.imageId;
        const meta = doc.createElement('p'); meta.className = 'ai-failure-meta';
        meta.textContent = `${item.errorCode || 'Unknown error code'} · Attempts: ${item.attemptCount} · ${item.gameCode}`;
        const rule = doc.createElement('p'); rule.className = 'ai-failure-meta';
        rule.textContent = 'Rule: ' + (item.ruleName || (item.ruleId ? `${item.ruleId} (no longer in settings)` : 'Not recorded'));
        detail.append(heading, reason, file, meta, rule); row.append(preview, detail); return row;
    }
    async function load(reset = false) {
        if (busy) return;
        const focusNext = doc.activeElement === more;
        const previousCount = reset ? 0 : list.children.length;
        busy = true; more.disabled = true; onlyAi.disabled = true;
        if (reset) { cursor = null; list.replaceChildren(); more.hidden = true; loaded = false; }
        message.dataset.error = 'false'; message.textContent = 'Loading failed screenshots…';
        const query = new URLSearchParams({aiOnly: String(onlyAi.checked)});
        if (cursor) { query.set('beforeAt', cursor.at); query.set('beforeId', cursor.id); }
        try {
            const response = await fetch('/admin/api/ai-queue/operations/failures/tasks?' + query, {cache: 'no-store'});
            if (response.status === 401 || (response.redirected && response.url.includes('/login'))) {
                doc.getElementById('ai-failures-sign-in').hidden = false;
                throw new Error('Your session has expired. Sign in again.');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data.detail || data.message || `Request failed (${response.status})`);
            if (!Array.isArray(data.items)) throw new Error('Could not read failed screenshots.');
            data.items.forEach(item => list.append(entry(item)));
            if (focusNext) (list.children[previousCount] || list.lastElementChild)?.querySelector('a')?.focus();
            cursor = data.nextId ? {id: data.nextId, at: data.nextAt} : null;
            more.hidden = !cursor;
            message.textContent = list.children.length ? `${list.children.length} failed ${list.children.length === 1 ? 'task' : 'tasks'} shown. Refresh to update.`
                : onlyAi.checked ? 'No current rejections from the AI service.' : 'No failed AI tasks.';
            loaded = true;
        } catch (error) {
            message.dataset.error = 'true';
            message.textContent = `${error.message || 'Could not load failed screenshots.'} ${cursor ? 'Select Load more failures to retry.' : 'Select Refresh failures to retry.'}`;
        } finally { busy = false; more.disabled = false; onlyAi.disabled = false; }
    }
    panel.addEventListener('toggle', () => { if (panel.open && !loaded) void load(true); });
    refresh.addEventListener('click', () => load(true));
    onlyAi.addEventListener('change', () => load(true));
    more.addEventListener('click', () => load());
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
    const statsRefresh = byId('ai-rule-stats-refresh');
    const statsMessage = byId('ai-rule-stats-message');
    let statistics = null;
    let statsBusy = false;
    let statsError = '';
    const activityRefresh = byId('ai-activity-refresh');
    const activityMessage = byId('ai-activity-message');
    let activity = null;
    let activityBusy = false;
    let activityError = '';
    let activityController = null;
    let disposed = false;

    function assignmentCounts(value) {
        return value && ['processing', 'active', 'expired'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0)
            && value.active + value.expired <= value.processing ? value : null;
    }

    function renderActivity() {
        if (!activityMessage) return;
        const matches = current && activity?.revision === current.revision && !dirty();
        const values = new Map((matches ? activity.rules : []).map(value => [value.ruleId, value]));
        const latest = new Set(matches ? activity.lastIssuedRuleIds : []);
        const route = byId('ai-rule-route');
        if (route) {
            const focusedRule = route.querySelector('button:focus')?.dataset.ruleTarget;
            route.replaceChildren();
            const notice = byId('ai-rule-route-message');
            notice.textContent = dirty() ? 'Save or reload settings to see the saved priority and activity.'
                : !matches ? 'Activity unavailable. Refresh activity or reload saved settings.'
                : !current.rules.length ? 'Add a rule to start selecting screenshots.'
                : !current.enabled ? 'New assignments are paused. Previously assigned tasks may still return results.'
                : !latest.size ? 'No assignments recorded yet.' : 'Select a rule to inspect its conditions and task counts.';
            if (matches) current.rules.forEach((rule, index) => {
                const value = assignmentCounts(values.get(rule.id));
                const button = doc.createElement('button');
                button.type = 'button'; button.className = 'ai-route-rule';
                button.dataset.ruleTarget = rule.id;
                button.dataset.latest = String(latest.has(rule.id));
                button.dataset.enabled = String(rule.enabled);
                const rank = doc.createElement('span'); rank.className = 'ai-priority'; rank.textContent = index + 1;
                const name = doc.createElement('strong'); name.textContent = rule.name;
                const status = doc.createElement('small');
                status.textContent = [latest.has(rule.id) ? '↳ Latest batch' : '', !rule.enabled ? 'Disabled' : '',
                    value ? `${value.active} awaiting · ${value.expired} overdue` : 'Counts unavailable'].filter(Boolean).join(' · ');
                button.append(rank, name, status);
                button.addEventListener('click', () => {
                    const node = Array.from(rules.children).find(node => node.dataset.ruleId === rule.id);
                    if (node) { node.open = true; node.querySelector('summary')?.focus(); }
                });
                route.append(button);
                if (focusedRule === rule.id) button.focus({preventScroll: true});
            });
        }
        Array.from(rules.children).forEach(node => {
            const value = values.get(node.dataset.ruleId);
            const counts = assignmentCounts(value);
            node.dataset.latest = String(latest.has(node.dataset.ruleId));
            const overdue = node.querySelector('[data-rule-overdue]');
            if (overdue) { overdue.hidden = !counts?.expired; overdue.textContent = counts ? `Overdue: ${counts.expired}` : ''; }
            const active = node.querySelector('[data-rule-active]');
            if (active) {
                active.hidden = !counts?.active;
                active.textContent = counts ? `Awaiting results: ${counts.active}` : '';
            }
            const cursor = node.querySelector('[data-rule-cursor]');
            if (cursor) {
                cursor.hidden = !latest.has(node.dataset.ruleId);
                cursor.textContent = 'Last assignment batch';
            }
            const container = node.querySelector('[data-rule-activity]');
            if (!container) return;
            const focusedLink = container.querySelector('a:focus')?.textContent;
            container.replaceChildren();
            if (!value) return;
            const line = text => {
                const p = doc.createElement('p'); p.textContent = text; container.append(p); return p;
            };
            const link = (p, imageId, label) => {
                if (!/^[0-9a-f]{64}$/.test(imageId || '')) return;
                const a = doc.createElement('a'); a.textContent = label;
                a.href = '/admin/screenshots?imageId=' + encodeURIComponent(imageId); p.append(a);
            };
            line(value.lastIssuedAt ? `Last assigned: ${formatAiTime(value.lastIssuedAt)} · ${value.lastIssuedCount} tasks`
                : 'Last assigned: no recorded activity');
            line(value.lastResultAt ? `Last accepted result: ${formatAiTime(value.lastResultAt)}` : 'Last accepted result: no recorded activity');
            line(counts ? `Processing: ${counts.processing} · Awaiting results: ${counts.active} · Deadline exceeded: ${counts.expired}`
                : 'Assignment counts unavailable. Refresh activity to retry.');
            if (counts && counts.processing > counts.active + counts.expired) {
                line(`Unknown deadline: ${counts.processing - counts.active - counts.expired}`);
            }
            if (value.expired > 0) {
                const p = line(`Response deadline exceeded: ${value.expired}. Oldest deadline: ${formatAiTime(value.oldestDeadline)}. `);
                p.dataset.error = 'true'; link(p, value.expiredImageId, 'Inspect an overdue task');
            }
            if (value.lastErrorAt) {
                const p = line(`Last recorded error: ${formatAiTime(value.lastErrorAt)} · ${value.lastErrorCode || ''} · ${value.lastErrorMessage || ''} `);
                link(p, value.lastErrorImageId, 'Inspect screenshot');
            }
            if (focusedLink) Array.from(container.querySelectorAll('a'))
                .find(a => a.textContent === focusedLink)?.focus({preventScroll: true});
        });
        const other = byId('ai-other-assignments');
        if (other) {
            const savedIds = new Set(current?.rules.map(rule => rule.id));
            const rows = matches ? activity.rules.filter(value => !savedIds.has(value.ruleId)) : [];
            other.replaceChildren();
            other.hidden = rows.length === 0;
            rows.forEach(value => {
                const counts = assignmentCounts(value), p = doc.createElement('p');
                p.textContent = (value.ruleId ? `Rule ${value.ruleId} (no longer in settings)` : 'No recorded rule')
                    + (counts ? `: Processing ${counts.processing} · Awaiting results ${counts.active} · Deadline exceeded ${counts.expired}`
                        + (counts.processing > counts.active + counts.expired ? ` · Unknown deadline ${counts.processing - counts.active - counts.expired}` : '')
                        : ': assignment counts unavailable');
                other.append(p);
            });
        }
        renderStatistics();
        activityMessage.dataset.error = String(Boolean(activityError));
        activityMessage.textContent = activityError ? `${activityError} Select Refresh activity to retry.`
            : dirty() ? 'Activity applies to saved settings. Save or reload settings to see these rules.'
            : activity && !matches ? 'Settings changed in another session. Reload saved settings to see activity.'
            : activity ? `Activity updated ${formatAiTime(activity.generatedAt)}. Refreshes every 15 seconds while this page is visible.`
            : activityBusy ? 'Loading rule activity…' : 'Rule activity unavailable.';
    }

    async function refreshActivity() {
        if (!activityRefresh || activityBusy || disposed) return;
        const revision = current?.revision;
        activityBusy = true; activityRefresh.disabled = true; activityError = '';
        const controller = new AbortController(); activityController = controller;
        renderActivity();
        try {
            const response = await fetch('/admin/api/ai-queue/operations/activity', {cache: 'no-store', signal: controller.signal});
            if (response.status === 401 || (response.redirected && response.url.includes('/login'))) {
                const signIn = byId('ai-queue-sign-in'); if (signIn) signIn.hidden = false;
                throw new Error('Your session has expired. Sign in again.');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data.detail || data.message || `Request failed (${response.status})`);
            if (!Array.isArray(data.rules) || !Array.isArray(data.lastIssuedRuleIds) || !Number.isSafeInteger(data.revision) || !data.generatedAt) {
                throw new Error('Could not read rule activity.');
            }
            if (!disposed) activity = data;
        } catch (error) {
            activity = null;
            if (error.name !== 'AbortError') activityError = error.message || 'Could not load rule activity.';
        } finally {
            activityBusy = false; activityController = null; activityRefresh.disabled = false;
            if (!disposed) {
                renderActivity();
                if (revision !== current?.revision) void refreshActivity();
            }
        }
    }
    activityRefresh?.addEventListener('click', refreshActivity);

    function renderStatistics() {
        if (!statsMessage) return;
        const matches = current && statistics?.revision === current.revision && !dirty();
        const counts = new Map((matches ? statistics.rules : []).map(value => [value.ruleId, value]));
        Array.from(rules.children).forEach(node => {
            const value = counts.get(node.dataset.ruleId);
            node.querySelectorAll('[data-rule-stat]').forEach(target => {
                target.textContent = value ? Number(value[target.dataset.ruleStat]).toLocaleString('en-US') : '—';
                if (target.dataset.ruleStat === 'processing' && activity?.revision === current?.revision && !dirty()) {
                    const live = assignmentCounts(activity.rules.find(row => row.ruleId === node.dataset.ruleId));
                    if (live) target.textContent = live.processing.toLocaleString('en-US');
                }
                if (target.dataset.ruleStat === 'failed') {
                    if (value) target.setAttribute('href', `/admin/screenshots?aiTaskStatus=FAILED&issuedRuleId=${encodeURIComponent(value.ruleId)}`);
                    else target.removeAttribute('href');
                }
            });
            // Remaining may include expired Processing tasks: never present their sum as a completion percentage.
            const outcome = node.querySelector('[data-rule-outcomes]');
            if (outcome) {
                const valid = value && ['completed', 'failed'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0);
                const total = valid ? value.completed + value.failed : 0;
                outcome.hidden = !total;
                if (total) {
                    outcome.setAttribute('style', `--completed-share: ${value.completed / total * 100}%`);
                    node.querySelector('[data-rule-outcome-label]').textContent =
                        `Retained outcomes · ${value.completed.toLocaleString('en-US')} completed / ${value.failed.toLocaleString('en-US')} failed`;
                }
            }
        });
        statsMessage.dataset.error = String(Boolean(statsError));
        statsMessage.textContent = statsBusy ? 'Refreshing rule statistics…'
            : statsError ? `${statsError} Select Refresh rule statistics to retry.`
            : dirty() ? 'Statistics apply to saved settings. Save or reload settings to see counts for these rules.'
            : statistics && !matches ? 'Settings changed in another session. Reload saved settings to see current counts.'
            : statistics ? `Updated ${formatAiTime(statistics.generatedAt)}. Counts can change as tasks are claimed or added.`
            : 'Rule statistics unavailable.';
    }

    async function refreshStatistics() {
        if (!statsRefresh || statsBusy) return;
        const requestedRevision = current?.revision;
        statsBusy = true;
        statsRefresh.disabled = true;
        statsError = '';
        renderStatistics();
        try {
            const response = await fetch('/admin/api/ai-queue/operations/rules', {cache: 'no-store'});
            if (response.status === 401 || (response.redirected && response.url.includes('/login'))) {
                const link = byId('ai-queue-sign-in');
                if (link) link.hidden = false;
                throw new Error('Your session has expired. Sign in again.');
            }
            const data = await response.json();
            if (!response.ok) throw new Error(data.detail || data.message || `Request failed (${response.status})`);
            if (!Array.isArray(data.rules) || !Number.isSafeInteger(data.revision) || !data.generatedAt) {
                throw new Error('Could not read rule statistics.');
            }
            statistics = data;
        } catch (error) {
            statistics = null;
            statsError = error.message || 'Could not load rule statistics.';
        } finally {
            statsBusy = false;
            statsRefresh.disabled = false;
            renderStatistics();
            if (requestedRevision !== current?.revision) void refreshStatistics();
        }
    }
    statsRefresh?.addEventListener('click', refreshStatistics);

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
        renderStatistics();
        renderActivity();
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
            void refreshStatistics();
            void refreshActivity();
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

    if (activityRefresh && view?.setInterval) {
        let timer = view.setInterval(() => { if (!doc.hidden) void refreshActivity(); }, 15000);
        const visible = () => { if (!doc.hidden) void refreshActivity(); };
        doc.addEventListener('visibilitychange', visible);
        view.addEventListener('pagehide', () => {
            disposed = true; view.clearInterval(timer); timer = null; activityController?.abort();
        });
        view.addEventListener('pageshow', event => {
            if (!event.persisted) return;
            disposed = false;
            if (timer == null) timer = view.setInterval(() => { if (!doc.hidden) void refreshActivity(); }, 15000);
            void refreshActivity();
        });
    }

    perform(() => request(), 'Enabled rules are checked from top to bottom, oldest screenshots within each rule first.');
    return {render, row, draftSettings, dirty, refreshStatistics, refreshActivity};
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = {rulePayload, sortRules, ruleSummary, settingsSnapshot, initializeAiQueue, initializeAiOperations,
        failureExplorerUrl, initializeAiFailures};
}
