function recognitionCardTokens(value) {
    if (!value || value.length > 512) return null;
    const ranks = {ace: 'A', two: '2', three: '3', four: '4', five: '5', six: '6',
        seven: '7', eight: '8', nine: '9', ten: '10', jack: 'J', queen: 'Q', king: 'K'};
    const suits = {C: '♣', D: '♦', H: '♥', S: '♠', '♣': '♣', '♦': '♦', '♥': '♥', '♠': '♠'};
    const result = [];
    for (const part of value.trim().split(/([\s,;_|/]+)/)) {
        if (!part) continue;
        if (/^[\s,;_|/]+$/.test(part)) {
            if (/[;|/]/.test(part)) result.push({separator: part.trim()});
            continue;
        }
        const normalized = ranks[part.toLowerCase()] || part.toUpperCase();
        const cards = [...normalized.matchAll(/(10|[2-9AJQK])([CDHS♣♦♥♠])?/g)];
        // Preserve the entire source when any token is ambiguous or unrecognized.
        if (!cards.length || cards.map(card => card[0]).join('') !== normalized) return null;
        result.push(...cards.map(card => ({rank: card[1], suit: suits[card[2]] || ''})));
    }
    return result.some(card => card.rank) ? result : null;
}

function renderRecognizedCards(element, value) {
    const raw = value == null || value === '' ? '—' : String(value);
    element.dataset.cardRaw = raw;
    element.textContent = raw;
    if (document.documentElement.dataset.cardPresentation !== 'cards') return;
    const cards = recognitionCardTokens(raw);
    if (!cards) return;
    const source = document.createElement('span');
    source.className = 'visually-hidden';
    source.textContent = raw;
    const visual = document.createElement('span');
    visual.className = 'recognized-card-list';
    visual.setAttribute('aria-hidden', 'true');
    visual.title = raw;
    for (const card of cards) {
        const tile = document.createElement('span');
        tile.className = card.separator ? 'recognized-card-separator' : 'recognized-card';
        if (card.suit === '♥' || card.suit === '♦') tile.classList.add('red-suit');
        tile.textContent = card.separator || card.rank + card.suit;
        visual.append(tile);
    }
    element.replaceChildren(source, visual);
}

function bindCardPresentation() {
    const key = 'recognition-validator-card-presentation';
    let saved;
    try { saved = window.localStorage.getItem(key); } catch (_) { /* Text remains the default. */ }
    const controls = document.querySelectorAll('[data-card-presentation]');
    function apply(cards) {
        document.documentElement.dataset.cardPresentation = cards ? 'cards' : 'text';
        controls.forEach(control => { control.checked = cards; });
        document.querySelectorAll('[data-recognized-cards]').forEach(element =>
            renderRecognizedCards(element, element.dataset.cardRaw ?? element.textContent));
    }
    apply(saved === 'cards');
    controls.forEach(control => control.addEventListener('change', () => {
        apply(control.checked);
        try { window.localStorage.setItem(key, control.checked ? 'cards' : 'text'); }
        catch (_) { /* The preference still works for the current page. */ }
    }));
}

if (typeof module !== 'undefined' && module.exports) module.exports = {recognitionCardTokens, renderRecognizedCards, bindCardPresentation};
if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bindCardPresentation, {once: true});
    else bindCardPresentation();
}
