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
