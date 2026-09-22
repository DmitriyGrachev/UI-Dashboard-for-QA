// Real templates and JS, synthetic API only. Run with the browser tool and review-preload-server.cjs.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    const errors = [], requests = [];
    p.on('pageerror', e => errors.push(e.message));
    const items = ['MATCH', 'MISMATCH', 'LOW_CONFIDENCE', 'HAND_COUNT_MISMATCH', 'NO_HANDS_FOUND', null].map((verdict, i) => ({
        imageId: String(i + 1).repeat(64), fileName: `screen-${i}.png`, gameCode: 'bj_igt', sessionId: 'table A&B',
        fileCreatedAt: `2026-09-22T12:0${i}:00Z`, reviewState: i < 2 ? 'CHECKED' : 'UNCHECKED', storageState: 'LOCAL_ONLY',
        aiStatus: verdict ? 'COMPLETED' : 'FAILED', aiVerdict: verdict, aiConfidence: i === 0 ? 95 : i === 1 ? 0 : null,
        decision: i === 0 ? 'ACCEPTED' : i === 1 ? 'REJECTED' : null
    }));
    await context.route('**/admin/api/**', async route => {
        const href = route.request().url(); requests.push(href);
        const url = {pathname: href.replace(/^https?:\/\/[^/]+/, '').split('?')[0]};
        let body;
        if (url.pathname.endsWith('/settings')) body = {games: ['bj_igt'], rules: []};
        else if (url.pathname.endsWith('/storage/status')) body = {enabled: false};
        else if (url.pathname.endsWith('/summary')) body = {totalCount: 6, oldestCreatedAt: items[0].fileCreatedAt, newestCreatedAt: items[5].fileCreatedAt};
        else if (/\/(content|thumbnail)$/.test(url.pathname)) return route.fulfill({contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#243b37"/></svg>'});
        else if (url.pathname === '/admin/api/screenshots') body = {items, nextCreatedAt: null, nextId: null};
        else {
            const item = items.find(item => url.pathname.endsWith('/' + item.imageId)) || items[0];
            body = {...item, imageUrl: `/admin/api/screenshots/${item.imageId}/content`, downloadUrl: '#',
                ai: {status: item.aiStatus, verdict: item.aiVerdict, confidence: item.aiConfidence}};
        }
        return route.fulfill({json: body});
    });
    try {
        await p.goto('http://127.0.0.1:18992/admin/screenshots');
        const results = p.locator('.screenshot-result');
        await results.first().waitFor();
        check((await results.nth(0).innerText()).includes('AI: Matches · 95%'), 'Missing MATCH confidence');
        check((await results.nth(1).innerText()).includes('AI: Mismatch · 0%'), 'Zero confidence lost');
        check((await results.nth(1).innerText()).includes('Operator: Does not match'), 'Operator rejected shown as checked');
        check((await results.nth(2).innerText()).includes('Not enough confidence · —'), 'Missing confidence coerced to zero');
        check(!(await results.nth(5).innerText()).includes('%'), 'Failed task shows a result');
        for (const [width, height, theme] of [[1440,900,'dark'], [1280,720,'light'], [768,900,'dark']]) {
            await p.setViewportSize({width, height});
            await p.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Overflow at ' + width);
        }
        await p.locator('#results-view').selectOption('grid');
        check(await p.locator('.result-thumbnail').count() === 6, 'Grid lost results');
        await results.nth(1).focus(); await p.keyboard.press('Enter');
        await p.waitForFunction(() => document.querySelectorAll('.screenshot-result')[1].getAttribute('aria-pressed') === 'true');
        check(errors.length === 0, errors.join('; '));
        return {passed: 'List outcomes, zero/null confidence, list/grid, keyboard, three viewport sizes', requests: requests.length};
    } finally { await context.close(); }
}
