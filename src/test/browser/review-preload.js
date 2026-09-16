// Run through the Playwright browser tool's filename argument while the local fixture server is running.
async (page) => {
    const base = "http://127.0.0.1:18992";
    const results = [];
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    for (const scenario of ["match", "inflight", "mismatch", "preload-error", "filters", "decision-race",
        "retry", "availability", "hold", "decision-error", "expired", "empty", "logout", "pagehide", "hidden"]) {
        const context = await page.context().browser().newContext({viewport: {width: 1280, height: 720}});
        const p = await context.newPage();
        const errors = [];
        p.on("pageerror", e => errors.push(e.message));
        await p.addInitScript(() => {
            const NativeImage = window.Image;
            window.fixtureImages = [];
            window.Image = function (...args) {
                const image = new NativeImage(...args); window.fixtureImages.push(image); return image;
            };
        });
        const ready = () => p.waitForFunction(() => !document.querySelector("#accept-button").disabled, null, {timeout: 10000});
        const counts = async () => (await p.request.get(base + "/fixture/counts")).json();
        const released = () => p.request.post(base + "/fixture/release");
        try {
            await p.goto(base + "/review?case=" + scenario);
            if (scenario === "hold") {
                await p.getByText("The assignment is kept. Select Retry image to try again.").waitFor();
                check(await p.locator("#accept-button").isDisabled(), "Storage failure enabled decisions");
                await p.locator("#review-retry").click();
            }
            await ready();
            if (["logout", "pagehide", "hidden"].includes(scenario)) {
                await p.waitForFunction(() => window.fixtureImages[1]?.hasAttribute("src"));
                await p.evaluate(kind => {
                    if (kind === "logout") {
                        const form = document.querySelector('form[action="/logout"]');
                        form.addEventListener("submit", e => e.preventDefault(), {once: true});
                        form.requestSubmit();
                    } else if (kind === "hidden") {
                        Object.defineProperty(document, "hidden", {value: true, configurable: true});
                        document.dispatchEvent(new Event("visibilitychange"));
                    } else window.dispatchEvent(new Event("pagehide"));
                }, scenario);
                check(await p.evaluate(() => !window.fixtureImages[1].hasAttribute("src")), "Navigation retained preload");
            } else if (["retry", "availability", "hold"].includes(scenario)) {
                const value = await counts();
                check(value.counts["1:content"] === (scenario === "retry" ? 3 : 4), "Retry count changed");
                check((value.counts["1:availability"] || 0) === (scenario === "retry" ? 0 : 1), "Availability behavior changed");
                check(value.decisions === 0, "Image failure submitted a decision");
            } else if (scenario === "filters") {
                await p.waitForFunction(async () => (await (await fetch("/fixture/counts")).json()).counts.remote === 1);
                await p.locator("#filter-toggle").click();
                await p.locator("#session-id").fill("changed");
                await p.waitForFunction(() => document.querySelector("#file-name").textContent === "4.png");
                await ready();
                await released();
                await p.evaluate(() => window.fixtureImages[1].dispatchEvent(new Event("error")));
                check(await p.locator("#file-name").textContent() === "4.png", "Late preload replaced the filtered task");
                check(!await p.locator("#accept-button").isDisabled(), "Late preload disabled the filtered task");
            } else if (scenario === "decision-race") {
                await p.locator("#accept-button").click();
                await p.locator("#filter-toggle").click();
                await p.locator("#session-id").fill("changed");
                await p.waitForTimeout(450); // Let the real filter debounce expire while the decision is held.
                check((await counts()).claims.length === 1, "Filter claim raced the pending decision");
                await released();
                await p.waitForFunction(() => document.querySelector("#file-name").textContent === "4.png");
                await ready();
                check((await counts()).claims.at(-1).filters.sessionId === "changed", "New filter was lost");
            } else if (scenario === "inflight") {
                await p.waitForFunction(async () => (await (await fetch("/fixture/counts")).json()).counts.remote === 1);
                await p.locator("#accept-button").click();
                await p.waitForFunction(() => document.querySelector("#file-name").textContent === "2.png");
                check(await p.locator("#accept-button").isDisabled(), "Undownloaded preload enabled decisions");
                await p.locator("#image-stage").focus();
                await p.keyboard.press("a");
                check((await counts()).decisions === 1, "Shortcut submitted an undecoded image");
                await released();
                await ready();
                const value = await counts();
                check(value.counts["2:content"] === 1 && value.counts.remote === 1, "In-flight image downloaded twice");
            } else {
                if (scenario === "match") {
                    await p.waitForFunction(() => window.fixtureImages[1]?.complete && window.fixtureImages[1]?.naturalWidth);
                    await p.locator("#zoom-in").click();
                    await p.evaluate(() => { window.fixturePreloaded = window.fixtureImages[1]; window.fixtureTransform = document.querySelector("#review-image").style.transform; });
                    await p.locator("#image-stage").focus();
                    await p.keyboard.press("a");
                } else {
                    if (scenario === "preload-error") {
                        await p.waitForFunction(() => window.fixtureImages[1] && !window.fixtureImages[1].hasAttribute("src"));
                    }
                    await p.locator("#accept-button").click();
                }
                if (scenario === "expired") {
                    await p.waitForURL("**/login?expired");
                } else if (scenario === "decision-error") {
                    await p.getByText("Fixture decision failed").waitFor();
                    check(await p.evaluate(() => !window.fixtureImages[1].hasAttribute("src")), "Error retained preload");
                    check((await counts()).decisions === 1, "Decision was retried automatically");
                } else if (scenario === "empty") {
                    await p.getByText("No screenshots are available for these filters.").waitFor();
                    check(await p.locator("#accept-button").isDisabled(), "Empty queue enabled actions");
                    check(await p.evaluate(() => !document.querySelector("#review-image").hasAttribute("src")), "Empty queue retained image");
                } else {
                    const actual = scenario === "mismatch" ? "3" : "2";
                    await p.waitForFunction(id => document.querySelector("#file-name").textContent === id + ".png", actual);
                    await ready();
                    const value = await counts();
                    check(value.counts[actual + ":content"] === (scenario === "preload-error" ? 2 : 1), "Unexpected actual-image download count");
                    if (scenario === "match") {
                        check(value.counts.remote === 1, "307 target downloaded twice");
                        check(await p.evaluate(() => document.querySelector("#review-image") === window.fixturePreloaded), "Did not adopt the loaded DOM element");
                        check(await p.evaluate(() => document.querySelector("#review-image").style.transform === window.fixtureTransform), "Zoom/pan was lost");
                    }
                }
            }
            check(errors.length === 0, scenario + " console errors: " + errors.join("; "));
            results.push({scenario, passed: true});
        } finally {
            await context.close();
        }
    }
    return results;
}
