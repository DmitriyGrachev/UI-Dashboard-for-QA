// Run against review-preload-server.cjs after rendering the Thymeleaf browser fixtures.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const errors = [], failures = [], requests = [];
    const check = (ok, message) => { if (!ok) failures.push(message); };
    let failMore = true;
    const items = Array.from({length: 55}, (_, i) => ({
        imageId: String(i + 1).padStart(64, '0'), fileName: `screen-${i + 1}.png`, gameCode: 'bj_igt',
        sessionId: 'continuity', fileCreatedAt: new Date(Date.UTC(2026, 8, 28, 10, i)).toISOString(),
        reviewState: 'UNCHECKED', storageState: 'LOCAL_ONLY'
    }));
    p.on('pageerror', error => errors.push(error.message));
    await context.route('**/admin/api/**', async route => {
        const url = new URL(route.request().url()); requests.push(url);
        if (/\/(content|thumbnail)$/.test(url.pathname)) return route.fulfill({contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#243b37"/></svg>'});
        if (url.pathname.endsWith('/settings')) return route.fulfill({json: {games: ['bj_igt'], rules: []}});
        if (url.pathname.endsWith('/storage/status')) return route.fulfill({json: {enabled: false}});
        if (url.pathname.endsWith('/summary')) return route.fulfill({json: {totalCount: 55}});
        if (url.pathname === '/admin/api/screenshots') {
            if (url.searchParams.has('cursorId')) return failMore
                ? route.fulfill({status: 503, json: {detail: 'Temporary database error'}})
                : route.fulfill({json: {items: items.slice(50)}});
            return route.fulfill({json: {items: items.slice(0, 50), nextCreatedAt: items[49].fileCreatedAt, nextId: items[49].imageId}});
        }
        const item = items.find(item => url.pathname.endsWith('/' + item.imageId)) || items[0];
        return route.fulfill({json: {...item, imageUrl: `/admin/api/screenshots/${item.imageId}/content`, downloadUrl: '#'}});
    });
    const ready = () => p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
    try {
        await p.goto('http://127.0.0.1:18992/admin/screenshots?sessionId=continuity');
        await ready();
        await p.evaluate(id => {
            const original = window.fetch;
            window.fetch = (url, options) => String(url).endsWith('/' + id)
                ? new Promise((resolve, reject) => {
                    window.detailsPending = true;
                    options?.signal?.addEventListener('abort', () => {
                        window.detailsAborted = true; reject(new DOMException('Aborted', 'AbortError'));
                    });
                }) : original(url, options);
        }, items[1].imageId);
        await p.locator('.screenshot-result').nth(1).click();
        await p.waitForFunction(() => window.detailsPending);
        await p.locator('.screenshot-result').nth(2).click();
        await p.waitForFunction(() => !document.querySelector('#detail-content').hidden);
        check(await p.evaluate(() => window.detailsAborted === true), 'Obsolete details request was not aborted');
        await p.locator('#load-more-results').click(); await ready();
        check(await p.locator('.screenshot-result').count() === 50, 'Failed append removed existing rows');
        check((await p.locator('#screenshot-results').innerText()).includes('Temporary database error'), 'Append error is not visible');
        failMore = false;
        await p.evaluate(() => {window.firstRow = document.querySelector('.screenshot-result');});
        await p.locator('#load-more-results').click(); await ready();
        check(await p.locator('.screenshot-result').count() === 55, 'Retry did not append the next page');
        check(await p.evaluate(() => window.firstRow === document.querySelector('.screenshot-result')), 'Append recreated existing rows');
        await p.locator('.screenshot-result').last().click();
        await p.waitForFunction(() => !document.querySelector('#detail-content').hidden);
        const before = requests.filter(url => url.pathname === '/admin/api/screenshots').length;
        await p.reload(); await ready();
        check(await p.locator('#explorer-file-summary').innerText() === 'screen-55.png', 'Reload lost the selected screenshot');
        const resumed = requests.filter(url => url.pathname === '/admin/api/screenshots').slice(before);
        check(resumed.length === 1 && resumed[0].searchParams.get('cursorId') === items[49].imageId,
            'Resume should fetch only the selected page, without walking earlier pages');
        check(resumed[0]?.searchParams.get('sessionId') === 'continuity', 'Resume changed the filter scope');
        await p.locator('#search-screenshots').click(); await ready();
        check(await p.locator('#explorer-file-summary').innerText() === 'screen-1.png', 'New search did not return to the first result');
        check(!errors.length, errors.join('\n'));
        if (failures.length) throw new Error(failures.join('\n'));
        return 'Pagination error recovery, incremental rendering, cursor-based resume and fresh search passed';
    } finally { await context.close(); }
}
