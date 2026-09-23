// Actual templates/JS with controlled responses; run with the browser tool while the local fixture server is running.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    const errors = [], requests = [], screenshots = [];
    p.on('pageerror', error => errors.push(error.message));
    p.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    const ruleId = '11111111-1111-1111-1111-111111111111', imageId = 'a'.repeat(64);
    const settings = {revision: 1, enabled: true, games: ['bj_igt'], rules: [
        {id: ruleId, name: 'Priority sessions', priority: 1, gameCode: 'bj_igt', enabled: true}]};
    const item = {imageId, fileName: 'fixture.png', gameCode: 'bj_igt', sessionId: 'fixture',
        fileCreatedAt: '2026-09-18T12:00:00Z', reviewState: 'UNCHECKED', storageState: 'LOCAL_ONLY', aiStatus: 'FAILED'};
    let empty = false, namesUnavailable = false, namesFailed = false, failuresUnavailable = false;
    let failureCalls = 0;
    await context.route('**/admin/api/**', async route => {
        const url = route.request().url();
        requests.push(url);
        let body;
        if (url.includes('/ai-queue/settings')) {
            if (namesFailed) return route.fulfill({status: 503, json: {detail: 'Settings unavailable'}});
            // A valid empty rules list also models a rule deleted after assignment.
            body = namesUnavailable ? {...settings, rules: []} : settings;
        } else if (url.endsWith('/operations/rules')) {
            body = {revision: 1, generatedAt: item.fileCreatedAt,
                rules: [{ruleId, remaining: 100, processing: 5, completed: 200, failed: 2}]};
        } else if (url.endsWith('/operations/activity')) {
            body = {revision: 1, generatedAt: item.fileCreatedAt, lastIssuedRuleIds: [], rules: []};
        } else if (url.endsWith('/operations/failures')) {
            failureCalls++;
            if (failuresUnavailable) return route.fulfill({status:503,json:{detail:'Failed summary unavailable'}});
            body = {generatedAt:item.fileCreatedAt,total:3,groups:[
                {ruleId,ruleName:'Priority sessions',errorCode:'AI_REJECTED',count:2},
                {ruleId:null,ruleName:null,errorCode:null,count:1}]};
        } else if (url.endsWith('/operations')) {
            body = {enabled: true, hasEligiblePending: true, processing: 5, failed: 2, expired: 0};
        } else if (url.includes('/storage/status')) {
            body = {enabled: false, uploaded: 0, backlog: 0, dueNow: 0, retrying: 0, unavailable: 0};
        } else if (url.includes('/export.csv')) {
            return route.fulfill({headers: {'Content-Disposition': 'attachment; filename="screenshots.csv"'},
                contentType: 'text/csv', body: 'image_id,ai_status\r\n' + imageId + ',FAILED\r\n'});
        } else if (url.includes('/summary')) {
            body = {totalCount: empty ? 0 : 1, oldestCreatedAt: item.fileCreatedAt, newestCreatedAt: item.fileCreatedAt};
        } else if (url.split('?')[0].endsWith('/content') || url.split('?')[0].endsWith('/thumbnail')) {
            return route.fulfill({contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#243b37"/><text x="280" y="270" fill="white" font-size="30">Synthetic screenshot fixture</text></svg>'});
        } else if (url.endsWith('/' + imageId)) {
            body = {...item, imageUrl: `/admin/api/screenshots/${imageId}/content`, downloadUrl: '#',
                ai: {status: 'FAILED', attemptCount: 3, issuedRuleId: ruleId, issuedRuleName: 'Priority sessions',
                    lastErrorCode: 'AI_REJECTED', lastErrorMessage: 'Cards could not be read. <img onerror=alert(1)>',
                    lastErrorAt: item.fileCreatedAt}};
        } else body = {items: empty ? [] : [item], nextCreatedAt: null, nextId: null};
        return route.fulfill({json: body});
    });
    try {
        await p.goto('http://127.0.0.1:18992/admin/ai-queue');
        const failed = p.locator('[data-rule-stat="failed"]');
        await failed.filter({hasText: '2'}).waitFor();
        check(await p.locator('#ai-operations-failed').getAttribute('href') === '/admin/ai-queue#ai-failures', 'Global failure log link');
        check(await failed.getAttribute('href') === `/admin/screenshots?aiTaskStatus=FAILED&issuedRuleId=${ruleId}`, 'Rule filter link');
        check(failureCalls === 0, 'Failures loaded before opening');
        await p.locator('#ai-failures > summary').click();
        await p.locator('.ai-failure-breakdown > summary').click();
        await p.locator('#ai-failures-total:not([hidden])').waitFor();
        check(await p.locator('#ai-failures-groups a').nth(1).getAttribute('href') === '/admin/screenshots?aiTaskStatus=FAILED&issuedRuleMissing=true&aiErrorMissing=true', 'Missing-value link widened the filter');
        for (const width of [1440,1280,768]) {
            await p.setViewportSize({width,height:900});
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Failed summary overflow');
            await p.screenshot({path:`C:/Users/dimag/AppData/Local/Temp/rv-failure-summary-${width}.png`,fullPage:true});
        }
        failuresUnavailable = true;
        await p.locator('#ai-failures-refresh').click();
        await p.getByText('Failed summary unavailable Select Refresh failures to retry.',{exact:true}).waitFor();
        check(await p.locator('#ai-failures-groups tr').count() === 0, 'Stale failure groups left after an error');
        failuresUnavailable = false;
        await p.locator('#ai-failures-refresh').click();
        const groupLink = p.locator('#ai-failures-groups a').first();
        await groupLink.waitFor(); await groupLink.focus();
        await p.keyboard.press('Enter');
        await p.locator('#explorer-ai-details').getByText('Assignment attempts: 3', {exact: true}).waitFor();
        await p.waitForFunction(() => document.querySelector('#explorer-image').naturalWidth > 0);
        check((await p.title()).startsWith('Screenshot explorer'), 'Wrong page');
        check(await p.locator('#explorer-ai-details details').getAttribute('open') !== null, 'Failed diagnostics collapsed');
        check(await p.locator('#explorer-ai-details img').count() === 0, 'Error was rendered as HTML');
        check(await p.locator('#explorer-ai-task-status').inputValue() === 'FAILED', 'Lost task status');
        check(await p.locator('#explorer-issued-rule').inputValue() === ruleId, 'Lost issuing rule');
        check(await p.locator('#explorer-ai-error-code').inputValue() === 'AI_REJECTED', 'Lost failure reason');
        check(requests.some(url => url.includes('/screenshots?') && url.includes('aiTaskStatus=FAILED') && url.includes(`issuedRuleId=${ruleId}`)), 'Search omitted filters');
        for (const [width, height, theme] of [[1440, 900, 'dark'], [1280, 720, 'light'], [768, 900, 'dark']]) {
            await p.setViewportSize({width, height});
            await p.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
            // Open the native filter disclosure to inspect all added controls.
            await p.evaluate(() => document.querySelector('.explorer-more-filters').open = true);
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Horizontal overflow at ' + width);
            const path = `C:/Users/dimag/AppData/Local/Temp/rv-ai-failed-${width}.png`;
            await p.screenshot({path, fullPage: true});
            screenshots.push(path);
        }
        const download = context.waitForEvent('request', {predicate: request => request.url().includes('/export.csv?')});
        await p.getByRole('button', {name: 'Download CSV', exact: true}).click();
        check((await download).url().includes('aiTaskStatus=FAILED'), 'CSV download');
        check(requests.some(url => url.includes('/export.csv?') && url.includes('aiTaskStatus=FAILED') && url.includes(`issuedRuleId=${ruleId}`)), 'CSV omitted filters');
        check(requests.some(url => url.includes('/export.csv?') && url.includes('aiErrorCode=AI_REJECTED')), 'CSV omitted error code');
        namesUnavailable = true;
        await p.reload();
        await p.locator('#explorer-ai-details').getByText('Assignment attempts: 3', {exact: true}).waitFor();
        check(await p.locator('#explorer-issued-rule').inputValue() === ruleId, 'Deleted rule reset the filter');
        empty = true;
        await p.locator('#search-screenshots').click();
        await p.locator('#screenshot-results').getByText('No screenshots match these filters.', {exact: true}).waitFor();
        check(errors.length === 1 && errors[0].includes('503'), errors.join('; '));
        namesFailed = true;
        await p.reload();
        await p.locator('.explorer-more-filters > summary').click();
        await p.locator('#explorer-rule-load-message:not([hidden])').waitFor();
        check(await p.locator('#explorer-issued-rule').inputValue() === ruleId, 'Unavailable settings widened the search');
        check(errors.length === 2 && errors.every(error => error.includes('503')), 'Unexpected errors: ' + errors.join('; '));
        namesFailed = false; empty = false;
        await p.goto('http://127.0.0.1:18992/admin/screenshots?aiTaskStatus=FAILED&issuedRuleMissing=true&aiErrorMissing=true');
        await p.locator('.screenshot-result').first().waitFor();
        check(await p.locator('#explorer-issued-rule').inputValue() === 'missing', 'Missing rule became All');
        check(await p.locator('#explorer-ai-error-missing').inputValue() === 'true', 'Missing reason became Any');
        check(await p.locator('#explorer-ai-error-code').isDisabled(), 'Exact reason still editable in missing mode');
        await p.locator('.explorer-saved-filters > summary').click();
        await p.locator('#saved-filter-name').fill('Missing diagnostics'); await p.locator('#save-filter').click();
        const savedQuery = await p.evaluate(() => JSON.parse(localStorage.getItem('recognition-validator.admin-saved-filters'))[0].query);
        check(savedQuery.includes('issuedRuleMissing=true') && savedQuery.includes('aiErrorMissing=true') && !savedQuery.includes('issuedRuleId='), 'Saved NULL filters changed');
        return {flow: 'Failed counter → filtered Explorer → diagnostics → CSV; deleted rule, empty state and settings failure',
            screenshots, consoleErrors: 'None in normal flow; two expected HTTP 503 responses during injected failures'};
    } finally { await context.close(); }
}
