// First render fixtures with -Dvalidator.write-browser-fixtures=true; run review-preload-server.cjs.
// Uses real Thymeleaf output and browser controls. All writes are intercepted locally.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    const errors = [], writes = [], screenshots = [];
    p.on('pageerror', error => errors.push(error.message));
    await p.route('**/admin/operators**', async route => {
        writes.push({url: route.request().url(), body: route.request().postData()});
        await route.fulfill({status: 303, headers: {location: '/admin?created'}});
    });
    await p.route('**/admin/rejected-screenshots.zip', async route => {
        writes.push({url: route.request().url(), body: route.request().postData()});
        await route.fulfill({contentType: 'application/zip', headers: {'content-disposition': 'attachment; filename="rejects.zip"'},
            body: 'PK\u0005\u0006' + '\0'.repeat(18)});
    });
    const layouts = async name => {
        for (const [width, height, theme] of [[1440, 900, 'dark'], [1280, 720, 'light'], [768, 900, 'dark'], [390, 844, 'light']]) {
            await p.setViewportSize({width, height});
            if (await p.evaluate(() => document.documentElement.dataset.theme) !== theme) await p.locator('[data-theme-toggle]').click();
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), name + ' page overflow at ' + width);
            const path = `C:/Users/dimag/AppData/Local/Temp/rv-admin-${name}-${width}.png`;
            await p.mouse.move(0, 0);
            await p.screenshot({path, fullPage: true, animations: 'disabled'}); screenshots.push(path);
        }
        await p.setViewportSize({width: 768, height: 900});
        await p.evaluate(() => document.documentElement.style.fontSize = '200%');
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), name + ' overflow with 200% text');
        await p.evaluate(() => document.documentElement.style.fontSize = '');
    };
    try {
        await p.goto('http://127.0.0.1:18992/admin');
        check(await p.locator('[data-operator]').count() === 5, 'Generate real Thymeleaf browser fixtures first');
        check(await p.locator('.admin-navigation [aria-current="page"]').innerText() === 'Operators', 'Wrong active navigation');
        await layouts('operators');
        await p.locator('.table-scroll').focus();
        await p.keyboard.press('ArrowRight');
        await p.waitForFunction(() => document.querySelector('.table-scroll').scrollLeft > 0);
        const manage = p.locator('.operator-manage').first();
        await manage.locator('summary').click();
        let confirmation = '';
        p.once('dialog', async dialog => { confirmation = dialog.message(); await dialog.dismiss(); });
        await manage.getByRole('button', {name: 'Deactivate', exact: true}).click();
        check(confirmation.includes('Olena') && confirmation.includes('unfinished work'), 'Missing deactivation consequence');
        check(writes.length === 0, 'Cancelled deactivation submitted');
        await p.locator('#open-create-operator').click();
        check(await p.locator('#create-operator-panel').evaluate(el => el.open), 'Create action did not open the native disclosure');
        check(await p.locator('#create-operator-panel [name="username"]').evaluate(el => el === document.activeElement), 'Create action did not focus username');
        await p.locator('#create-operator-panel [name="username"]').fill('Test operator');
        await p.locator('#create-operator-panel [name="password"]').fill('short');
        await p.locator('#create-operator-panel button[type="submit"]').click();
        check(writes.length === 0, 'Invalid password submitted');
        await p.locator('#create-operator-panel [name="password"]').fill('fixture-password');
        await Promise.all([p.waitForURL('**/admin?created'), p.locator('#create-operator-panel button[type="submit"]').click()]);
        check(writes[0].body.includes('_csrf=') && writes[0].body.includes('username=Test+operator'), 'Create form lost CSRF or username');

        await p.goto('http://127.0.0.1:18992/admin/rejects');
        await layouts('rejects');
        await p.getByRole('button', {name: 'Open Recognition completed from calendar', exact: true}).click();
        await p.locator('.flatpickr-calendar.open').waitFor();
        await p.keyboard.press('Escape');
        check(await p.locator('.flatpickr-calendar.open').count() === 0, 'Escape did not close the real calendar');
        const previous = p.locator('[name="includePreviouslyDownloaded"]');
        await previous.check();
        await p.locator('[name="aiMismatch"]').selectOption('true');
        check(await previous.isDisabled() && !await previous.isVisible(), 'AI export exposes operator download option');
        await p.locator('#processed-from-date').fill('23.09.2026');
        await p.locator('#processed-to-date').fill('22.09.2026');
        await p.getByRole('button', {name: 'Download ZIP', exact: true}).click();
        check(await p.locator('#rejected-export-date-error').isVisible() && writes.length === 1, 'Invalid date range submitted');
        await p.locator('#processed-from-date').fill('21.09.2026');
        await p.locator('[name="sessionId"]').fill('table A&B');
        const [download] = await Promise.all([p.waitForEvent('download'), p.getByRole('button', {name: 'Download ZIP', exact: true}).click()]);
        check(download.suggestedFilename() === 'rejects.zip', 'ZIP did not use a browser download');
        const body = writes[1].body;
        check(body.includes('aiMismatch=true') && body.includes('sessionId=table+A%26B') && body.includes('_csrf='), 'ZIP parameters or CSRF changed');
        check(body.includes('processedFrom=2026-09-21T') && body.includes('processedTo=2026-09-22T'), 'UTC boundaries changed');
        check(!body.includes('includePreviouslyDownloaded'), 'Disabled operator option leaked into AI export');
        check((await p.locator('#rejected-export-status').innerText()).includes('Download requested'), 'Missing download feedback');
        await p.locator('[name="aiMismatch"]').selectOption('false');
        check(await previous.isEnabled() && await previous.isChecked(), 'Switching back lost the operator option');
        check(errors.length === 0, errors.join('; '));
        return {operators: 'create, validation, keyboard scroll and cancelled deactivation passed',
            rejects: 'source, UTC validation and native POST download passed', layouts: 'four sizes, both themes and 200% text', screenshots, errors};
    } finally { await context.close(); }
}
