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
    }
    appendIfPresent(params, "storageState", filters.storageState);
    appendIfPresent(params, "parseStatus", filters.parseStatus);
    appendIfPresent(params, "notification", filters.notification);
    appendIfPresent(params, "hasUserHand", filters.hasUserHand);
    appendIfPresent(params, "aiResult", filters.aiResult);
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

function canonicalUtcMinute(date) {
    return date.toISOString().slice(0, 16);
}

function presetRange(now, {hours = 0, days = 0}) {
    const durationMs = (hours + days * 24) * 60 * 60 * 1000;
    const to = new Date(Math.ceil(now.getTime() / 60000) * 60000);
    const from = new Date(to.getTime() - durationMs);
    return {from: canonicalUtcMinute(from), to: canonicalUtcMinute(to)};
}

function keyboardAction({key, tagName = "", isContentEditable = false}) {
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
        `Storage: ${details.storageState || "—"}`
    ].join("\n");
}

function formatLoadedCount(loaded, total) {
    const loadedLabel = Number(loaded).toLocaleString("en-US");
    if (total === null || total === undefined) return `${loadedLabel} loaded`;
    return `Loaded ${loadedLabel} of ${Number(total).toLocaleString("en-US")}`;
}

async function copyShareLink(clipboard, url) {
    await clipboard.writeText(url);
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
        copyShareLink,
        createTechnicalReport,
        formatUtcDate,
        formatLoadedCount,
        filterStatus,
        keyboardAction,
        loadPageThenSummary,
        nextSearchSequence,
        presetRange,
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
        reviewState: byId("review-state"),
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

    const dateRange = window.UtcDateTimePicker.createRange({
        fromInput: elements.createdFrom,
        toInput: elements.createdTo,
        errorElement: elements.dateError
    });

    function currentFilters() {
        return {
            createdFrom: elements.createdFrom.value,
            createdTo: elements.createdTo.value,
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
        }
    }

    function updateSearchControls() {
        const busy = state.searching || state.loadingMore;
        elements.searchButton.disabled = busy;
        elements.resetFilters.disabled = busy;
        elements.form.setAttribute("aria-busy", String(busy));
        document.querySelectorAll("[data-range-hours], [data-range-days]").forEach(button => {
            button.disabled = busy;
        });
        elements.filterStatus.textContent = filterStatus(currentFilters(), state.appliedFilters, busy);
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

    function restoreFromUrl() {
        const params = new URLSearchParams(window.location.search);
        const canonical = value => value ? value.slice(0, 16) : value;
        writeBoundary(elements.createdFrom, canonical(params.get("createdFrom")) || "");
        writeBoundary(elements.createdTo, canonical(params.get("createdTo")) || "");
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
        setInputFromQuery(elements.imageId, params, "imageId");
        setInputFromQuery(elements.fileName, params, "fileName");
        updateCheckedOnlyControls();
        return params.get("selected");
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
        return value === "CHECKED" ? "Checked" : "Unchecked";
    }

    function renderResults() {
        elements.results.replaceChildren();
        if (state.items.length === 0) {
            showResultsMessage("No screenshots match these filters.");
            return;
        }
        state.items.forEach((item, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "screenshot-result";
            button.setAttribute("role", "option");
            button.setAttribute("aria-selected", String(index === state.selectedIndex));
            button.dataset.index = String(index);

            const top = document.createElement("span");
            top.className = "screenshot-result-top";
            const time = document.createElement("time");
            time.textContent = formatUtcDate(item.fileCreatedAt);
            const review = document.createElement("span");
            review.className = `result-badge is-${item.reviewState.toLowerCase()}`;
            review.textContent = reviewLabel(item.reviewState);
            top.append(time, review);

            const file = document.createElement("strong");
            file.textContent = item.fileName;
            const bottom = document.createElement("span");
            bottom.className = "screenshot-result-bottom";
            const game = document.createElement("span");
            game.textContent = item.gameCode;
            const storage = document.createElement("span");
            storage.textContent = storageLabel(item.storageState);
            bottom.append(game, storage);
            button.append(top, file, bottom);
            button.addEventListener("click", () => selectResult(index));
            elements.results.append(button);
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
        element.textContent = value === null || value === undefined || value === "" ? "—" : value;
    }

    function renderDetails(details) {
        elements.aiDetails.textContent = aiResultText(details.ai);
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
    elements.reviewState.addEventListener("change", updateCheckedOnlyControls);
    elements.form.addEventListener("input", updateSearchControls);
    elements.form.addEventListener("change", updateSearchControls);
    elements.resetFilters.addEventListener("click", () => {
        elements.form.reset();
        dateRange.clear();
        elements.reviewState.value = "ALL";
        updateCheckedOnlyControls();
        search();
    });
    document.querySelectorAll("[data-range-hours], [data-range-days]").forEach(button => {
        button.addEventListener("click", () => {
            const range = presetRange(new Date(), {
                hours: Number(button.dataset.rangeHours || 0),
                days: Number(button.dataset.rangeDays || 0)
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
    elements.fullscreen.addEventListener("click", () => elements.stage.requestFullscreen?.());
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
        const action = keyboardAction({
            key: event.key,
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
        if (action === "fullscreen") elements.stage.requestFullscreen?.();
        if (action === "resetZoom") resetZoom();
        if (action === "zoomIn") setScale(state.scale + 0.1);
        if (action === "zoomOut") setScale(state.scale - 0.1);
    });

    const selectedId = restoreFromUrl();
    clearSelection();
    refreshStorage();
    search({selectedId});
})();
}
