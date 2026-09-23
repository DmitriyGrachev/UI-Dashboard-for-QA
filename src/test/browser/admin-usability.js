// Real templates and JS, synthetic API only. Run with the browser tool and review-preload-server.cjs.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    const errors = [], requests = [], screenshots = [];
    let searchState = 'ready';
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
        else if (url.pathname === '/admin/api/screenshots') {
            if (searchState === 'error') return route.fulfill({status: 503, json: {detail: 'Test database unavailable'}});
            body = {items: searchState === 'empty' ? [] : items,
                nextCreatedAt: href.includes('cursorId=') || searchState === 'empty' ? null : items[5].fileCreatedAt,
                nextId: href.includes('cursorId=') || searchState === 'empty' ? null : items[5].imageId};
        }
        else {
            const item = items.find(item => url.pathname.endsWith('/' + item.imageId)) || items[0];
            body = {...item, imageUrl: `/admin/api/screenshots/${item.imageId}/content`, downloadUrl: '#',
                dealerCards: 'KS', activeUserCards: 'AC 8H', inactiveUserCards: '7D 4C', hit: true, stand: true,
                ai: {status: item.aiStatus, verdict: item.aiVerdict, confidence: item.aiConfidence, message: 'Test result, not production data.'}};
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
        check(await p.locator('.result-thumbnail[loading="lazy"]').count() === 6, 'List thumbnails are not lazy');
        await p.evaluate(() => { window.initialThumbnail = document.querySelector('.result-thumbnail'); });
        await p.getByRole('checkbox', {name: 'Show cards'}).check();
        for (const [index, value] of [[0, '95'], [1, '0'], [2, null], [5, null]]) {
            await results.nth(index).click();
            await p.waitForFunction(() => !document.querySelector('#detail-content').hidden);
            const meter = p.getByRole('meter', {name: 'AI confidence'});
            if (value === null) check(await meter.count() === 0, 'Unknown or failed confidence has a meter');
            else check(await meter.getAttribute('value') === value, 'Incorrect confidence meter');
        }
        await results.first().click();
        check(await p.evaluate(() => window.initialThumbnail === document.querySelector('.result-thumbnail')), 'Selection recreated thumbnails');
        await p.waitForFunction(() => !document.querySelector('#detail-content').hidden);
        check(await p.locator('#detail-actions button').count() === 0, 'Recognized actions look actionable');
        for (const [width, height, theme] of [[1440,900,'dark'], [1440,900,'light'], [1280,720,'dark'], [1280,720,'light'], [768,900,'dark'], [390,844,'light']]) {
            await p.setViewportSize({width, height});
            if (await p.evaluate(() => document.documentElement.dataset.theme) !== theme) {
                await p.locator('[data-theme-toggle]').click();
            }
            await p.locator('h1').hover();
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Overflow at ' + width);
            const path = `C:/Users/dimag/AppData/Local/Temp/rv-explorer-${width}-${theme}.png`;
            await p.screenshot({path, fullPage: true, animations: 'disabled'});
            screenshots.push(path);
        }
        await p.setViewportSize({width:1440,height:900});
        await p.locator('#results-view').selectOption('grid');
        check(await p.locator('.result-thumbnail').count() === 6, 'Grid lost results');
        await results.nth(1).focus(); await p.keyboard.press('Enter');
        await p.waitForFunction(() => document.querySelectorAll('.screenshot-result')[1].getAttribute('aria-pressed') === 'true');
        await p.waitForFunction(() => !document.querySelector('#detail-content').hidden);
        await p.evaluate(() => document.querySelector('#open-screenshot-session').closest('details').open = true);
        const session = p.locator('#open-screenshot-session');
        await session.waitFor();
        check(await session.getAttribute('href') === '/admin/screenshots?gameCode=bj_igt&sessionId=table+A%26B', 'Session scope or encoding incorrect');
        await session.focus();
        const popupPromise = p.waitForEvent('popup');
        await p.keyboard.press('Enter');
        const popup = await popupPromise;
        await popup.locator('.screenshot-result').first().waitFor();
        check(await popup.evaluate(() => !window.opener), 'Session tab has an opener');
        check(await p.locator('.screenshot-result').nth(1).getAttribute('aria-pressed') === 'true', 'Session link changed source selection');
        await popup.close();
        await p.setViewportSize({width:1440,height:900});
        await p.locator('.explorer-period > summary').click();
        await p.clock.install({time: new Date('2026-09-30T23:59:00Z')});
        await p.locator('#explorer-date-preset').selectOption('today');
        await p.locator('#search-screenshots').click();
        await p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
        await p.locator('.explorer-saved-filters > summary').click();
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
        await p.locator('.explorer-saved-filters > summary').click();
        await p.locator('#saved-filter-select').selectOption('');
        await p.locator('#saved-filter-select').selectOption('0');
        await p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
        check(await p.locator('#explorer-created-from').inputValue() === '2026-10-01T00:00', 'Saved today did not advance');
        await p.locator('.explorer-period > summary').click();
        await p.locator('#explorer-created-from-date').fill('29.09.2026');
        check(await p.locator('#explorer-date-preset').inputValue() === '', 'Manual date edit kept relative mode');
        await p.keyboard.press('Escape');
        check(await p.locator('.flatpickr-calendar.open').count() === 0, 'Escape left the calendar open');
        check(await p.locator('.explorer-period').getAttribute('open') !== null, 'Calendar Escape closed the parent filter');
        await p.keyboard.press('Escape');
        check(await p.locator('.explorer-period > summary').evaluate(el => el === document.activeElement), 'Escape lost focus');
        await p.locator('.explorer-more-filters > summary').click();
        await p.locator('#explorer-confidence-from').fill('0');
        check(await p.locator('#explorer-more-count').innerText() === '1', 'Zero confidence omitted from active filter count');
        await p.locator('#explorer-confidence-from').fill('101');
        await p.keyboard.press('Escape');
        const searchesBeforeInvalid = requests.filter(url => /screenshots\?/.test(url)).length;
        await p.locator('#search-screenshots').click();
        check(await p.locator('.explorer-more-filters').getAttribute('open') !== null, 'Invalid hidden field did not open its panel');
        check(requests.filter(url => /screenshots\?/.test(url)).length === searchesBeforeInvalid, 'Invalid filter sent a request');
        await p.keyboard.press('Escape');
        const exportsBeforeInvalid = requests.filter(url => url.includes('export.csv')).length;
        await p.locator('#download-screenshot-csv').click();
        check(await p.locator('.explorer-more-filters').getAttribute('open') !== null, 'CSV hid the invalid field');
        check(requests.filter(url => url.includes('export.csv')).length === exportsBeforeInvalid, 'Invalid CSV started a download');
        await p.locator('#explorer-confidence-from').fill('0');
        await p.locator('#search-screenshots').click();
        await p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
        check(await p.locator('.explorer-filter-popup[open]').count() === 0, 'Search did not close panels');
        check(requests.some(url => url.includes('confidenceFrom=0')), 'Search lost zero confidence');
        await p.locator('#explorer-zoom-in').click();
        const zoom = await p.locator('#explorer-zoom-value').innerText();
        await p.locator('#next-screenshot').click();
        check(await p.locator('#explorer-zoom-value').innerText() === zoom, 'Selection reset zoom');
        await p.locator('#explorer-image-stage').focus();
        await p.keyboard.press('Shift+ArrowRight');
        check((await p.locator('#explorer-image').getAttribute('style')).includes('40px'), 'Keyboard pan broke');
        await p.keyboard.press('0');
        check(await p.locator('#explorer-zoom-value').innerText() === '100%', 'Keyboard reset broke');
        await p.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Overflow at 200% text size');
        await p.evaluate(() => { document.documentElement.style.fontSize = ''; });
        searchState = 'empty';
        await p.locator('#search-screenshots').click();
        await p.getByText('No screenshots match these filters.', {exact: true}).waitFor();
        check(await p.locator('#next-screenshot').isDisabled(), 'Empty results allow navigation');
        searchState = 'error';
        await p.locator('#search-screenshots').click();
        await p.getByText('Test database unavailable', {exact: true}).waitFor();
        searchState = 'ready';
        await p.locator('#search-screenshots').click();
        await results.first().waitFor();
        check(errors.length === 0, errors.join('; '));
        return {passed: 'List/grid, confidence, relative dates, pagination/CSV, popup focus and validation, zoom/pan, empty/error recovery, six theme/size combinations and 200% text', requests: requests.length, screenshots};
    } finally { await context.close(); }
}
