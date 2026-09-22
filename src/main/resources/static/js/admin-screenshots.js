function utcIso(value) {
    if (!value) return null;
    return `${value.length === 16 ? `${value}:00` : value}Z`;
}

function appendIfPresent(params, name, value) {
    if (value === null || value === undefined) return;
    const normalized = typeof value === "string" ? value.trim() : String(value);
    if (normalized !== "") params.set(name, normalized);
}

function buildSearchParams(filters, cursor = null) {
    const params = new URLSearchParams();
    appendIfPresent(params, "createdFrom", utcIso(filters.createdFrom));
    appendIfPresent(params, "createdTo", utcIso(filters.createdTo));
    appendIfPresent(params, "reviewState", filters.reviewState || "ALL");
    appendIfPresent(params, "gameCode", filters.gameCode);
    appendIfPresent(params, "tokenId", filters.tokenId);
    appendIfPresent(params, "sessionId", filters.sessionId);
    appendIfPresent(params, "imageId", filters.imageId);
    appendIfPresent(params, "fileName", filters.fileName);
    if (filters.reviewState !== "UNCHECKED") {
        appendIfPresent(params, "decision", filters.decision);
        appendIfPresent(params, "reviewedBy", filters.reviewedBy);
        appendIfPresent(params, "reviewedFrom", utcIso(filters.reviewedFrom));
        appendIfPresent(params, "reviewedTo", utcIso(filters.reviewedTo));
    }
    appendIfPresent(params, "storageState", filters.storageState);
    appendIfPresent(params, "parseStatus", filters.parseStatus);
    appendIfPresent(params, "notification", filters.notification);
    appendIfPresent(params, "hasUserHand", filters.hasUserHand);
    appendIfPresent(params, "aiResult", filters.aiResult);
    appendIfPresent(params, "aiTaskStatus", filters.aiTaskStatus);
    appendIfPresent(params, "issuedRuleId", filters.issuedRuleId);
    if (filters.aiErrorCode) params.set("aiErrorCode", filters.aiErrorCode);
    if (filters.aiErrorMissing === true || filters.aiErrorMissing === 'true') params.set("aiErrorMissing", 'true');
    if (filters.issuedRuleMissing === true || filters.issuedRuleMissing === 'true') params.set("issuedRuleMissing", 'true');
    appendIfPresent(params, "aiVerdict", filters.aiVerdict);
    appendIfPresent(params, "confidenceFrom", filters.confidenceFrom);
    appendIfPresent(params, "confidenceTo", filters.confidenceTo);
    params.set("limit", "50");
    if (cursor?.createdAt && cursor?.id) {
        params.set("cursorCreatedAt", cursor.createdAt);
        params.set("cursorId", cursor.id);
    }
    return params;
}

function formatUtcDate(value) {
    if (!value) return "—";
    return new Intl.DateTimeFormat("en-GB", {
        timeZone: "UTC",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    }).format(new Date(value)) + " UTC";
}

function csvExportUrl(filters) {
    const params = buildSearchParams(filters);
    params.delete('limit');
    return `/admin/api/screenshots/export.csv?${params}`;
}

function canonicalUtcMinute(date) {
    return date.toISOString().slice(0, 16);
}

function presetRange(now, {hours = 0, days = 0, day} = {}) {
    if (day === "today" || day === "yesterday") {
        const from = new Date(now);
        from.setUTCHours(0, 0, 0, 0);
        if (day === "yesterday") from.setUTCDate(from.getUTCDate() - 1);
        const to = new Date(from);
        to.setUTCDate(to.getUTCDate() + 1);
        return {from: canonicalUtcMinute(from), to: canonicalUtcMinute(to)};
    }
    const durationMs = (hours + days * 24) * 60 * 60 * 1000;
    const to = new Date(Math.ceil(now.getTime() / 60000) * 60000);
    const from = new Date(to.getTime() - durationMs);
    return {from: canonicalUtcMinute(from), to: canonicalUtcMinute(to)};
}

function sessionExplorerUrl(details) {
    if (!details?.gameCode?.trim() || !details?.sessionId?.trim()) return null;
    return '/admin/screenshots?' + new URLSearchParams({gameCode: details.gameCode, sessionId: details.sessionId});
}

function resolveSavedFilter(saved, now = new Date()) {
    const params = new URLSearchParams(saved.query);
    if (saved.relativeDate == null) return params;
    const {preset, field} = saved.relativeDate;
    if (!["today", "yesterday", "last24h"].includes(preset) || !["created", "reviewed"].includes(field)
            || (field === "reviewed" && params.get("reviewState") === "UNCHECKED")) {
        throw new Error("Saved date period is invalid. Select a valid period and save the filters again.");
    }
    for (const key of ["createdFrom", "createdTo", "reviewedFrom", "reviewedTo"]) params.delete(key);
    const range = presetRange(now, preset === "last24h" ? {hours: 24} : {day: preset});
    params.set(field + "From", utcIso(range.from));
    params.set(field + "To", utcIso(range.to));
    return params;
}

function keyboardAction({key, tagName = "", isContentEditable = false,
    ctrlKey, metaKey, altKey, shiftKey, repeat, defaultPrevented}) {
    if (ctrlKey || metaKey || altKey || repeat || defaultPrevented || (shiftKey && key !== '+')) return null;
    const interactive = ["INPUT", "SELECT", "TEXTAREA", "BUTTON", "A", "SUMMARY"]
        .includes(String(tagName).toUpperCase());
    if (interactive || isContentEditable) return null;
    if (key === "ArrowLeft") return "previous";
    if (key === "ArrowRight") return "next";
    if (key.toLowerCase() === "d") return "download";
    if (key.toLowerCase() === "f") return "fullscreen";
    if (key === "0") return "resetZoom";
    if (key === "+" || key === "=") return "zoomIn";
    if (key === "-") return "zoomOut";
    return null;
}

function storageSupportsTemporaryLink(storageState) {
    return storageState === "B2_ONLY" || storageState === "BOTH";
}

function createTechnicalReport(details) {
    const formatAi = typeof module !== "undefined" && module.exports
        ? require("./ai-result.js").aiResultText : aiResultText;
    const actions = [
        details.stand && "stand",
        details.hit && "hit",
        details.doubleAction && "double",
        details.split && "split",
        details.surrender && "surrender"
    ].filter(Boolean).join(", ") || "—";
    return [
        `File: ${details.fileName || "—"}`,
        `Image ID: ${details.imageId || "—"}`,
        `Game: ${details.gameCode || "—"}`,
        `Token / session: ${details.tokenId ?? "—"} / ${details.sessionId || "—"}`,
        `Created: ${formatUtcDate(details.fileCreatedAt)}`,
        `Dealer: ${details.dealerCards || "—"}`,
        `Active hand: ${details.activeUserCards || "—"}`,
        `Other hands: ${details.inactiveUserCards || "—"}`,
        `Buttons: ${details.buttonsRaw || "—"}`,
        `Available actions: ${actions}`,
        `Notification: ${details.notification ? "Yes" : "No"}`,
        `Parse: ${details.parseStatus || "—"}`,
        `Review: ${details.reviewState || "—"} · ${details.decision || "—"} · ${details.reviewedBy || "—"}`,
        `Reviewed: ${formatUtcDate(details.reviewedAt)}`,
        `Storage: ${details.storageState || "—"}`,
        formatAi(details.ai)
    ].join("\n");
}

function formatLoadedCount(loaded, total) {
    const loadedLabel = Number(loaded).toLocaleString("en-US");
    if (total === null || total === undefined) return `${loadedLabel} loaded`;
    return `Loaded ${loadedLabel} of ${Number(total).toLocaleString("en-US")}`;
}

async function copyShareLink(clipboard, url) {
    const link = new URL(url);
    const selected = link.searchParams.get('selected');
    if (selected) link.searchParams.set('imageId', selected);
    await clipboard.writeText(link.href);
}

async function loadPageThenSummary(loadPage, startSummary, renderPage = () => {}) {
    const page = await loadPage();
    renderPage(page);
    try {
        void Promise.resolve(startSummary(page)).catch(() => {});
    } catch {
        // Summary failures must never hide an already loaded page.
    }
    return page;
}

function nextSearchSequence(current, append) {
    return append ? current : current + 1;
}

function resolveSearchFilters(liveFilters, appliedFilters, append) {
    return append && appliedFilters ? appliedFilters : liveFilters;
}

function filterStatus(liveFilters, appliedFilters, busy) {
    if (busy) return "Searching…";
    if (!appliedFilters) return "Select Search to load screenshots.";
    return buildSearchParams(liveFilters).toString() === buildSearchParams(appliedFilters).toString()
        ? "Filters applied." : "Changes not applied. Select Search.";
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        buildSearchParams,
        csvExportUrl,
        sessionExplorerUrl,
        copyShareLink,
        createTechnicalReport,
        formatUtcDate,
        formatLoadedCount,
        filterStatus,
        keyboardAction,
        loadPageThenSummary,
        nextSearchSequence,
        presetRange,
        resolveSavedFilter,
        resolveSearchFilters,
        storageSupportsTemporaryLink
    };
}

if (typeof document !== "undefined") {
(() => {
    "use strict";

    const byId = id => document.getElementById(id);
    const elements = {
        form: byId("screenshot-filter-form"),
        createdFrom: byId("explorer-created-from"),
        createdTo: byId("explorer-created-to"),
        dateError: byId("explorer-date-range-error"),
        dateField: byId("explorer-date-field"),
        datePreset: byId("explorer-date-preset"),
        reviewState: byId("screenshot-filter-form")?.elements.namedItem("reviewState"),
        gameCode: byId("explorer-game-code"),
        tokenId: byId("explorer-token-id"),
        sessionId: byId("explorer-session-id"),
        decision: byId("explorer-decision"),
        reviewedBy: byId("explorer-reviewed-by"),
        storageState: byId("explorer-storage-state"),
        parseStatus: byId("explorer-parse-status"),
        notification: byId("explorer-notification"),
        hasUserHand: byId("explorer-has-user-hand"),
        aiResult: byId("explorer-ai-result"),
        aiTaskStatus: byId("explorer-ai-task-status"),
        issuedRuleId: byId("explorer-issued-rule"),
        aiErrorCode: byId("explorer-ai-error-code"),
        aiErrorMissing: byId("explorer-ai-error-missing"),
        aiVerdict: byId("explorer-ai-verdict"),
        confidenceFrom: byId("explorer-confidence-from"),
        confidenceTo: byId("explorer-confidence-to"),
        aiDetails: byId("explorer-ai-details"),
        imageId: byId("explorer-image-id"),
        fileName: byId("explorer-file-name"),
        resetFilters: byId("reset-screenshot-filters"),
        searchButton: byId("search-screenshots"),
        filterStatus: byId("explorer-filter-status"),
        results: byId("screenshot-results"),
        resultCount: byId("result-count"),
        loadedResultCount: byId("loaded-result-count"),
        resultRange: byId("result-range"),
        loadMore: byId("load-more-results"),
        previous: byId("previous-screenshot"),
        next: byId("next-screenshot"),
        fileSummary: byId("explorer-file-summary"),
        stage: byId("explorer-image-stage"),
        image: byId("explorer-image"),
        viewerMessage: byId("explorer-viewer-message"),
        zoomOut: byId("explorer-zoom-out"),
        zoomIn: byId("explorer-zoom-in"),
        zoomReset: byId("explorer-zoom-reset"),
        zoomValue: byId("explorer-zoom-value"),
        fullscreen: byId("explorer-fullscreen"),
        download: byId("download-screenshot"),
        temporaryLink: byId("open-temporary-link"),
        copyLink: byId("copy-screenshot-link"),
        copyReport: byId("copy-technical-report"),
        openSession: byId("open-screenshot-session"),
        detailPlaceholder: byId("detail-placeholder"),
        detailContent: byId("detail-content"),
        detailReviewState: byId("detail-review-state"),
        refreshStorage: byId("refresh-storage"),
        storageUploaded: byId("storage-uploaded"),
        storageBacklog: byId("storage-backlog"),
        storageDueNow: byId("storage-due-now"),
        storageRetrying: byId("storage-retrying"),
        storageUnavailable: byId("storage-unavailable"),
        storageOldestPending: byId("storage-oldest-pending"),
        storageMessage: byId("storage-status-message")
    };

    const detailElements = {
        gameCode: byId("detail-game"),
        decision: byId("detail-decision"),
        dealerCards: byId("detail-dealer"),
        activeUserCards: byId("detail-active-hand"),
        inactiveUserCards: byId("detail-other-hands"),
        buttonsRaw: byId("detail-buttons"),
        actions: byId("detail-actions"),
        imageId: byId("detail-image-id"),
        fileName: byId("detail-file-name"),
        tokenId: byId("detail-token-id"),
        sessionId: byId("detail-session-id"),
        fileCreatedAt: byId("detail-created-at"),
        processedAt: byId("detail-processed-at"),
        reviewedBy: byId("detail-reviewed-by"),
        reviewedAt: byId("detail-reviewed-at"),
        recognitionDurationMs: byId("detail-duration"),
        notification: byId("detail-notification"),
        parseStatus: byId("detail-parse-status"),
        storageState: byId("detail-storage-state"),
        cloudUploadedAt: byId("detail-cloud-uploaded-at"),
        payloadRaw: byId("detail-payload")
    };

    if (!elements.form) return;

    const state = {
        items: [],
        selectedIndex: -1,
        selectionSequence: 0,
        selectedDetails: null,
        nextCursor: null,
        totalCount: null,
        searching: false,
        loadingMore: false,
        searchSequence: 0,
        appliedFilters: null,
        scale: 1,
        x: 0,
        y: 0,
        dragging: false,
        pointerStartX: 0,
        pointerStartY: 0,
        originX: 0,
        originY: 0
    };

    let resolvedDateRange = null;
    const dateRange = window.UtcDateTimePicker.createRange({
        fromInput: elements.createdFrom,
        toInput: elements.createdTo,
        errorElement: elements.dateError,
        onCommit: () => {
            if (elements.createdFrom.value !== resolvedDateRange?.from || elements.createdTo.value !== resolvedDateRange?.to) {
                elements.datePreset.value = "";
            }
            updateSearchControls();
        }
    });

    function currentFilters() {
        const reviewed = elements.dateField.value === "reviewed";
        return {
            createdFrom: reviewed ? "" : elements.createdFrom.value,
            createdTo: reviewed ? "" : elements.createdTo.value,
            reviewedFrom: reviewed ? elements.createdFrom.value : "",
            reviewedTo: reviewed ? elements.createdTo.value : "",
            reviewState: elements.reviewState.value,
            gameCode: elements.gameCode.value,
            tokenId: elements.tokenId.value,
            sessionId: elements.sessionId.value,
            decision: elements.decision.value,
            reviewedBy: elements.reviewedBy.value,
            storageState: elements.storageState.value,
            parseStatus: elements.parseStatus.value,
            notification: elements.notification.value,
            hasUserHand: elements.hasUserHand.value,
            aiResult: elements.aiResult.value,
            aiTaskStatus: elements.aiTaskStatus.value,
            issuedRuleId: elements.issuedRuleId.value === 'missing' ? '' : elements.issuedRuleId.value,
            issuedRuleMissing: elements.issuedRuleId.value === 'missing',
            aiErrorCode: elements.aiErrorCode.value,
            aiErrorMissing: elements.aiErrorMissing.value,
            aiVerdict: elements.aiVerdict.value,
            confidenceFrom: elements.confidenceFrom.value,
            confidenceTo: elements.confidenceTo.value,
            imageId: elements.imageId.value,
            fileName: elements.fileName.value
        };
    }

    function updateCheckedOnlyControls() {
        const disabled = elements.reviewState.value === "UNCHECKED";
        elements.decision.disabled = disabled;
        elements.reviewedBy.disabled = disabled;
        if (disabled) {
            elements.decision.value = "";
            elements.reviewedBy.value = "";
            elements.dateField.value = "created";
        }
    }

    function updateSearchControls() {
        const busy = state.searching || state.loadingMore;
        elements.searchButton.disabled = busy;
        elements.resetFilters.disabled = busy;
        elements.form.setAttribute("aria-busy", String(busy));
        document.querySelectorAll("[data-range-hours], [data-range-days], [data-range-day]").forEach(button => {
            button.disabled = busy;
        });
        elements.filterStatus.textContent = filterStatus(currentFilters(), state.appliedFilters, busy);
        if (elements.filterStatus.textContent === 'Filters applied.') {
            const labels = {gameCode: 'Game', imageId: 'Image ID', sessionId: 'Session', tokenId: 'Token',
                createdFrom: 'Created from', createdTo: 'Created to', reviewedFrom: 'Reviewed from',
                reviewedTo: 'Reviewed to', decision: 'Decision', reviewState: 'Review', aiResult: 'AI result',
                aiVerdict: 'AI verdict', aiTaskStatus: 'AI task status', issuedRuleId: 'Issuing rule',
                issuedRuleMissing: 'No recorded rule', aiErrorMissing: 'Reason unavailable', aiErrorCode: 'AI error code',
                confidenceFrom: 'Confidence from', confidenceTo: 'Confidence to'};
            const conditions = Array.from(buildSearchParams(state.appliedFilters))
                .filter(([key, value]) => key !== 'limit' && !(key === 'reviewState' && value === 'ALL'))
                .map(([key, value]) => (labels[key] || key) + ': ' + value);
            elements.filterStatus.textContent = conditions.join(' · ') || 'All screenshots';
        }
        for (const input of [elements.createdFrom, elements.createdTo]) {
            input.closest('[data-utc-boundary]').querySelectorAll('[aria-label]').forEach(control => {
                control.setAttribute('aria-label', control.getAttribute('aria-label')
                    .replace(/Created|Operator reviewed/g, elements.dateField.value === 'reviewed' ? 'Operator reviewed' : 'Created'));
            });
        }
    }

    function showResultsMessage(message) {
        elements.results.replaceChildren();
        const paragraph = document.createElement("p");
        paragraph.className = "explorer-result-message";
        paragraph.textContent = message;
        elements.results.append(paragraph);
    }

    function isLoginResponse(response) {
        if (!response.redirected || !response.url) return false;
        return new URL(response.url, window.location.href).pathname === "/login";
    }

    async function fetchJson(url) {
        const response = await fetch(url, {
            headers: {Accept: "application/json"},
            credentials: "same-origin"
        });
        if (isLoginResponse(response)) {
            window.location.assign("/login?expired");
            throw new Error("Session expired");
        }
        if (!response.ok) {
            let detail = `Request failed (${response.status})`;
            try {
                const problem = await response.json();
                if (problem.detail) detail = problem.detail;
            } catch {
                // The status remains useful when the response is not JSON.
            }
            throw new Error(detail);
        }
        return response.json();
    }

    function writeSearchUrl(selectedId = null) {
        const params = buildSearchParams(state.appliedFilters || currentFilters());
        params.delete("limit");
        if (selectedId) params.set("selected", selectedId);
        const query = params.toString();
        history.replaceState(null, "", query ? `/admin/screenshots?${query}` : "/admin/screenshots");
    }

    function setInputFromQuery(input, params, name, fallback = "") {
        input.value = params.get(name) ?? fallback;
    }

    function writeBoundary(canonicalInput, value) {
        canonicalInput.value = value || "";
        const boundary = canonicalInput.closest("[data-utc-boundary]");
        const parsed = value ? window.UtcDateTimePicker.parseCanonicalUtcDateTime(value) : null;
        const dateInput = boundary.querySelector("[data-date-input]");
        const hourInput = boundary.querySelector("[data-hour-segment] [data-segment-input]");
        const minuteInput = boundary.querySelector("[data-minute-segment] [data-segment-input]");
        dateInput.value = parsed?.date || "";
        hourInput.value = parsed?.hour || "00";
        minuteInput.value = parsed?.minute || "00";
        if (dateInput._flatpickr) dateInput._flatpickr.setDate(dateInput.value, false, "d.m.Y");
    }

    function restoreFromUrl(params = new URLSearchParams(window.location.search)) {
        elements.datePreset.value = "";
        const canonical = value => value ? value.slice(0, 16) : value;
        const reviewed = params.has("reviewedFrom") || params.has("reviewedTo");
        elements.dateField.value = reviewed ? "reviewed" : "created";
        writeBoundary(elements.createdFrom, canonical(params.get(reviewed ? "reviewedFrom" : "createdFrom")) || "");
        writeBoundary(elements.createdTo, canonical(params.get(reviewed ? "reviewedTo" : "createdTo")) || "");
        setInputFromQuery(elements.reviewState, params, "reviewState", "ALL");
        setInputFromQuery(elements.gameCode, params, "gameCode");
        setInputFromQuery(elements.tokenId, params, "tokenId");
        setInputFromQuery(elements.sessionId, params, "sessionId");
        setInputFromQuery(elements.decision, params, "decision");
        setInputFromQuery(elements.reviewedBy, params, "reviewedBy");
        setInputFromQuery(elements.storageState, params, "storageState");
        setInputFromQuery(elements.parseStatus, params, "parseStatus");
        setInputFromQuery(elements.notification, params, "notification");
        setInputFromQuery(elements.hasUserHand, params, "hasUserHand");
        setInputFromQuery(elements.aiResult, params, "aiResult");
        setInputFromQuery(elements.aiTaskStatus, params, "aiTaskStatus");
        ensureRuleOption(params.get("issuedRuleId"));
        setInputFromQuery(elements.issuedRuleId, params, "issuedRuleId");
        if (params.get('issuedRuleMissing') === 'true') elements.issuedRuleId.value = 'missing';
        setInputFromQuery(elements.aiErrorCode, params, 'aiErrorCode');
        elements.aiErrorMissing.value = params.get('aiErrorMissing') === 'true' ? 'true' : '';
        elements.aiErrorCode.disabled = elements.aiErrorMissing.value === 'true';
        setInputFromQuery(elements.aiVerdict, params, "aiVerdict");
        setInputFromQuery(elements.confidenceFrom, params, "confidenceFrom");
        setInputFromQuery(elements.confidenceTo, params, "confidenceTo");
        setInputFromQuery(elements.imageId, params, "imageId");
        setInputFromQuery(elements.fileName, params, "fileName");
        updateCheckedOnlyControls();
        return params.get("selected");
    }

    function ensureRuleOption(id) {
        if (id && !Array.from(elements.issuedRuleId.options).some(option => option.value === id)) {
            elements.issuedRuleId.add(new Option(`Rule ${id} (name unavailable)`, id));
        }
    }

    async function loadIssuingRules() {
        try {
            const settings = await fetchJson('/admin/api/ai-queue/settings');
            const selected = elements.issuedRuleId.value;
            elements.issuedRuleId.replaceChildren(new Option('All issuing rules', ''), new Option('No recorded rule', 'missing'));
            settings.rules.forEach(rule => elements.issuedRuleId.add(
                new Option(`${rule.name}${rule.enabled ? '' : ' (disabled)'}`, rule.id)));
            ensureRuleOption(selected);
            elements.issuedRuleId.value = selected;
        } catch {
            const message = byId('explorer-rule-load-message');
            message.textContent = 'Rule names unavailable. The selected rule ID still applies. Reload the page to retry.';
            message.hidden = false;
        }
    }

    function storageLabel(value) {
        return ({
            LOCAL_ONLY: "Local",
            B2_ONLY: "B2",
            BOTH: "Local + B2",
            MISSING: "Missing"
        })[value] || value || "—";
    }

    function reviewLabel(value) {
        return value === "CHECKED" ? "Operator: Checked" : "Operator: Unchecked";
    }

    function renderResults() {
        const focusedIndex = document.activeElement?.closest?.(".screenshot-result")?.dataset.index;
        elements.results.replaceChildren();
        if (state.items.length === 0) {
            showResultsMessage("No screenshots match these filters.");
            return;
        }
        state.items.forEach((item, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "screenshot-result";
            button.setAttribute("aria-pressed", String(index === state.selectedIndex));
            button.dataset.index = String(index);

            const top = document.createElement("span");
            top.className = "screenshot-result-top";
            const time = document.createElement("time");
            time.textContent = formatUtcDate(item.fileCreatedAt);
            const review = document.createElement("span");
            review.className = "result-badge";
            review.dataset.tone = item.decision === "ACCEPTED" ? "success" : item.decision === "REJECTED" ? "danger" : "neutral";
            review.textContent = item.decision === "ACCEPTED" ? "Operator: Matches"
                : item.decision === "REJECTED" ? "Operator: Does not match" : reviewLabel(item.reviewState);
            top.append(time);
            const statuses = document.createElement("span");
            statuses.className = "screenshot-result-statuses";
            const ai = document.createElement("span");
            ai.className = "result-badge";
            ai.dataset.aiStatus = item.aiStatus || "PENDING";
            const result = aiPresentation({status: item.aiStatus, verdict: item.aiVerdict, confidence: item.aiConfidence});
            ai.dataset.tone = result.tone;
            ai.textContent = "AI: " + result.label + (item.aiStatus === "COMPLETED"
                ? ` · ${result.confidence == null ? "—" : result.confidence + "%"}` : "");
            statuses.append(review, ai);

            const file = document.createElement("strong");
            file.textContent = item.fileName;
            const bottom = document.createElement("span");
            bottom.className = "screenshot-result-bottom";
            const game = document.createElement("span");
            game.textContent = item.gameCode;
            const storage = document.createElement("span");
            storage.textContent = storageLabel(item.storageState);
            bottom.append(game, storage);
            if (byId("results-view").value === "grid") {
                const preview = document.createElement("img");
                preview.className = "result-thumbnail";
                preview.alt = "";
                preview.loading = "lazy";
                preview.decoding = "async";
                preview.width = 320;
                preview.height = 180;
                if (item.storageState !== "MISSING") preview.src = `/admin/api/screenshots/${encodeURIComponent(item.imageId)}/thumbnail`;
                preview.addEventListener("error", () => { preview.alt = "Preview unavailable"; });
                button.append(preview);
            }
            button.append(top, file, statuses, bottom);
            button.addEventListener("click", () => selectResult(index));
            elements.results.append(button);
            if (focusedIndex === String(index)) button.focus({preventScroll: true});
        });
    }

    function updateNavigation() {
        elements.previous.disabled = state.selectedIndex <= 0;
        elements.next.disabled = state.selectedIndex < 0
            || state.selectedIndex >= state.items.length - 1 && !state.nextCursor;
    }

    function updateLoadedCount() {
        elements.loadedResultCount.textContent = formatLoadedCount(
            state.items.length,
            state.totalCount
        );
    }

    async function refreshSummary(params, sequence) {
        try {
            const summary = await fetchJson(`/admin/api/screenshots/summary?${params}`);
            if (sequence !== state.searchSequence) return;
            state.totalCount = Number(summary.totalCount);
            elements.resultCount.textContent = state.totalCount.toLocaleString("en-US");
            elements.resultRange.textContent = state.totalCount
                ? `${formatUtcDate(summary.oldestCreatedAt)} — ${formatUtcDate(summary.newestCreatedAt)}`
                : "—";
            updateLoadedCount();
        } catch {
            if (sequence !== state.searchSequence) return;
            state.totalCount = null;
            elements.resultCount.textContent = "—";
            elements.resultRange.textContent = "Summary unavailable";
            updateLoadedCount();
        }
    }

    function applyPage(page, append) {
        state.items = append ? state.items.concat(page.items) : page.items;
        state.nextCursor = page.nextCreatedAt && page.nextId
            ? {createdAt: page.nextCreatedAt, id: page.nextId}
            : null;
        elements.loadMore.hidden = !state.nextCursor;
        elements.loadMore.disabled = false;
        updateLoadedCount();
        renderResults();
    }

    async function search({append = false, selectedId = null} = {}) {
        if (state.searching || state.loadingMore) return;
        if (!append) resolveDatePeriod();
        if (!append && !dateRange.validate()) return;
        const sequence = nextSearchSequence(state.searchSequence, append);
        state.searchSequence = sequence;
        const filters = resolveSearchFilters(
            currentFilters(),
            state.appliedFilters,
            append
        );
        if (!append) state.appliedFilters = filters;
        append ? state.loadingMore = true : state.searching = true;
        updateSearchControls();
        if (!append) {
            showResultsMessage("Searching…");
            state.items = [];
            state.selectedIndex = -1;
            state.nextCursor = null;
            state.totalCount = null;
            elements.loadMore.hidden = true;
            elements.resultCount.textContent = "…";
            elements.resultRange.textContent = "Calculating…";
            updateLoadedCount();
            clearSelection();
        }
        elements.loadMore.disabled = true;
        const params = buildSearchParams(filters, append ? state.nextCursor : null);
        try {
            let page;
            if (append) {
                page = await fetchJson(`/admin/api/screenshots?${params}`);
                if (sequence !== state.searchSequence) return;
                applyPage(page, true);
            } else {
                const summaryParams = buildSearchParams(filters);
                page = await loadPageThenSummary(
                    () => fetchJson(`/admin/api/screenshots?${params}`),
                    () => refreshSummary(summaryParams, sequence),
                    loadedPage => {
                        if (sequence === state.searchSequence) applyPage(loadedPage, false);
                    }
                );
            }
            if (sequence !== state.searchSequence) return;

            let targetIndex = -1;
            if (selectedId) targetIndex = state.items.findIndex(item => item.imageId === selectedId);
            if (targetIndex < 0 && !append && state.items.length > 0) targetIndex = 0;
            if (targetIndex >= 0) await selectResult(targetIndex);
            else if (!append && state.items.length === 0) clearSelection();
            writeSearchUrl(state.items[state.selectedIndex]?.imageId || null);
        } catch (error) {
            showResultsMessage(error.message || "Could not load screenshots.");
            if (!append) state.appliedFilters = null;
        } finally {
            state.searching = false;
            state.loadingMore = false;
            elements.loadMore.disabled = false;
            updateNavigation();
            updateSearchControls();
        }
    }

    function setText(element, value) {
        if (element.hasAttribute('data-recognized-cards')) {
            renderRecognizedCards(element, value);
            return;
        }
        element.textContent = value === null || value === undefined || value === "" ? "—" : value;
    }

    function renderDetails(details) {
        const sessionUrl = sessionExplorerUrl(details);
        elements.openSession.hidden = !sessionUrl;
        if (sessionUrl) elements.openSession.href = sessionUrl;
        else elements.openSession.removeAttribute('href');
        renderAiResult(elements.aiDetails, details.ai);
        elements.detailPlaceholder.hidden = true;
        elements.detailContent.hidden = false;
        elements.detailReviewState.textContent = reviewLabel(details.reviewState);
        elements.detailReviewState.dataset.state = details.reviewState.toLowerCase();
        setText(detailElements.gameCode, details.gameCode);
        setText(detailElements.decision, details.decision === "ACCEPTED"
            ? "Matches" : details.decision === "REJECTED" ? "Does not match" : null);
        setText(detailElements.dealerCards, details.dealerCards);
        setText(detailElements.activeUserCards, details.activeUserCards);
        setText(detailElements.inactiveUserCards, details.inactiveUserCards);
        setText(detailElements.buttonsRaw, details.buttonsRaw);
        setText(detailElements.actions, [
            details.stand && "stand",
            details.hit && "hit",
            details.doubleAction && "double",
            details.split && "split",
            details.surrender && "surrender"
        ].filter(Boolean).join(" · "));
        setText(detailElements.imageId, details.imageId);
        setText(detailElements.fileName, details.fileName);
        setText(detailElements.tokenId, details.tokenId);
        setText(detailElements.sessionId, details.sessionId);
        setText(detailElements.fileCreatedAt, formatUtcDate(details.fileCreatedAt));
        setText(detailElements.processedAt, formatUtcDate(details.processedAt));
        setText(detailElements.reviewedBy, details.reviewedBy);
        setText(detailElements.reviewedAt, formatUtcDate(details.reviewedAt));
        setText(detailElements.recognitionDurationMs,
            details.recognitionDurationMs == null ? null : `${details.recognitionDurationMs} ms`);
        setText(detailElements.notification, details.notification ? "Yes" : "No");
        setText(detailElements.parseStatus, details.parseStatus);
        setText(detailElements.storageState, storageLabel(details.storageState));
        setText(detailElements.cloudUploadedAt, formatUtcDate(details.cloudUploadedAt));
        setText(detailElements.payloadRaw, details.payloadRaw);
    }

    function clearSelection() {
        elements.openSession.hidden = true;
        elements.openSession.removeAttribute('href');
        state.selectionSequence++;
        state.selectedIndex = -1;
        state.selectedDetails = null;
        elements.image.hidden = true;
        elements.image.removeAttribute("src");
        elements.viewerMessage.hidden = false;
        elements.viewerMessage.textContent = "No screenshot selected.";
        elements.fileSummary.textContent = "Choose a result";
        elements.detailPlaceholder.hidden = false;
        elements.detailContent.hidden = true;
        elements.detailReviewState.textContent = "—";
        elements.download.setAttribute("aria-disabled", "true");
        elements.download.href = "#";
        elements.temporaryLink.disabled = true;
        elements.copyLink.disabled = true;
        elements.copyReport.disabled = true;
        updateNavigation();
    }

    async function selectResult(index) {
        if (index < 0 || index >= state.items.length) return;
        elements.openSession.hidden = true;
        elements.openSession.removeAttribute('href');
        state.selectionSequence++;
        state.selectedIndex = index;
        state.selectedDetails = null;
        const item = state.items[index];
        renderResults();
        updateNavigation();
        stopDragging();
        elements.fileSummary.textContent = item.fileName;
        elements.viewerMessage.hidden = false;
        elements.viewerMessage.textContent = "Loading screenshot…";
        elements.image.hidden = true;
        elements.image.removeAttribute("src");
        elements.detailContent.hidden = true;
        elements.detailPlaceholder.hidden = false;
        elements.detailReviewState.textContent = "—";
        elements.download.setAttribute("aria-disabled", "true");
        elements.temporaryLink.disabled = true;
        elements.copyLink.disabled = false;
        elements.copyReport.disabled = true;
        writeSearchUrl(item.imageId);

        try {
            const details = await fetchJson(`/admin/api/screenshots/${encodeURIComponent(item.imageId)}`);
            if (state.items[state.selectedIndex]?.imageId !== item.imageId) return;
            state.selectedDetails = details;
            renderDetails(details);
            elements.download.href = details.downloadUrl;
            elements.download.removeAttribute("aria-disabled");
            elements.temporaryLink.disabled = !storageSupportsTemporaryLink(details.storageState);
            elements.copyReport.disabled = false;
            elements.image.src = `${details.imageUrl}?view=${Date.now()}`;
        } catch (error) {
            if (state.items[state.selectedIndex]?.imageId !== item.imageId) return;
            elements.viewerMessage.textContent = error.message || "Could not load screenshot details.";
        }
    }

    async function nextResult() {
        if (state.selectedIndex < state.items.length - 1) {
            await selectResult(state.selectedIndex + 1);
            return;
        }
        if (!state.nextCursor) return;
        const previousLength = state.items.length;
        const selectionSequence = state.selectionSequence;
        await search({append: true});
        if (state.selectionSequence === selectionSequence && state.items.length > previousLength) {
            await selectResult(previousLength);
        }
    }

    function previousResult() {
        if (state.selectedIndex > 0) selectResult(state.selectedIndex - 1);
    }

    function toggleFullscreen() {
        if (document.fullscreenElement) document.exitFullscreen();
        else elements.stage.closest(".explorer-viewer").requestFullscreen?.();
    }

    function clampScale(scale) {
        return Math.max(0.25, Math.min(6, scale));
    }

    function renderTransform() {
        elements.image.style.transform =
            `translate(${state.x}px, ${state.y}px) scale(${state.scale})`;
        elements.zoomValue.textContent = `${Math.round(state.scale * 100)}%`;
    }

    function setScale(nextScale) {
        state.scale = clampScale(nextScale);
        renderTransform();
    }

    function resetZoom() {
        state.scale = 1;
        state.x = 0;
        state.y = 0;
        renderTransform();
    }

    async function openTemporaryLink() {
        const details = state.selectedDetails;
        if (!details || !storageSupportsTemporaryLink(details.storageState)) return;
        const popup = window.open("about:blank", "_blank");
        if (!popup) {
            elements.viewerMessage.hidden = false;
            elements.viewerMessage.textContent = "Allow pop-ups to open the temporary B2 link.";
            return;
        }
        popup.opener = null;
        try {
            const link = await fetchJson(details.temporaryLinkUrl);
            popup.location.replace(link.url);
        } catch (error) {
            popup.close();
            elements.viewerMessage.hidden = false;
            elements.viewerMessage.textContent = error.message;
        }
    }

    async function copyReport() {
        if (!state.selectedDetails) return;
        try {
            await navigator.clipboard.writeText(createTechnicalReport(state.selectedDetails));
            const original = elements.copyReport.textContent;
            elements.copyReport.textContent = "Copied";
            setTimeout(() => elements.copyReport.textContent = original, 1200);
        } catch {
            elements.copyReport.textContent = "Copy failed";
        }
    }

    async function copyCurrentLink() {
        if (state.selectedIndex < 0) return;
        try {
            await copyShareLink(navigator.clipboard, window.location.href);
            const original = elements.copyLink.textContent;
            elements.copyLink.textContent = "Copied";
            setTimeout(() => elements.copyLink.textContent = original, 1200);
        } catch {
            elements.copyLink.textContent = "Copy failed";
        }
    }

    async function refreshStorage() {
        elements.refreshStorage.disabled = true;
        elements.storageMessage.textContent = "Refreshing…";
        try {
            const status = await fetchJson("/admin/api/storage/status");
            const number = value => Number(value).toLocaleString("en-US");
            elements.storageUploaded.textContent = number(status.uploaded);
            elements.storageBacklog.textContent = number(status.backlog);
            elements.storageDueNow.textContent = number(status.dueNow);
            elements.storageRetrying.textContent = number(status.retrying);
            elements.storageUnavailable.textContent = number(status.unavailable);
            elements.storageOldestPending.textContent = formatUtcDate(status.oldestPendingAt);
            elements.storageMessage.textContent = status.enabled
                ? "B2 upload is enabled" : "B2 upload is disabled";
        } catch (error) {
            elements.storageMessage.textContent = error.message || "Storage status unavailable";
        } finally {
            elements.refreshStorage.disabled = false;
        }
    }

    elements.image.addEventListener("load", () => {
        if (!state.selectedDetails) return;
        elements.image.hidden = false;
        elements.viewerMessage.hidden = true;
    });
    elements.image.addEventListener("error", async () => {
        const details = state.selectedDetails;
        if (!details) return;
        elements.image.hidden = true;
        elements.viewerMessage.hidden = false;
        elements.viewerMessage.textContent = "Checking image availability…";
        try {
            const response = await fetch(details.availabilityUrl, {
                credentials: "same-origin"
            });
            if (state.selectedDetails !== details) return;
            elements.viewerMessage.textContent = response.status === 404
                ? "The image is no longer available in local storage or B2."
                : "The image could not be displayed. Try again.";
        } catch {
            if (state.selectedDetails !== details) return;
            elements.viewerMessage.textContent = "Image storage is temporarily unavailable.";
        }
    });

    elements.form.addEventListener("submit", event => {
        event.preventDefault();
        updateCheckedOnlyControls();
        search();
    });
    byId('download-screenshot-csv').addEventListener('click', () => {
        updateCheckedOnlyControls();
        if (!dateRange.validate() || !elements.form.reportValidity()) return;
        window.open(csvExportUrl(currentFilters()), '_blank', 'noopener');
    });
    byId("review-state").addEventListener("change", updateCheckedOnlyControls);
    elements.dateField.addEventListener("change", () => {
        if (elements.dateField.value === "reviewed" && elements.reviewState.value === "UNCHECKED") {
            elements.reviewState.value = "CHECKED";
            updateCheckedOnlyControls();
        }
        resolveDatePeriod();
        updateSearchControls();
    });
    function resolveDatePeriod() {
        const preset = elements.datePreset.value;
        if (!preset) return;
        const range = presetRange(new Date(), preset === "last24h" ? {hours: 24} : {day: preset});
        resolvedDateRange = range;
        writeBoundary(elements.createdFrom, range.from);
        writeBoundary(elements.createdTo, range.to);
    }
    elements.datePreset.addEventListener("change", () => { resolveDatePeriod(); updateSearchControls(); });
    for (const type of ["input", "change"]) elements.form.addEventListener(type, event => {
        if (event.target.closest('[data-utc-boundary]')) elements.datePreset.value = "";
    });
    elements.form.addEventListener("input", updateSearchControls);
    elements.aiErrorMissing.addEventListener('change', () => {
        elements.aiErrorCode.disabled = elements.aiErrorMissing.value === 'true';
        if (elements.aiErrorCode.disabled) elements.aiErrorCode.value = '';
        updateSearchControls();
    });
    elements.form.addEventListener("change", updateSearchControls);
    elements.resetFilters.addEventListener("click", () => {
        elements.form.reset();
        elements.aiErrorCode.disabled = false;
        dateRange.clear();
        elements.reviewState.value = "ALL";
        updateCheckedOnlyControls();
        search();
    });
    document.querySelectorAll("[data-range-hours], [data-range-days], [data-range-day]").forEach(button => {
        button.addEventListener("click", () => {
            elements.datePreset.value = button.dataset.rangeDay || (button.dataset.rangeHours === "24" ? "last24h" : "");
            const range = presetRange(new Date(), {
                hours: Number(button.dataset.rangeHours || 0),
                days: Number(button.dataset.rangeDays || 0),
                day: button.dataset.rangeDay
            });
            writeBoundary(elements.createdFrom, range.from);
            writeBoundary(elements.createdTo, range.to);
            search();
        });
    });
    elements.loadMore.addEventListener("click", () => search({append: true}));
    elements.previous.addEventListener("click", previousResult);
    elements.next.addEventListener("click", nextResult);
    elements.zoomOut.addEventListener("click", () => setScale(state.scale - 0.1));
    elements.zoomIn.addEventListener("click", () => setScale(state.scale + 0.1));
    elements.zoomReset.addEventListener("click", resetZoom);
    elements.fullscreen.addEventListener("click", () => toggleFullscreen());
    document.addEventListener('fullscreenchange', () => {
        elements.fullscreen.textContent = document.fullscreenElement ? 'Exit fullscreen' : 'Fullscreen';
    });
    elements.temporaryLink.addEventListener("click", openTemporaryLink);
    elements.copyLink.addEventListener("click", copyCurrentLink);
    elements.copyReport.addEventListener("click", copyReport);
    elements.refreshStorage.addEventListener("click", refreshStorage);
    elements.download.addEventListener("click", event => {
        if (elements.download.getAttribute("aria-disabled") === "true") event.preventDefault();
    });

    elements.stage.addEventListener("wheel", event => {
        event.preventDefault();
        setScale(state.scale + (event.deltaY < 0 ? 0.1 : -0.1));
    }, {passive: false});
    elements.stage.addEventListener("pointerdown", event => {
        if (elements.image.hidden) return;
        state.dragging = true;
        state.pointerStartX = event.clientX;
        state.pointerStartY = event.clientY;
        state.originX = state.x;
        state.originY = state.y;
        elements.stage.classList.add("dragging");
        elements.stage.setPointerCapture?.(event.pointerId);
    });
    elements.stage.addEventListener("pointermove", event => {
        if (!state.dragging) return;
        state.x = state.originX + event.clientX - state.pointerStartX;
        state.y = state.originY + event.clientY - state.pointerStartY;
        renderTransform();
    });
    const stopDragging = () => {
        state.dragging = false;
        elements.stage.classList.remove("dragging");
    };
    elements.stage.addEventListener("pointerup", stopDragging);
    elements.stage.addEventListener("pointercancel", stopDragging);

    document.addEventListener("keydown", event => {
        if (document.querySelector('dialog[open]')) return;
        if (event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey
                && event.target === elements.stage && event.key.startsWith('Arrow')) {
            event.preventDefault();
            if (event.key === 'ArrowLeft') state.x -= 40;
            if (event.key === 'ArrowRight') state.x += 40;
            if (event.key === 'ArrowUp') state.y -= 40;
            if (event.key === 'ArrowDown') state.y += 40;
            renderTransform();
            return;
        }
        const action = keyboardAction({
            key: event.key,
            ctrlKey: event.ctrlKey, metaKey: event.metaKey, altKey: event.altKey,
            shiftKey: event.shiftKey, repeat: event.repeat, defaultPrevented: event.defaultPrevented,
            tagName: event.target?.tagName,
            isContentEditable: event.target?.isContentEditable
        });
        if (!action) return;
        event.preventDefault();
        if (action === "previous") previousResult();
        if (action === "next") nextResult();
        if (action === "download" && elements.download.getAttribute("aria-disabled") !== "true") {
            elements.download.click();
        }
        if (action === "fullscreen") toggleFullscreen();
        if (action === "resetZoom") resetZoom();
        if (action === "zoomIn") setScale(state.scale + 0.1);
        if (action === "zoomOut") setScale(state.scale - 0.1);
    });

    const savedSelect = byId("saved-filter-select");
    const savedName = byId("saved-filter-name");
    const savedMessage = byId("saved-filter-message");
    const savedKey = "recognition-validator.admin-saved-filters";
    let savedFilters = [];
    try {
        const value = JSON.parse(localStorage.getItem(savedKey) || "[]");
        if (Array.isArray(value)) savedFilters = value.filter(item => typeof item?.name === "string" && typeof item?.query === "string").slice(0, 20);
    } catch { /* Filters remain usable without browser storage. */ }
    function renderSavedFilters() {
        savedSelect.replaceChildren(new Option("Choose saved filters…", ""));
        savedFilters.forEach((item, index) => savedSelect.add(new Option(item.name, String(index))));
    }
    function persistSavedFilters(next) {
        try {
            localStorage.setItem(savedKey, JSON.stringify(next));
            savedFilters = next;
            renderSavedFilters();
            return true;
        } catch {
            savedMessage.textContent = "Browser storage is unavailable. Filters were not saved.";
            return false;
        }
    }
    byId("save-filter").addEventListener("click", () => {
        const name = savedName.value.trim();
        if (!name) { savedMessage.textContent = "Enter a name for these filters."; savedName.focus(); return; }
        if (!dateRange.validate() || !elements.form.reportValidity()) return;
        const next = savedFilters.filter(item => item.name !== name);
        if (next.length >= 20) { savedMessage.textContent = "Delete a saved filter before adding another (maximum 20)."; return; }
        const params = buildSearchParams(currentFilters());
        params.delete("limit");
        const relativeDate = elements.datePreset.value
            ? {preset: elements.datePreset.value, field: elements.dateField.value} : null;
        if (relativeDate) for (const key of ["createdFrom", "createdTo", "reviewedFrom", "reviewedTo"]) params.delete(key);
        next.push({name, query: params.toString(), ...(relativeDate ? {relativeDate} : {})});
        if (persistSavedFilters(next)) { savedSelect.value = String(next.length - 1); savedMessage.textContent = relativeDate
            ? "Saved in this browser. The period updates when applied or searched again."
            : "Saved in this browser. Dates are saved exactly as selected."; }
    });
    savedSelect.addEventListener("change", () => {
        if (savedSelect.value === "") return;
        if (state.searching || state.loadingMore) { savedMessage.textContent = "Wait for the current search to finish, then select the filters."; savedSelect.value = ""; return; }
        const saved = savedFilters[Number(savedSelect.value)];
        savedName.value = saved.name;
        try {
            restoreFromUrl(resolveSavedFilter(saved));
            elements.datePreset.value = saved.relativeDate?.preset || "";
        } catch (error) { savedMessage.textContent = error.message; return; }
        savedMessage.textContent = `Applied: ${saved.name}`;
        search();
    });
    byId("delete-filter").addEventListener("click", () => {
        if (savedSelect.value === "") return;
        if (persistSavedFilters(savedFilters.filter((_, index) => index !== Number(savedSelect.value)))) savedMessage.textContent = "Saved filters deleted.";
    });
    renderSavedFilters();
    byId("results-view").addEventListener("change", event => {
        elements.results.classList.toggle("is-grid", event.target.value === "grid");
        document.querySelector(".screenshot-explorer").classList.toggle("grid-view", event.target.value === "grid");
        renderResults();
    });

    const selectedId = restoreFromUrl();
    void loadIssuingRules();
    clearSelection();
    refreshStorage();
    search({selectedId});
})();
}
