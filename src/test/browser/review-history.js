// Run with review-preload-server.cjs and rendered ReviewHistoryWebTest fixtures.
async page => {
    const context = await page.context().browser().newContext({viewport: {width:1440, height:900}});
    const p = await context.newPage(), errors = [], requests = [];
    const check = (ok, message) => {if (!ok) throw Error(message);};
    let unavailable = false;
    p.on('pageerror', error => errors.push(error.message));
    p.on('request', request => requests.push(request.url()));
    await p.route('**/api/images/*/content', route => unavailable ? route.fulfill({status:404})
        : route.fulfill({contentType:'image/svg+xml', headers:{'Cache-Control':'no-store'}, body:'<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#19594e"/><text x="300" y="270" fill="white" font-size="32">Review history fixture</text></svg>'}));
    try {
        await p.goto('http://127.0.0.1:18992/history');
        check(await p.locator('.history-table tbody tr').count() === 25, 'History lost rows');
        check(!requests.some(url => url.includes('/api/images/')), 'History eagerly loads images');
        check(await p.locator('.operator-navigation [aria-current="page"]').innerText() === 'My history', 'History navigation is not active');
        const first = p.locator('.history-preview-link').first();
        await first.focus(); await p.keyboard.press('Enter');
        await p.locator('#history-preview-image:not([hidden])').waitFor();
        check(await p.locator('#history-preview').evaluate(el => el.open), 'Preview did not open');
        check((await p.locator('#history-preview-meta').innerText()).includes('Does not match'), 'Preview lost the decision');
        await p.keyboard.press('Escape');
        check(await first.evaluate(el => el === document.activeElement), 'Closing preview lost keyboard focus');
        check(await p.locator('#history-preview-image').getAttribute('src') === null, 'Closed preview retained its image');
        unavailable = true;
        await first.click();
        await p.getByText('Screenshot unavailable.', {exact:false}).waitFor();
        check(await p.locator('#history-preview-retry').isVisible(), 'Missing retry after image error');
        unavailable = false;
        await p.locator('#history-preview-retry').click();
        await p.locator('#history-preview-image:not([hidden])').waitFor();
        await p.locator('#history-preview-close').click();
        for (const theme of ['light','dark']) {
            await p.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
            for (const [width,height] of [[1440,900],[1280,720],[768,900],[390,844]]) {
                await p.setViewportSize({width,height});
                check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Page overflow at ' + width);
                await first.click();
                await p.locator('#history-preview-image:not([hidden])').waitFor();
                check(await p.locator('#history-preview-close').isVisible(), 'Cannot close narrow preview');
                check(await p.locator('#history-preview').evaluate(el => el.scrollWidth <= el.clientWidth + 1), 'Preview overflow');
                if (width === 1280) await p.screenshot({path:`C:/Users/dimag/AppData/Local/Temp/rv-history-${theme}.png`});
                await p.keyboard.press('Escape');
            }
        }
        await p.evaluate(() => document.documentElement.style.fontSize = '200%');
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Text scaling overflow');
        await first.click(); await p.locator('#history-preview-image:not([hidden])').waitFor();
        await p.locator('#history-preview-close').click();
        check(!requests.some(url => url.includes('/api/review-tasks/')), 'History claimed or changed an assignment');
        await p.goto('http://127.0.0.1:18992/history?case=empty');
        await p.getByText('No reviews to show').waitFor();
        await p.goto('http://127.0.0.1:18992/history?case=error');
        await p.getByRole('link', {name:'Retry',exact:true}).waitFor();
        check(!errors.length, errors.join('\n'));
        return 'Own history, lazy image preview, keyboard focus, retry, empty/error states, no queue requests, both themes/four widths/200% text passed';
    } finally { await context.close(); }
}
