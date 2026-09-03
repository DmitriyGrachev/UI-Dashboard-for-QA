function aiFilterValues(state) {
    return {aiResult: state || null};
}

function aiResultText(ai) {
    if (!ai) return 'AI: Unchecked';
    const percent = value => value == null ? '—' : `${value}%`;
    if (ai.status !== 'COMPLETED') {
        return `AI: Unchecked (${ai.status})${ai.lastErrorCode ? ` · ${ai.lastErrorCode}` : ''}`;
    }
    return `AI: ${ai.valid ? 'Matched' : 'Unmatched'} · ${ai.verdict || '—'}\n`
        + `certainty: ${percent(ai.certainty)} · confidence: ${percent(ai.confidence)}\n`
        + `${ai.checkedAt || ''}${ai.message ? `\n${ai.message}` : ''}`;
}

if (typeof module !== 'undefined' && module.exports) module.exports = {aiFilterValues, aiResultText};
