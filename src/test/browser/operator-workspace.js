// Run with the local review-preload-server and rendered OperatorUiRenderingWebTest fixtures.
async (page) => {
    const base = 'http://127.0.0.1:18992';
    const results = [];
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const errors = [];
    p.on('pageerror', error => errors.push(error.message));
    try {
        await p.route('**/api/review-tasks/claim', async route => {
            const response = await route.fetch();
            const body = await response.json();
            Object.assign(body.item, {dealerCards: 'K♠', activeUserCards: 'A♣ 8♥', inactiveUserCards: '7♦ 4♣',
                sessionId: 'capture-session', fileCreatedAt: '2026-09-25T10:42:18Z', hit: true, stand: true,
                parseStatus: 'PARTIAL'});
            await route.fulfill({response, json: body});
        });
        const ready = () => p.locator('#accept-button:enabled').waitFor();
        const counts = async () => (await p.request.get(base + '/fixture/counts')).json();
        await p.goto(base + '/review');
        await ready();
        check(await p.locator('#review-focus-mode').count() === 1, 'Missing optional focus mode');
        const normalStage = await p.locator('#image-stage').boundingBox();
        const beforeFocus = await counts();
        await p.evaluate(() => {window.originalImage = document.querySelector('#review-image');});
        await p.locator('#review-focus-mode').focus(); await p.keyboard.press('Enter');
        check(await p.locator('#review-focus-mode').getAttribute('aria-pressed') === 'true', 'Focus mode is not announced');
        check((await p.locator('#image-stage').boundingBox()).height > normalStage.height, 'Focus mode gives no extra image space');
        check(await p.evaluate(() => window.originalImage === document.querySelector('#review-image')), 'Focus mode replaced the loaded image');
        check(JSON.stringify(await counts()) === JSON.stringify(beforeFocus), 'Layout change made a queue request');
        await p.locator('#review-focus-mode').click();
        check(await p.locator('#recognition-warning, #parse-value').count() === 0, 'Parse status still distracts from review');
        check((await p.locator('#capture-summary').innerText()).includes('UTC'), 'Capture time has no timezone');
        check(await p.locator('#image-stage').getAttribute('aria-busy') === 'false', 'Ready image remains busy');
        check(!await p.locator('.review-ai-disclosure').getAttribute('open'), 'AI guidance takes over the desk');
        await p.getByLabel('Show cards', {exact: true}).check();
        check(await p.locator('#active-cards .recognized-card').count() === 2, 'Card presentation is broken');
        const imageBefore = await p.locator('#image-stage').boundingBox();
        await p.locator('#filter-toggle').click();
        check(await p.locator('#filter-close').evaluate(el => el === document.activeElement), 'Opening filters lost focus');
        const imageAfter = await p.locator('#image-stage').boundingBox();
        check(imageBefore.width === imageAfter.width, 'Filters shrink the image');
        await p.locator('#notification').selectOption('false');
        await p.locator('#confidence-from').fill('0');
        await p.locator('#session-id').fill('a r');
        await p.waitForFunction(() => document.querySelector('#file-name').textContent === '4.png');
        await ready();
        check((await counts()).decisions === 0, 'Typing in filters submitted a decision');
        check((await p.locator('#review-filter-summary').innerText()).includes('Notification: No'), 'False filter missing');
        check((await p.locator('#review-filter-summary').innerText()).includes('Confidence from: 0%'), 'Zero confidence missing');
        await p.locator('#filter-close').click();
        check(await p.locator('#filter-toggle').evaluate(el => el === document.activeElement), 'Closing filters lost focus');
        await p.locator('.review-ai-disclosure > summary').click();
        await p.locator('#faq-open').click();
        await p.keyboard.press('a');
        check((await counts()).decisions === 0, 'Help dialog allowed a decision shortcut');
        await p.keyboard.press('Escape');
        await p.locator('#image-stage').focus();
        await p.keyboard.press('+');
        check(await p.locator('#zoom-value').innerText() === '125%', 'Zoom shortcut stopped working');
        await p.keyboard.press('Shift+ArrowRight');
        check((await p.locator('#review-image').getAttribute('style')).includes('40px'), 'Pan shortcut stopped working');
        await p.keyboard.press('0');
        await p.keyboard.press('a');
        await p.waitForFunction(() => document.querySelector('#file-name').textContent === '2.png');
        await ready();
        check((await counts()).decisions === 1, 'Decision shortcut was not saved');
        check(!await p.locator('#review-ai-warning').isVisible(), 'AI warning leaked into an unchecked task');
        check(!await p.locator('#capture-summary').isVisible(), 'Old capture time leaked into the next task');
        check(await p.locator('.review-ai-disclosure').getAttribute('open') !== null, 'AI guidance preference reset between tasks');
        await p.locator('#filter-toggle').click();
        await p.locator('#clear-filters').click();
        await ready();
        check(await p.locator('#review-filter-summary').innerText() === 'Entire queue · no filters', 'Reset left stale filter context');
        await p.locator('#filter-close').click();
        for (const verdict of ['MATCH', 'MISMATCH', 'LOW_CONFIDENCE', 'HAND_COUNT_MISMATCH', 'NO_HANDS_FOUND']) {
            await p.route('**/api/review-tasks/claim', route => route.fulfill({json: {item: {
                imageId: '1', imageUrl: '/api/images/1/content', parseStatus: 'PARTIAL',
                ai: {status: 'COMPLETED', verdict, valid: verdict === 'MATCH', confidence: 80}
            }}}));
            await p.reload();
            await ready();
            check(await p.locator('#review-ai-warning').isVisible() === (verdict !== 'MATCH'), 'Incorrect AI attention state for ' + verdict);
            if (verdict !== 'MATCH') {
                await p.locator('#review-ai-warning').click();
                check(await p.locator('.review-ai-disclosure').getAttribute('open') !== null, 'AI warning does not open details');
                check((await p.locator('#ai-result-details .ai-status').innerText()) !== 'Matches', 'Wrong verdict in AI details');
            }
            if (verdict === 'NO_HANDS_FOUND') {
                await p.route('**/api/review-tasks/1/decision', route => route.fulfill({json: {item: null}}));
                await p.locator('#accept-button').click();
                await p.getByText('No screenshots are available for these filters.').waitFor();
                check(!await p.locator('#review-ai-warning').isVisible(), 'AI warning remained on an empty queue');
                check(await p.locator('#accept-button').isDisabled(), 'Empty queue allows decisions');
                await p.unroute('**/api/review-tasks/1/decision');
            }
            await p.unroute('**/api/review-tasks/claim');
        }
        for (const status of ['PROCESSING', 'FAILED']) {
            await p.route('**/api/review-tasks/claim', route => route.fulfill({json: {item: {
                imageId: '1', imageUrl: '/api/images/1/content', ai: {status, verdict: 'MISMATCH'}
            }}}));
            await p.reload();
            await ready();
            check(!await p.locator('#review-ai-warning').isVisible(), 'Unfinished AI check shown as a verdict');
            await p.unroute('**/api/review-tasks/claim');
        }
        results.push('Review controls, no parse warnings, completed AI warnings and details');

        for (const theme of ['light', 'dark']) {
            await p.evaluate(value => {localStorage.setItem('recognition-validator-theme', value);}, theme);
            for (const [width, height] of [[1440, 900], [1280, 720], [768, 900], [390, 844]]) {
                await p.setViewportSize({width, height});
                for (const path of ['/review', '/statistics']) {
                    await p.goto(base + path);
                    if (path === '/review') await ready();
                    check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${path} overflow at ${width} ${theme}`);
                    if (path === '/review') {
                        const dock = await p.locator('.verdict-dock').boundingBox();
                        check(dock.y >= 0 && dock.y + dock.height <= height + 1, `Decision dock not visible at ${width}`);
                        check(await p.locator('#reject-button').isVisible(), 'Reject action is hidden');
                        check(await p.locator('.verdict-dock').evaluate(el => el.scrollWidth <= el.clientWidth), 'Decisions overflow their dock');
                    } else {
                        check(await p.locator('.operator-navigation [aria-current="page"]').innerText() === 'My statistics', 'Statistics navigation state lost');
                        await p.locator('.daily-data summary').click();
                        check(await p.locator('.daily-data tbody tr').count() === 7, 'Daily table lost values');
                    }
                }
            }
        }
        results.push('Both themes at desktop, laptop, 768px and 390px');
        await p.goto(base + '/review'); await ready();
        await p.locator('#review-focus-mode').click();
        await p.reload(); await ready();
        check(await p.locator('#review-focus-mode').getAttribute('aria-pressed') === 'true', 'Focus preference was not restored');
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Focus mode overflows a narrow window');
        await p.locator('#review-focus-mode').click();
        await p.setViewportSize({width: 1280, height: 720});
        for (const path of ['/review', '/statistics']) {
            await p.goto(base + path);
            if (path === '/review') await ready();
            await p.evaluate(() => {document.documentElement.style.fontSize = '200%';});
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `Text scaling overflows ${path}`);
            if (path === '/review') check((await p.locator('#image-stage').boundingBox()).height >= 200, 'Text scaling hides the screenshot');
        }
        await p.goto(base + '/statistics?case=empty');
        check(await p.locator('.daily-zero-message').isVisible(), 'Empty statistics has no explanation');
        check(!/NaN|Infinity/.test(await p.locator('body').innerText()), 'Empty statistics produces invalid numbers');
        check(!errors.length, errors.join('\n'));
        results.push('Text scaling, empty statistics, no page errors');
        return results;
    } finally {
        await context.close();
    }
}
