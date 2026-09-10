function aiFilterValues(state, verdict, confidenceFrom, confidenceTo) {
    const values = {aiResult: state || null};
    if (arguments.length > 1) {
        const percentage = value => {
            if (value === null || value === undefined || String(value).trim() === "") return null;
            const number = Number(value);
            return Number.isInteger(number) && number >= 0 && number <= 100 ? number : null;
        };
        values.aiVerdict = verdict || null;
        values.confidenceFrom = percentage(confidenceFrom);
        values.confidenceTo = percentage(confidenceTo);
    }
    return values;
}

function aiResultText(ai) {
    if (!ai) return 'AI: Unchecked';
    const percent = value => value == null ? '—' : `${value}%`;
    if (ai.status !== 'COMPLETED') {
        return `AI: Unchecked (${ai.status})${ai.lastErrorCode ? ` · ${ai.lastErrorCode}` : ''}`
            + `${ai.lastErrorMessage ? `\n${ai.lastErrorMessage}` : ''}`
            + `${ai.lastErrorAt ? `\n${ai.lastErrorAt}` : ''}`;
    }
    return `AI: ${ai.valid ? 'Matched' : 'Unmatched'} · ${ai.verdict || '—'}\n`
        + `certainty: ${percent(ai.certainty)} · confidence: ${percent(ai.confidence)}\n`
        + `${ai.checkedAt || ''}${ai.message ? `\n${ai.message}` : ''}`;
}

if (typeof module !== 'undefined' && module.exports) module.exports = {aiFilterValues, aiResultText};

function aiPresentation(ai) {
    if (!ai || ai.status !== 'COMPLETED') {
        const failed = ai?.status === 'FAILED';
        return {label: failed ? 'Check failed' : ai?.status === 'PROCESSING' ? 'Checking…' : 'Not checked',
            tone: failed ? 'warning' : 'neutral',
            message: ai?.lastErrorMessage || '', confidence: null, certainty: null};
    }
    const labels = {MATCH: 'Matches', MISMATCH: 'Mismatch', LOW_CONFIDENCE: 'Not enough confidence',
        HAND_COUNT_MISMATCH: 'Hand count differs', NO_HANDS_FOUND: 'No hands found'};
    return {label: labels[ai.verdict] || ai.verdict || 'Check complete',
        tone: ai.verdict === 'MATCH' ? 'success' : ai.verdict === 'LOW_CONFIDENCE' ? 'warning' : 'danger',
        message: ai.message || '', confidence: ai.confidence, certainty: ai.certainty};
}

function renderAiResult(container, ai) {
    const result = aiPresentation(ai);
    const doc = container.ownerDocument;
    const node = (tag, className, text) => {
        const el = doc.createElement(tag);
        el.className = className;
        el.textContent = text;
        return el;
    };
    container.replaceChildren();
    const header = node('div', 'ai-card-header', '');
    header.append(node('strong', '', 'AI review'), node('span', `ai-status ${result.tone}`, result.label));
    container.append(header);
    if (ai?.status === 'COMPLETED') {
        const metrics = node('dl', 'ai-metrics', '');
        for (const [label, value] of [['Confidence', result.confidence], ['Certainty', result.certainty]]) {
            const metric = node('div', '', '');
            metric.append(node('dt', '', label), node('dd', '', value == null ? '—' : `${value}%`));
            metrics.append(metric);
        }
        container.append(metrics);
    }
    if (result.message) container.append(node('p', 'ai-explanation', result.message));
    if (ai?.checkedAt || ai?.lastErrorCode) {
        const details = node('details', 'ai-diagnostics', '');
        details.append(node('summary', '', 'Check details'));
        if (ai.checkedAt) details.append(node('p', '', `Checked: ${ai.checkedAt}`));
        if (ai.lastErrorCode) details.append(node('p', '', `Error: ${ai.lastErrorCode}`));
        container.append(details);
    }
}
if (typeof module !== 'undefined' && module.exports) Object.assign(module.exports, {aiPresentation, renderAiResult});
