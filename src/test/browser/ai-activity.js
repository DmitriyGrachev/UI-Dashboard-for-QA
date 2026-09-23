// Run with the Playwright tool's filename argument while review-preload-server.cjs is running.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    const errors = []; p.on('pageerror', error => errors.push(error.message));
    let activityCalls = 0, countCalls = 0, unavailable = false;
    let settings = {revision: 1, enabled: true, games: ['bj_igt'], rules: [
        {id: '00000000-0000-0000-0000-000000000001', name: 'Priority sessions', priority: 1, gameCode: 'bj_igt', enabled: true},
        {id: '00000000-0000-0000-0000-000000000002', name: 'Default', priority: 2, gameCode: 'bj_igt', enabled: true}]};
    const imageId = 'a'.repeat(64);
    await p.route('**/admin/api/ai-queue/**', async route => {
        const path = route.request().url();
        let body;
        if (path.endsWith('/settings')) {
            if (route.request().method() === 'PUT') settings = {...settings, ...route.request().postDataJSON(), revision: settings.revision + 1};
            body = settings;
        } else if (path.endsWith('/activity')) {
            activityCalls++;
            if (unavailable) return route.fulfill({status: 503, json: {message: 'Fixture database unavailable'}});
            body = {revision: settings.revision, generatedAt: '2026-09-17T12:00:00Z', lastIssuedRuleIds: [settings.rules[0].id],
                rules: settings.rules.map((rule, index) => ({ruleId: rule.id, lastIssuedAt: index ? null : '2026-09-17T11:59:00Z',
                    lastIssuedCount: index ? null : 10, lastResultAt: index ? null : '2026-09-17T11:59:45Z',
                    processing: index ? 3 : 10, active: index ? 3 : 8,
                    expired: index ? 0 : 2, oldestDeadline: '2026-09-17T11:58:00Z', expiredImageId: imageId,
                    lastErrorAt: index ? null : '2026-09-17T11:57:00Z', lastErrorImageId: imageId,
                    lastErrorCode: 'AI_REJECTED', lastErrorMessage: 'Cards could not be read. <img onerror=alert(1)>'}))};
        } else if (path.endsWith('/rules')) {
            countCalls++;
            body = {revision: settings.revision, generatedAt: '2026-09-17T12:00:00Z', rules: settings.rules.map(rule =>
                ({ruleId: rule.id, remaining: 1234, processing: 10, completed: 5678, failed: 2}))};
        } else body = {enabled: settings.enabled, hasEligiblePending: true, processing: 10, failed: 2, expired: 2};
        return route.fulfill({json: body});
    });
    try {
        await p.clock.install();
        await p.goto('http://127.0.0.1:18992/admin/ai-queue');
        await p.locator('#ai-activity-message').filter({hasText: 'Activity updated'}).waitFor();
        check((await p.title()).startsWith('AI queue'), 'Wrong page');
        check(await p.locator('[data-rule-cursor]:visible').count() === 1, 'Missing latest batch cursor');
        check(await p.locator('[data-rule-active]:visible').count() === 2, 'Active assignments collapsed into one cursor');
        const first = p.locator('.ai-rule').first();
        await first.locator('summary').click();
        await first.getByRole('link', {name: 'Inspect an overdue task'}).waitFor();
        check(await first.getByRole('link', {name: 'Inspect screenshot', exact: true}).getAttribute('href') === '/admin/screenshots?imageId=' + imageId, 'Incorrect diagnostic link');
        check(await first.locator('[data-rule-activity] img').count() === 0, 'Error message inserted HTML');
        const viewports = [];
        for (const [width, height, theme] of [[1440, 900, 'dark'], [1280, 720, 'light'], [768, 900, 'dark'], [390, 844, 'light']]) {
            await p.setViewportSize({width, height});
            if (await p.evaluate(() => document.documentElement.dataset.theme) !== theme) await p.locator('[data-theme-toggle]').click();
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Horizontal overflow at ' + width);
            await p.mouse.move(0, 0);
            await p.screenshot({path: `C:/Users/dimag/AppData/Local/Temp/rv-ai-activity-${width}.png`, fullPage: true, animations: 'disabled'});
            viewports.push({width, height, theme, passed: true});
        }
        const countsBefore = countCalls, activityBefore = activityCalls;
        await p.setViewportSize({width: 768, height: 900});
        await p.evaluate(() => document.documentElement.style.fontSize = '200%');
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'AI Queue overflow with 200% text');
        await p.evaluate(() => document.documentElement.style.fontSize = '');
        await first.getByRole('link', {name: 'Inspect screenshot', exact: true}).focus();
        await p.clock.fastForward(15000);
        await p.waitForFunction(() => !document.querySelector('#ai-activity-refresh').disabled);
        check(activityCalls === activityBefore + 1 && countCalls === countsBefore, 'Polling reran heavy statistics');
        check(await first.getByRole('link', {name: 'Inspect screenshot', exact: true}).evaluate(node => node === document.activeElement),
            'Activity refresh lost keyboard focus');
        await p.evaluate(() => Object.defineProperty(document, 'hidden', {value: true, configurable: true}));
        await p.clock.fastForward(30000);
        check(activityCalls === activityBefore + 1, 'Hidden page kept polling');
        await p.evaluate(() => { Object.defineProperty(document, 'hidden', {value: false, configurable: true}); document.dispatchEvent(new Event('visibilitychange')); });
        await p.waitForFunction(() => !document.querySelector('#ai-activity-refresh').disabled);
        await first.locator('[name="name"]').fill('Unsaved draft');
        check(await p.locator('[data-rule-cursor]:visible').count() === 0, 'Draft retained runtime cursor');
        await p.getByRole('button', {name: 'Refresh activity', exact: true}).click();
        await p.waitForFunction(() => !document.querySelector('#ai-activity-refresh').disabled);
        check(await first.locator('[name="name"]').inputValue() === 'Unsaved draft', 'Polling discarded draft');
        await first.locator('[name="name"]').fill('Priority sessions');
        await p.getByRole('button', {name: 'Refresh activity', exact: true}).focus();
        await p.keyboard.press('Enter');
        await p.waitForFunction(() => !document.querySelector('#ai-activity-refresh').disabled);
        unavailable = true;
        await p.getByRole('button', {name: 'Refresh activity', exact: true}).click();
        await p.getByText('Fixture database unavailable', {exact: false}).waitFor();
        check(await p.locator('[data-rule-cursor]:visible').count() === 0, 'Failure left stale cursor');
        unavailable = false;
        await p.getByRole('button', {name: 'Refresh activity', exact: true}).click();
        await p.locator('[data-rule-cursor]:visible').waitFor();
        const beforeNavigation = activityCalls;
        await p.evaluate(() => dispatchEvent(new PageTransitionEvent('pagehide', {persisted: true})));
        await p.clock.fastForward(15000);
        check(activityCalls === beforeNavigation, 'Navigation kept the activity timer running');
        await p.evaluate(() => dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true})));
        await p.waitForFunction(() => !document.querySelector('#ai-activity-refresh').disabled);
        check(activityCalls === beforeNavigation + 1, 'Back navigation did not resume activity');
        await first.getByRole('button', {name: 'Move rule down', exact: true}).click();
        check(await p.locator('.ai-rule').first().locator('[name="name"]').inputValue() === 'Default', 'Move down did not reorder the draft');
        check(settings.rules[0].name === 'Priority sessions', 'Moving a rule saved without explicit submission');
        await Promise.all([p.waitForResponse(r => r.request().method() === 'PUT'), p.locator('#ai-queue-save').click()]);
        await p.locator('#ai-queue-message').filter({hasText: 'Saved.'}).waitFor();
        check(settings.rules[0].name === 'Default', 'Save lost the new priority');
        await Promise.all([p.waitForResponse(r => r.request().method() === 'PUT'), p.locator('#ai-queue-stop').click()]);
        await p.locator('#ai-operations-state').filter({hasText: 'Paused'}).waitFor();
        check(settings.enabled === false && settings.rules[0].name === 'Default', 'Pause changed saved rule order');
        check(errors.length === 0, errors.join('; '));
        return {viewports, polling: 'light endpoint only, paused while hidden', draft: 'preserved', errors: 'clear cursor and retry', keyboard: 'passed', consoleErrors: errors};
    } finally { await context.close(); }
}
