// Uses real Thymeleaf fixtures; run review-preload-server.cjs after the fixture rendering test.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (value, message) => { if (!value) throw new Error(message); };
    const errors = [], requests = [], screenshots = [];
    let unavailable = false;
    p.on('pageerror', error => errors.push(error.message));
    await p.route('**/admin/api/**', async route => {
        requests.push(route.request().url());
        check(route.request().url().endsWith('/ai-queue/operations'), 'Overview requested heavy rule counts or unrelated data');
        await route.fulfill(unavailable ? {status: 503, json: {message: 'AI statistics unavailable'}} :
            {json: {enabled: true, hasEligiblePending: true, processing: 18, failed: 3, expired: 2, lastResult: '2026-09-23T10:00:00Z'}});
    });
    try {
        await p.goto('http://127.0.0.1:18992/admin/overview');
        await p.locator('#ai-operations-state').filter({hasText: 'Allowed'}).waitFor();
        check(await p.locator('.admin-navigation [aria-current="page"]').innerText() === 'Overview', 'Overview navigation is not selected');
        check(await p.locator('.overview-day').count() === 7, 'Incorrect chart period');
        check(await p.locator('#overview-operator-total').innerText() === '3,775', 'Incorrect operator total');
        check(await p.locator('#overview-ai-total').innerText() === '4,615', 'Incorrect AI total');
        check(await p.locator('#ai-queue-stop').count() === 0, 'Overview exposes a settings mutation');
        for (const [width, height, theme] of [[1440, 900, 'light'], [1440, 900, 'dark'], [1280, 720, 'light'], [768, 900, 'dark'], [390, 844, 'light']]) {
            await p.setViewportSize({width, height});
            if (await p.evaluate(() => document.documentElement.dataset.theme) !== theme) await p.locator('[data-theme-toggle]').click();
            await p.mouse.move(0, 0);
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Overview overflow at ' + width);
            const path = `C:/Users/dimag/AppData/Local/Temp/rv-overview-${width}-${theme}.png`;
            await p.screenshot({path, fullPage: true, animations: 'disabled'}); screenshots.push(path);
        }
        await p.setViewportSize({width: 768, height: 900});
        await p.evaluate(() => document.documentElement.style.fontSize = '200%');
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Overview overflow with 200% text');
        await p.evaluate(() => document.documentElement.style.fontSize = '');
        await p.locator('.overview-day a').first().focus();
        await p.keyboard.press('Tab');
        check(await p.locator('.overview-day').first().locator('.overview-chart-tip').isVisible(), 'Chart details unavailable to keyboard');
        check((await p.locator('.overview-day a').first().getAttribute('aria-label')).includes('operator reviews'), 'Missing chart accessibility text');
        const query = async locator => locator.evaluate(a => Object.fromEntries(new URL(a.href).searchParams));
        const operatorLink = await query(p.locator('.overview-day a').first());
        const aiLink = await query(p.locator('.overview-day a').nth(1));
        check(operatorLink.reviewedFrom === '2026-09-17T00:00:00Z' && operatorLink.reviewedTo === '2026-09-18T00:00:00Z' && operatorLink.reviewState === 'CHECKED', 'Operator bar has wrong review window');
        check(aiLink.aiReviewedFrom === operatorLink.reviewedFrom && aiLink.aiReviewedTo === operatorLink.reviewedTo && aiLink.aiResult === 'CHECKED' && !aiLink.createdFrom, 'AI bar uses screenshot creation time');
        const mismatchLink = await query(p.getByRole('link', {name:'View AI mismatches →'}));
        check(mismatchLink.aiResult === 'UNMATCHED' && mismatchLink.aiReviewedFrom === operatorLink.reviewedFrom && mismatchLink.aiReviewedTo === '2026-09-24T00:00:00Z', 'Metric does not preserve the full period');
        check((await query(p.getByRole('link', {name:'View operator rejects →'}))).decision === 'REJECTED', 'Rejects link lacks decision filter');
        await p.getByText('View daily figures', {exact: true}).click();
        check(await p.locator('.overview-data tbody tr').count() === 7, 'Daily table and chart disagree');
        await p.getByLabel('Period · UTC').selectOption('30');
        await Promise.all([p.waitForURL('**/admin/overview?days=30'), p.getByRole('button', {name: 'Apply', exact: true}).click()]);
        check(await p.locator('.overview-day').count() === 30, 'Period form did not load thirty days');
        check(await p.getByLabel('Period · UTC').inputValue() === '30', 'Selected period was lost');
        await p.setViewportSize({width: 390, height: 844});
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Thirty-day chart overflows narrow window');
        unavailable = true;
        await p.getByRole('button', {name: 'Refresh', exact: true}).click();
        await p.locator('#ai-operations-message').filter({hasText: 'AI statistics unavailable'}).waitFor();
        check((await p.locator('#ai-operations-message').innerText()).includes('may be out of date'), 'Stale live values are not identified');
        check(await p.locator('.overview-day').count() === 30, 'Live error replaced historical chart');
        unavailable = false;
        await p.getByRole('button', {name: 'Refresh', exact: true}).click();
        await p.waitForFunction(() => document.querySelector('#ai-operations-message').dataset.error === 'false');
        await p.goto('http://127.0.0.1:18992/admin/overview?case=empty');
        await p.getByText('No reviews in this period', {exact: true}).waitFor();
        check(await p.locator('.overview-day').count() === 0 && await p.locator('#overview-operator-total').innerText() === '0', 'Empty period contains fabricated activity');
        check(await p.locator('.overview-ring strong').allTextContents().then(values => values.every(value => value === '—')), 'Empty ring implies a match rate');
        check(errors.length === 0, errors.join('; '));
        return {passed: '7/30-day periods, accessible figures, empty state, live error/retry, five layouts and 200% text', requests: requests.length, screenshots, errors};
    } finally { await context.close(); }
}
