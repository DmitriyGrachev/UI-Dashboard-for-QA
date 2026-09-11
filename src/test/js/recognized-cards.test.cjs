const test = require('node:test');
const assert = require('node:assert/strict');
const {recognitionCardTokens} = require('../../main/resources/static/js/recognized-cards.js');

test('renders recognized ranks and suits without guessing missing suits', () => {
    assert.deepEqual(recognitionCardTokens('KC, 6D'), [{rank: 'K', suit: '♣'}, {rank: '6', suit: '♦'}]);
    assert.deepEqual(recognitionCardTokens('Eight_Ace'), [{rank: '8', suit: ''}, {rank: 'A', suit: ''}]);
    assert.deepEqual(recognitionCardTokens('A10J3').map(card => card.rank), ['A', '10', 'J', '3']);
    assert.deepEqual(recognitionCardTokens('A♠ | 7♥'), [{rank: 'A', suit: '♠'}, {separator: '|'}, {rank: '7', suit: '♥'}]);
});

test('keeps ambiguous, unknown and invalid source values intact as text', () => {
    for (const value of [null, '', '—', 'Ace_UNKNOWN', 'KC, ??', '11', '<img src=x>', 'HIDDEN', 'A'.repeat(513)]) {
        assert.equal(recognitionCardTokens(value), null, String(value));
    }
});
