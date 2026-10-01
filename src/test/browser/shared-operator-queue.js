// Run with the Playwright tool against review-preload-server.cjs and rendered Thymeleaf fixtures.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1280, height: 720}});
    const p = await context.newPage(), errors = [];
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    p.on('pageerror', error => errors.push(error.message));
    await p.route('**/admin/api/**', route => route.fulfill({json: route.request().url().endsWith('/settings')
        ? {games: ['bj_igt'], rules: []} : {items: [], totalCount: 0, enabled: false}}));
    try {
        await p.goto('http://127.0.0.1:18992/admin/screenshots?aiTaskStatus=FAILED&gameCode=bj_igt');
        await p.waitForFunction(() => !document.querySelector('#search-screenshots').disabled);
        await p.evaluate(() => Object.defineProperty(navigator, 'clipboard', {value: {
            writeText: async value => {window.copiedQueue = value;}
        }, configurable: true}));
        await p.locator('#share-operator-queue').click();
        await p.locator('#operator-queue-share-message').filter({hasText: 'copied'}).waitFor();
        const copied = new URL(await p.evaluate(() => window.copiedQueue));
        check(copied.pathname === '/review' && copied.searchParams.get('aiResult') === 'FAILED'
            && copied.searchParams.get('gameCode') === 'bj_igt', 'Copied queue changed filters');
        await p.evaluate(() => {navigator.clipboard.writeText = async () => {throw new Error('Denied');};});
        await p.locator('#share-operator-queue').click();
        await p.locator('#operator-queue-share-url').waitFor();
        check(await p.locator('#operator-queue-share-url').inputValue() === copied.href, 'Missing clipboard fallback');
        await p.evaluate(() => {document.querySelector('#explorer-file-name').value = 'specific.png';});
        await p.locator('#share-operator-queue').click();
        check(await p.locator('#operator-queue-share-message').getAttribute('data-error') === 'true', 'Unsupported filter silently dropped');
        check(await p.locator('#operator-queue-share-url').isHidden(), 'Stale copy link still visible');
        for (const width of [1280, 768, 390]) {
            await p.setViewportSize({width, height: 800});
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Share controls overflow at ' + width);
        }
        // This rendered fixture is the validated server response for the exact query below.
        await p.goto('http://127.0.0.1:18992/review');
        await p.waitForFunction(() => document.querySelector('#accept-button')?.disabled === false);
        await p.evaluate(() => {
            sessionStorage.setItem('recognition-validator.review-filters', JSON.stringify({sessionId: 'old-session', gameCode: 'other', aiResult: 'MATCHED'}));
        });
        const claim = p.waitForRequest(request => request.url().endsWith('/api/review-tasks/claim'));
        await p.goto('http://127.0.0.1:18992/review?queue=1&gameCode=bj_igt&aiResult=FAILED&tokenId=0&notification=false&createdFrom=2026-10-01T00:00:00Z');
        const body = (await claim).postDataJSON();
        check(body.replaceCurrent === true && body.filters.aiResult === 'FAILED' && body.filters.gameCode === 'bj_igt'
            && body.filters.tokenId === 0 && body.filters.notification === false && !body.filters.sessionId
            && body.filters.createdFrom === '2026-10-01T00:00:00Z', 'Shared filters did not replace the previous queue');
        check(new URL(p.url()).search === '', 'Shared URL was not consumed');
        const reloadClaim = p.waitForRequest(request => request.url().endsWith('/api/review-tasks/claim'));
        await p.reload();
        check((await reloadClaim).postDataJSON().filters.aiResult === 'FAILED', 'Reload lost imported filters');
        check(errors.length === 0, errors.join('\n'));
        return {passed: true, checked: 'copy, clipboard fallback, unsupported filters, responsive controls, imported queue and reload'};
    } finally { await context.close(); }
}
