// Uses rendered admin fixtures served by review-preload-server.cjs.
async (page) => {
    const context = await page.context().browser().newContext({viewport: {width: 1440, height: 900}});
    const p = await context.newPage();
    const check = (ok, message) => { if (!ok) throw new Error(message); };
    try {
        await p.goto('http://127.0.0.1:18992/admin');
        const toggle = p.locator('#admin-sidebar-toggle');
        check(await toggle.count() === 1, 'Shared navigation toggle is missing');
        await toggle.focus();
        await p.keyboard.press('Enter');
        check(!await p.locator('#admin-sidebar').isVisible(), 'Sidebar did not collapse');
        check(await toggle.getAttribute('aria-expanded') === 'false', 'Collapsed state is not accessible');
        check(await toggle.evaluate(el => el === document.activeElement), 'Collapse lost keyboard focus');
        for (const path of ['/admin/overview', '/admin/screenshots', '/admin/ai-queue', '/admin/rejects']) {
            await p.goto('http://127.0.0.1:18992' + path);
            check(!await p.locator('#admin-sidebar').isVisible(), 'Preference was lost on ' + path);
            check(await toggle.isVisible(), 'Cannot restore navigation on ' + path);
        }
        await p.goto('http://127.0.0.1:18992/admin/screenshots');
        const collapsedWidth = (await p.locator('.explorer-content').boundingBox()).width;
        await toggle.click();
        check(await p.locator('#admin-sidebar').isVisible(), 'Sidebar did not expand');
        check((await p.locator('.explorer-content').boundingBox()).width < collapsedWidth, 'Collapsed rail did not free space');
        await p.reload();
        check(await p.locator('#admin-sidebar').isVisible(), 'Expanded preference was lost');
        for (const theme of ['light', 'dark']) {
            await p.evaluate(value => {document.documentElement.dataset.theme = value;}, theme);
            for (const width of [1440, 1280, 768, 390]) {
                await p.setViewportSize({width, height: 900});
                for (let i = 0; i < 2; i++) {
                    await toggle.click();
                    check(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Navigation overflow');
                    check(await toggle.isVisible(), 'Toggle disappeared');
                }
            }
        }
        await p.addInitScript(() => {
            Storage.prototype.getItem = Storage.prototype.setItem = () => {throw new Error('Storage disabled');};
        });
        await p.reload();
        await toggle.click();
        check(!await p.locator('#admin-sidebar').isVisible(), 'Collapse needs browser storage');
        await toggle.click();
        check(await p.locator('#admin-sidebar').isVisible(), 'Expand needs browser storage');
        return 'Five admin pages, persistence, keyboard, both themes, four widths and unavailable storage passed';
    } finally { await context.close(); }
}
