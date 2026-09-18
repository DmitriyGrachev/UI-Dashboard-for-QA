// Local browser fixture: actual review template/JS, deterministic API and no-store image delivery.
// Run: node src/test/browser/review-preload-server.cjs
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve(__dirname, "../../main/resources");
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5WQAAAAASUVORK5CYII=", "base64");
let scenario = "match", counts = {}, held = [], decisions = 0, claims = [];
const imageUrl = id => "/api/images/" + id + "/content";
const item = id => ({imageId: id, imageUrl: imageUrl(id), fileName: id + ".png", gameCode: "bj_igt", parseStatus: "SUCCESS"});
const payload = (id, next) => ({item: id ? item(id) : null, nextImageId: next || null, nextImageUrl: next ? imageUrl(next) : null});
function send(res, value, status = 200) {
    res.writeHead(status, {"Content-Type": "application/json", "Cache-Control": "no-store"});
    res.end(JSON.stringify(value));
}
function deliver(res) { res.writeHead(200, {"Content-Type": "image/png", "Cache-Control": "no-store"}); res.end(png); }
function increment(key) { return counts[key] = (counts[key] || 0) + 1; }
const remote = http.createServer((req, res) => {
    increment("remote");
    if (scenario === "inflight" || scenario === "filters") held.push(() => deliver(res));
    else deliver(res);
});
const app = http.createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1:18992");
    if (["/review", "/admin/ai-queue", "/admin/screenshots"].includes(url.pathname)) {
        held.forEach(release => release()); held = [];
        scenario = url.searchParams.get("case") || "match"; counts = {}; decisions = 0; claims = [];
        const template = url.pathname === "/review" ? "review.html"
            : url.pathname === "/admin/screenshots" ? "admin-screenshots.html" : "admin-ai-queue.html";
        let html = fs.readFileSync(path.join(root, "templates", template), "utf8")
            .replace(/th:(src|href|action)="@\{([^}]+)\}"/g, '$1="$2"')
            .replace(/th:content="\$\{_csrf.token\}"/, 'content="test"')
            .replace(/th:content="\$\{_csrf.headerName\}"/, 'content="X-CSRF-TOKEN"');
        if (url.pathname === "/admin/screenshots") {
            html = html.replace(/<script[^>]*src="[^"]*flatpickr[^"]*"[^>]*><\/script>/g, "")
                .replace(/<link[^>]*flatpickr[^>]*>/g, "");
        } else html = html.replace(/<script[^>]*src="[^"]*(flatpickr|time-segment-combobox|utc-datetime-picker)[^"]*"[^>]*><\/script>/g, "")
            .replace(/<link[^>]*flatpickr[^>]*>/g, "")
            .replace("</head>", '<script>window.UtcDateTimePicker={createRange:()=>({clear(){}})};</script></head>');
        res.writeHead(200, {"Content-Type": "text/html"}); res.end(html); return;
    }
    if (url.pathname === "/fixture/counts") return send(res, {counts, decisions, claims});
    if (url.pathname === "/fixture/release") {
        const releases = held; held = []; releases.forEach(release => release());
        return send(res, {});
    }
    if (url.pathname === "/api/review-tasks/summary") return send(res, {remaining: 3});
    if (url.pathname.startsWith("/api/review-tasks/")) {
        let body = ""; for await (const chunk of req) body += chunk;
        const request = body ? JSON.parse(body) : {};
        if (url.pathname.endsWith("/claim")) {
            claims.push(request);
            return send(res, request.filters?.sessionId ? payload("4", "5") : payload("1", "2"));
        }
        decisions++;
        if (scenario === "decision-error") return send(res, {detail: "Fixture decision failed"}, 503);
        if (scenario === "expired") return send(res, {}, 401);
        const respond = () => send(res, scenario === "empty" ? payload(null) : payload(scenario === "mismatch" ? "3" : "2"));
        if (scenario === "decision-race") held.push(respond); else respond();
        return;
    }
    const imageMatch = url.pathname.match(/^\/api\/images\/(\d+)\/(content|availability)$/);
    if (imageMatch) {
        const [, id, action] = imageMatch;
        const attempt = increment(id + ":" + action);
        if (action === "availability") return send(res, {}, scenario === "hold" ? 503 : 204);
        if (id === "1" && ["retry", "availability", "hold"].includes(scenario)
            && attempt <= (scenario === "retry" ? 2 : 3)) return send(res, {}, 503);
        if (id === "2" && scenario === "preload-error" && attempt === 1) return send(res, {}, 503);
        if (id === "2" && ["match", "inflight", "filters", "decision-race"].includes(scenario)) {
            res.writeHead(307, {"Location": "http://127.0.0.1:18993/image", "Cache-Control": "no-store"});
            res.end(); return;
        }
        deliver(res); return;
    }
    if (url.pathname === "/login" || url.pathname === "/logout") { res.end("Signed out"); return; }
    // Serve only known static directories; no arbitrary filesystem paths.
    if (/^\/(js|css)\/[a-z0-9.-]+$/.test(url.pathname)) {
        const file = path.join(root, "static", url.pathname);
        if (fs.existsSync(file)) {
            res.writeHead(200, {"Content-Type": url.pathname.endsWith(".js") ? "text/javascript" : "text/css"});
            res.end(fs.readFileSync(file)); return;
        }
    }
    res.writeHead(404); res.end();
});
remote.listen(18993, "127.0.0.1");
app.listen(18992, "127.0.0.1", () => console.log("Review browser fixture http://127.0.0.1:18992/review"));
