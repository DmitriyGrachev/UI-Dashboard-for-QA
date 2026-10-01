// Run with Playwright against review-preload-server.cjs and rendered Thymeleaf fixtures.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1280, height: 720}});
    const p = await context.newPage(), errors = [], previews = [], mutations = [];
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    const settings = {revision: 7, enabled: true, games: ['bj_igt', 'bj_single_deck_ags'], rules: [
        {id: '00000000-0000-0000-0000-000000000001', name: 'Priority token', enabled: true, priority: 1, gameCode: 'bj_igt', tokenId: 53},
        {id: '00000000-0000-0000-0000-000000000002', name: 'Default', enabled: true, priority: 2, gameCode: 'bj_igt'}]};
    let mode = 'examples', release;
    p.on('pageerror', error => errors.push(error.message));
    await p.route('**/admin/api/**', async route => {
        const request = route.request(), url = new URL(request.url());
        if (url.pathname.endsWith('/preview')) {
            previews.push({url: url.pathname, body: request.postDataJSON(), headers: request.headers()});
            if (mode === 'pending') await new Promise(resolve => {release = resolve;});
            if (mode === 'error') return route.fulfill({status: 503, json: {message: 'Preview temporarily unavailable'}});
            return route.fulfill({json: {generatedAt: '2026-10-01T12:00:00Z', enabled: mode !== 'disabled',
                hasMore: mode === 'examples', items: mode === 'examples' ? Array.from({length: 10}, (_, i) => ({
                    imageId: String(i + 1).padStart(64, '0'), fileName: '<img onerror=alert(1)>screen-' + i + '.png',
                    gameCode: 'bj_igt', createdAt: '2026-09-30T12:00:00Z'})) : []}});
        }
        if (request.method() !== 'GET') mutations.push(request.url());
        if (url.pathname.endsWith('/thumbnail')) return route.fulfill({contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="54"><rect width="96" height="54" fill="#125c4c"/></svg>'});
        return route.fulfill({json: url.pathname.endsWith('/settings') ? settings :
            {revision: 7, enabled: true, rules: [], generatedAt: '2026-10-01T12:00:00Z', lastIssuedRuleIds: []}});
    });
    try {
        await p.clock.install();
        await p.goto('http://127.0.0.1:18992/admin/ai-queue#ai-rules-view');
        await p.waitForFunction(() => document.querySelectorAll('.ai-rule').length === 2);
        const first = p.locator('.ai-rule').first(), second = p.locator('.ai-rule').nth(1);
        await first.locator('summary').click(); await first.locator('[name="sessionId"]').fill('unsaved-session');
        await second.locator('summary').click();
        await second.locator('[data-preview-rule]').click();
        const dialog = p.locator('#ai-rule-preview');
        await dialog.locator('li').last().waitFor();
        check(previews.length === 1 && previews[0].url.endsWith('/rules/2/preview')
            && previews[0].body.rules[0].sessionId === 'unsaved-session' && previews[0].body.revision === 7,
            'Preview did not use the full unsaved draft and current priority');
        check(Boolean(previews[0].headers['x-csrf-token']), 'Missing CSRF token');
        check(await dialog.locator('li').count() === 10 && (await dialog.innerText()).includes('more available'), 'Missing bounded preview');
        check(await dialog.locator('img[onerror]').count() === 0 && (await dialog.innerText()).includes('<img onerror=alert(1)>'), 'Filename inserted as HTML');
        check((await dialog.locator('a').first().getAttribute('href')).endsWith('1'.padStart(64, '0')), 'Wrong screenshot link');
        for (const [width, theme] of [[1280, 'dark'], [768, 'light'], [390, 'dark']]) {
            await p.setViewportSize({width, height: 800});
            await p.evaluate(theme => {document.documentElement.dataset.theme = theme;}, theme);
            check(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth), 'Preview overflow at ' + width);
            await p.screenshot({path: `C:/Users/dimag/AppData/Local/Temp/rv-rule-preview-${width}.png`});
        }
        await p.evaluate(() => document.documentElement.style.fontSize = '200%');
        check(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth), 'Preview overflow with enlarged text');
        await p.evaluate(() => document.documentElement.style.fontSize = '');
        await p.keyboard.press('Escape');
        check(await second.locator('[data-preview-rule]').evaluate(node => node === document.activeElement), 'Closing preview lost focus');
        check(await first.locator('[name="sessionId"]').inputValue() === 'unsaved-session', 'Preview discarded draft');
        for (const [state, text] of [['error', 'Preview temporarily unavailable'], ['empty', 'No available screenshots'], ['disabled', 'pauses assignments']]) {
            mode = state;
            await second.locator('[data-preview-rule]').click();
            await dialog.locator('#ai-rule-preview-message').filter({hasText: text}).waitFor();
            check(await dialog.locator('li').count() === 0, 'Stale results in ' + state);
            await dialog.getByRole('button', {name: 'Close preview'}).click();
        }
        mode = 'pending';
        await p.evaluate(() => {const original = fetch; window.fetch = (url, options) => {
            if (String(url).endsWith('/preview')) window.previewSignal = options.signal;
            return original(url, options);
        };});
        await second.locator('[data-preview-rule]').click();
        await p.waitForFunction(() => Boolean(window.previewSignal));
        await p.keyboard.press('Escape');
        await p.waitForFunction(() => window.previewSignal.aborted);
        release?.();
        check(mutations.length === 0, 'Preview saved or changed tasks');
        check(errors.length === 0, errors.join('\n'));
        return {passed: true, checked: 'unsaved priority, CSRF, bounded examples, escaping, errors, empty/paused states, close/cancel and responsive dialog'};
    } finally { release?.(); await context.close(); }
}
