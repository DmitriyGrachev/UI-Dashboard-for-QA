// Run against review-preload-server.cjs after rendering the Thymeleaf fixtures.
async page => {
    const context = await page.context().browser().newContext();
    const p = await context.newPage(), errors = [], checked = [];
    p.on('pageerror', error => errors.push(error.message));
    const check = (value, message) => { if (!value) throw Error(message); };
    const item = {imageId:'a'.repeat(64),fileName:'bj_single_deck_ags-long-session-2026-09-30.png',
        gameCode:'bj_single_deck_ags',fileCreatedAt:'2026-09-30T00:00:00Z',reviewState:'UNCHECKED'};
    const rules = [{id:'11111111-1111-1111-1111-111111111111',name:'Single deck — manual inspection',
        gameCode:'bj_single_deck_ags',priority:1,enabled:true}];
    await p.route('**/admin/api/**', route => {
        const path = new URL(route.request().url()).pathname;
        if (/\/(content|thumbnail)$/.test(path)) return route.fulfill({contentType:'image/svg+xml',
            body:'<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#176553"/></svg>'});
        let body = {enabled:true,revision:1,generatedAt:'2026-09-30T12:00:00Z',rules:[],groups:[],lastIssuedRuleIds:[]};
        if (path.endsWith('/settings')) body = {...body,games:['bj_single_deck_ags'],rules};
        if (path.endsWith('/rules')) body.rules = rules.map(rule => ({ruleId:rule.id,remaining:1234,processing:4,completed:500,failed:2}));
        if (path.endsWith('/summary')) body = {totalCount:1};
        if (path === '/admin/api/screenshots') body = {items:[item]};
        if (path.endsWith('/'+item.imageId)) body = {...item,imageUrl:'/admin/api/screenshots/'+item.imageId+'/content'};
        if (path.endsWith('/failures/tasks')) body = {items:[{...item,errorCode:'AI_REJECTED',
            errorMessage:'Unparseable cards in the screenshot.',failedAt:'2026-09-30T12:00:00Z',attemptCount:1}]};
        return route.fulfill({json:body});
    });
    try {
        for (const path of ['/review','/statistics','/history','/admin','/admin/overview','/admin/rejects',
            '/admin/ai-queue#ai-rules-view','/admin/ai-queue#ai-failures','/admin/screenshots']) {
            await p.goto('http://127.0.0.1:18992'+path);
            if (path === '/review') await p.locator('#accept-button:enabled').waitFor();
            if (path === '/review') {
                await p.locator('#filter-toggle').click();
                await p.locator('#ai-verdict').selectOption('MISMATCH');
                await p.locator('#confidence-from').fill('60');
                const request = p.waitForRequest(request => request.url().endsWith('/api/review-tasks/claim')
                    && request.postDataJSON()?.filters?.aiResult === 'FAILED');
                await p.locator('#ai-result').selectOption('FAILED');
                const filters = (await request).postDataJSON().filters;
                check(!filters.aiVerdict && filters.aiConfidenceFrom == null, 'Failed kept completed-result criteria');
                check(await p.locator('#ai-verdict').isDisabled() && await p.locator('#confidence-from').isDisabled(), 'Failed enables conflicting filters');
                await p.reload();
                await p.locator('#accept-button:enabled').waitFor();
                check(await p.locator('#ai-result').inputValue() === 'FAILED', 'Failed not restored');
                if (!await p.locator('#ai-result').isVisible()) await p.locator('#filter-toggle').click();
                await p.locator('#ai-result').selectOption('UNMATCHED');
                check(await p.locator('#ai-verdict').isEnabled(), 'Completed criteria not restored');
                await p.locator('#clear-filters').click();
                await p.locator('#filter-close').click();
            }
            if (path === '/admin/screenshots') await p.locator('#detail-content:visible').waitFor();
            if (path.includes('#ai-failures')) await p.locator('#ai-failure-inspector img').waitFor();
            if (path.includes('#ai-rules-view')) {
                await p.locator('.ai-rule').first().waitFor();
                await p.locator('.ai-rule').first().evaluate(node => node.open=true);
            }
            for (const [width,height,font] of [[1440,900,''],[1280,720,''],[960,540,''],[768,450,''],[390,844,''],[1280,720,'200%'],[768,900,'200%']]) {
                await p.setViewportSize({width,height});
                await p.evaluate(font => document.documentElement.style.fontSize=font,font);
                const label = `${path} ${width}×${height} text=${font||'100%'}`;
                check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Page overflow: '+label);
                // The image may pan; its toolbar and actions must fit their own grid column.
                const clipping = await p.locator('.explorer-viewer, .viewer-panel').evaluateAll(nodes => nodes
                    .filter(node => node.clientWidth && (node.scrollWidth > node.clientWidth+1 || node.scrollHeight > node.clientHeight+1))
                    .map(node => `${node.className}: ${node.clientWidth}/${node.scrollWidth}, ${node.clientHeight}/${node.scrollHeight}`));
                check(!clipping.length,'Clipped viewer: '+label+' '+clipping.join('; '));
                check(await p.evaluate(() => {
                    const nav=document.querySelector('.admin-sidebar .admin-navigation');
                    const storage=document.querySelector('.admin-storage-disclosure');
                    return !nav || !storage || getComputedStyle(nav).flexDirection !== 'column'
                        || nav.getBoundingClientRect().bottom <= storage.getBoundingClientRect().top;
                }), 'Navigation overlaps storage controls: '+label);
                checked.push(label);
            }
            if (path === '/admin/screenshots') {
                await p.setViewportSize({width:1280,height:720});
                for (const theme of ['dark','light']) {
                    await p.evaluate(theme => document.documentElement.dataset.theme=theme,theme);
                    await p.locator('#explorer-image-stage').scrollIntoViewIfNeeded();
                    await p.screenshot({path:`C:/Users/dimag/AppData/Local/Temp/rv-responsive-explorer-${theme}.png`});
                }
            }
            await p.evaluate(() => document.documentElement.style.fontSize='');
        }
        check(errors.length===0,errors.join('; '));
        return {passed:checked.length,description:'9 views, 7 window/text combinations; image controls stay inside their panel',errors};
    } finally { await context.close(); }
}
