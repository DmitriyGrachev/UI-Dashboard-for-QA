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
        else if (url.pathname.endsWith('/export.csv')) return route.fulfill({contentType: 'text/csv', body: 'image_id\r\n'});
        else if (url.pathname === '/admin/api/screenshots') body = {items, nextCreatedAt: href.includes('cursorId=') ? null : items[5].fileCreatedAt, nextId: href.includes('cursorId=') ? null : items[5].imageId};
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
        await p.setViewportSize({width:1440,height:900});
        await p.evaluate(() => document.querySelector('#screenshot-filter-form').closest('details').open = true);
        await p.clock.install({time: new Date('2026-09-30T23:59:00Z')});
        await p.locator('#explorer-date-preset').selectOption('today');
        await p.locator('#search-screenshots').click();
        await p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
        await p.locator('#saved-filter-name').fill('Today');
        await p.locator('#save-filter').click();
        const saved = await p.evaluate(() => JSON.parse(localStorage.getItem('recognition-validator.admin-saved-filters'))[0]);
        check(saved.relativeDate.preset === 'today' && !saved.query.includes('createdFrom'), 'Relative preset stored as fixed dates');
        await p.clock.setSystemTime(new Date('2026-10-01T00:01:00Z'));
        await p.locator('#load-more-results').click();
        await p.waitForFunction(() => document.querySelectorAll('.screenshot-result').length === 12);
        check(requests.filter(url => url.includes('cursorId=')).at(-1).includes('createdFrom=2026-09-30'), 'Pagination crossed midnight');
        await p.locator('#download-screenshot-csv').click();
        await p.waitForTimeout(100);
        check(requests.some(url => url.includes('export.csv?') && url.includes('createdFrom=2026-09-30')), 'CSV recalculated the period');
        await p.locator('#saved-filter-select').selectOption('');
        await p.locator('#saved-filter-select').selectOption('0');
        await p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
        check(await p.locator('#explorer-created-from').inputValue() === '2026-10-01T00:00', 'Saved today did not advance');
        await p.locator('#explorer-created-from-date').fill('29.09.2026');
        check(await p.locator('#explorer-date-preset').inputValue() === '', 'Manual date edit kept relative mode');
        check(errors.length === 0, errors.join('; '));
        return {passed: 'List outcomes; relative saved dates across midnight; frozen pagination/CSV; manual date edit; keyboard and three sizes', requests: requests.length};
    } finally { await context.close(); }
}
