// Run with the browser tool against review-preload-server.cjs (real rendered template and JS).
async page => {
    const context = await page.context().browser().newContext({viewport: {width:1440,height:900}});
    const p = await context.newPage(), errors = [], calls = [];
    p.on('pageerror', e => errors.push(e.message));
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const id = 'a'.repeat(64), missing = 'b'.repeat(64);
    const first = {imageId:id,fileName:'failed-fixture.png',gameCode:'bj_igt',ruleId:null,ruleName:null,
        errorCode:'AI_REJECTED',errorMessage:'Cannot read cards. <img onerror=alert(1)>',failedAt:'2026-09-23T12:00:00Z',attemptCount:3};
    let moreFails = true, empty = false, unauthorized = false;
    await p.route('**/admin/api/**', async route => {
        const url = route.request().url(), path = url.split('?')[0];
        let body = {revision:1,enabled:false,games:['bj_igt'],rules:[],generatedAt:first.failedAt,lastIssuedRuleIds:[]};
        if (path.endsWith('/failures/tasks')) {
            calls.push('?' + url.split('?')[1]);
            if (unauthorized) return route.fulfill({status:401,json:{detail:'Unauthorized'}});
            if (url.includes('beforeId=') && moreFails) {
                moreFails = false; return route.fulfill({status:503,json:{detail:'Log unavailable'}});
            }
            body = empty ? {items:[]} : url.includes('aiOnly=true') ? {items:[first]}
                : url.includes('beforeId=') ? {items:[{...first,imageId:'c'.repeat(64),fileName:'older.png'}]}
                : {items:[first,{...first,imageId:missing,fileName:'missing.png',errorCode:'DELIVERY_UNAVAILABLE',
                    errorMessage:null,failedAt:null,ruleId:'11111111-1111-1111-1111-111111111111'}],nextAt:first.failedAt,nextId:missing};
        } else if (path.endsWith('/thumbnail')) {
            if (path.includes(missing)) return route.fulfill({status:404});
            return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90"><rect width="160" height="90" fill="#176553"/></svg>'});
        } else if (path.endsWith('/failures')) body = {generatedAt:first.failedAt,total:2,groups:[]};
        else if (path.endsWith('/operations')) body = {enabled:false,hasEligiblePending:false,processing:0,failed:2,expired:0};
        return route.fulfill({json:body});
    });
    try {
        await p.goto('http://127.0.0.1:18992/admin/ai-queue#ai-failures');
        await p.locator('#ai-failure-log-message').filter({hasText:'2 failed tasks shown'}).waitFor();
        check(await p.locator('#ai-failures').evaluate(node => node.open), 'Deep link did not open log');
        await p.waitForFunction(() => document.querySelector('.ai-failure-preview img').naturalWidth > 0);
        await p.getByText('Preview unavailable · open details', {exact:true}).waitFor();
        check(await p.locator('.ai-failure-reason img').count() === 0, 'Error message executed as HTML');
        check((await p.locator('.ai-failure-entry').last().textContent()).includes('Error time unavailable'), 'Invented missing error time');
        check(await p.locator('.ai-failure-preview').first().getAttribute('href') === '/admin/screenshots?imageId=' + id, 'Wrong screenshot link');
        await p.locator('#ai-failure-log-more').click();
        await p.getByText('Log unavailable Select Load more failures to retry.',{exact:true}).waitFor();
        check(await p.locator('.ai-failure-entry').count() === 2, 'Pagination failure removed existing log');
        await p.locator('#ai-failure-log-more').click();
        await p.locator('#ai-failure-log-message').filter({hasText:'3 failed tasks shown'}).waitFor();
        check(await p.locator('.ai-failure-preview').last().evaluate(node => node === document.activeElement), 'Load more lost focus instead of moving to the new screenshot');
        check(await p.locator('#ai-failure-log-more').isHidden(), 'Terminal page kept Load more');
        check(calls[1] === calls[2], 'Retry skipped a cursor');
        for (const [width,theme] of [[1440,'dark'],[1280,'light'],[768,'dark'],[390,'light']]) {
            await p.setViewportSize({width,height:900});
            await p.evaluate(theme => document.documentElement.dataset.theme=theme, theme);
            check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Overflow at ' + width);
            await p.locator('#ai-failures').scrollIntoViewIfNeeded();
            await p.screenshot({path:`C:/Users/dimag/AppData/Local/Temp/rv-failed-log-${width}.png`});
        }
        await p.setViewportSize({width:768,height:900});
        await p.evaluate(() => document.documentElement.style.fontSize='200%');
        check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '200% text overflow');
        await p.evaluate(() => document.documentElement.style.fontSize='');
        await p.locator('#ai-failure-ai-only').check();
        await p.locator('#ai-failure-log-message').filter({hasText:'1 failed task shown'}).waitFor();
        check(calls.at(-1) === '?aiOnly=true', 'AI-only filter kept old pagination');
        empty=true; await p.locator('#ai-failures-refresh').click();
        await p.getByText('No current rejections from the AI service.',{exact:true}).waitFor();
        check(await p.locator('.ai-failure-entry').count() === 0, 'Empty response kept stale errors');
        unauthorized=true; await p.locator('#ai-failures-refresh').click();
        await p.locator('#ai-failures-sign-in').waitFor();
        check(errors.length===0, errors.join('; '));
        return {passed:'deep link, previews, missing file, safe errors, pagination/retry, AI-only, empty, auth, four sizes, 200% text',consoleErrors:errors};
    } finally { await context.close(); }
}
