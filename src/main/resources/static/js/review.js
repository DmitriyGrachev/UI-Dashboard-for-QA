function toUtcIso(value) {
    if (!value) return null;
    const withSeconds = value.length === 16 ? `${value}:00` : value;
    return `${withSeconds}Z`;
}

function toOptionalBoolean(value) {
    if (value !== "true" && value !== "false") return null;
    return value === "true";
}

function toOptionalLong(value) {
    if (value === null || value.trim() === "") return null;
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}

function formatUtcDate(value) {
    if (!value) return null;
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

function writeStoredFilters(storage, key, filters) {
    try {
        storage.setItem(key, JSON.stringify(filters));
    } catch {
        // Review remains usable when browser storage is unavailable.
    }
}

function readStoredFilters(storage, key) {
    try {
        const parsed = JSON.parse(storage.getItem(key));
        return parsed && typeof parsed === "object" ? parsed : null;
    } catch {
        return null;
    }
}

function clampScale(value) {
    return Math.min(6, Math.max(0.25, value));
}

function writeStoredScale(storage, key, scale) {
    try {
        storage.setItem(key, String(clampScale(scale)));
    } catch {
        // Review remains usable when browser storage is unavailable.
    }
}

function readStoredScale(storage, key) {
    try {
        const stored = storage.getItem(key);
        if (stored === null || stored === "") return 1;
        const scale = Number(stored);
        return Number.isFinite(scale) ? clampScale(scale) : 1;
    } catch {
        return 1;
    }
}

function prepareViewForNextItem(viewState) {
    viewState.dragging = false;
}

function createReviewDateRange(pickerApi, elements, applyFilters) {
    return pickerApi.createRange({
        fromInput: elements.createdFrom,
        toInput: elements.createdTo,
        errorElement: elements.dateRangeError,
        onCommit: applyFilters
    });
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        createReviewDateRange,
        formatUtcDate,
        prepareViewForNextItem,
        readStoredFilters,
        readStoredScale,
        toUtcIso,
        toOptionalLong,
        toOptionalBoolean,
        writeStoredFilters,
        writeStoredScale
    };
}

if (typeof document !== "undefined") {
(() => {
    "use strict";

    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    const elements = {
        workspace: document.querySelector(".review-workspace"),
        filterForm: document.getElementById("filter-form"),
        filterToggle: document.getElementById("filter-toggle"),
        filterToggleLabel: document.querySelector("[data-filter-toggle-label]"),
        activeFilterCount: document.getElementById("active-filter-count"),
        clearFilters: document.getElementById("clear-filters"),
        createdFrom: document.getElementById("created-from"),
        createdTo: document.getElementById("created-to"),
        dateRangeError: document.getElementById("review-date-range-error"),
        tokenId: document.getElementById("token-id"),
        sessionId: document.getElementById("session-id"),
        gameCode: document.getElementById("game-code"),
        notification: document.getElementById("notification"),
        hasUserHand: document.getElementById("has-user-hand"),
        remainingCount: document.getElementById("remaining-count"),
        queueOldestDate: document.getElementById("queue-oldest-date"),
        queueNewestDate: document.getElementById("queue-newest-date"),
        gameSummary: document.getElementById("game-summary"),
        fileName: document.getElementById("file-name"),
        stage: document.getElementById("image-stage"),
        image: document.getElementById("review-image"),
        viewerMessage: document.getElementById("viewer-message"),
        decisionMessage: document.getElementById("decision-message"),
        accept: document.getElementById("accept-button"),
        reject: document.getElementById("reject-button"),
        faqOpen: document.getElementById("faq-open"),
        faqDialog: document.getElementById("faq-dialog"),
        faqClose: document.getElementById("faq-close"),
        zoomIn: document.getElementById("zoom-in"),
        zoomOut: document.getElementById("zoom-out"),
        zoomReset: document.getElementById("zoom-reset"),
        zoomValue: document.getElementById("zoom-value"),
        fullscreen: document.getElementById("fullscreen"),
        game: document.getElementById("game-value"),
        session: document.getElementById("session-value"),
        created: document.getElementById("created-value"),
        processed: document.getElementById("processed-value"),
        duration: document.getElementById("duration-value"),
        dealer: document.getElementById("dealer-cards"),
        activeCards: document.getElementById("active-cards"),
        inactiveCards: document.getElementById("inactive-cards"),
        notificationValue: document.getElementById("notification-value"),
        buttons: document.getElementById("buttons-value"),
        flags: document.getElementById("flags-value"),
        parseStatus: document.getElementById("parse-value")
    };
    const remainingCountEnabled = elements.remainingCount !== null;
    const filtersCollapsedKey = "recognition-validator.filters-collapsed";
    const filtersStorageKey = "recognition-validator.review-filters";
    const scaleStorageKey = "recognition-validator.review-scale";

    const state = {
        item: null,
        busy: false,
        scale: readStoredScale(window.sessionStorage, scaleStorageKey),
        x: 0,
        y: 0,
        dragging: false,
        pointerStartX: 0,
        pointerStartY: 0,
        originX: 0,
        originY: 0,
        remaining: null,
        claimController: null,
        filterTimer: null
    };

    function requestHeaders() {
        return {
            "Content-Type": "application/json",
            [csrfHeader]: csrfToken
        };
    }

    function filters() {
        return {
            createdFrom: toUtcIso(elements.createdFrom.value),
            createdTo: toUtcIso(elements.createdTo.value),
            tokenId: toOptionalLong(elements.tokenId.value),
            sessionId: emptyToNull(elements.sessionId.value),
            gameCode: emptyToNull(elements.gameCode.value),
            notification: toOptionalBoolean(elements.notification.value),
            hasUserHand: toOptionalBoolean(elements.hasUserHand.value)
        };
    }

    function filterInputValues() {
        return {
            createdFrom: elements.createdFrom.value,
            createdTo: elements.createdTo.value,
            tokenId: elements.tokenId.value,
            sessionId: elements.sessionId.value,
            gameCode: elements.gameCode.value,
            notification: elements.notification.value,
            hasUserHand: elements.hasUserHand.value
        };
    }

    function persistFilters() {
        writeStoredFilters(window.sessionStorage, filtersStorageKey, filterInputValues());
    }

    function restoreFilters() {
        const stored = readStoredFilters(window.sessionStorage, filtersStorageKey);
        if (!stored) return;
        elements.createdFrom.value = stored.createdFrom || "";
        elements.createdTo.value = stored.createdTo || "";
        elements.tokenId.value = stored.tokenId || "";
        elements.sessionId.value = stored.sessionId || "";
        elements.gameCode.value = stored.gameCode || "";
        elements.notification.value = stored.notification || "";
        elements.hasUserHand.value = stored.hasUserHand || "";
    }

    function activeFilterTotal() {
        return Object.values(filters()).filter(value => value !== null).length;
    }

    function updateActiveFilterCount() {
        const count = activeFilterTotal();
        elements.activeFilterCount.hidden = count === 0;
        elements.activeFilterCount.textContent = String(count);
        const suffix = count === 0 ? "" : `, ${count} active`;
        elements.filterToggle.setAttribute(
            "aria-label",
            `${isFiltersCollapsed() ? "Show" : "Hide"} filters${suffix}`
        );
    }

    function isFiltersCollapsed() {
        return elements.workspace.dataset.filtersCollapsed === "true";
    }

    function storedFiltersCollapsed() {
        try {
            const stored = window.localStorage.getItem(filtersCollapsedKey);
            return stored === null ? true : stored === "true";
        } catch {
            return true;
        }
    }

    function setFiltersCollapsed(collapsed, persist = true) {
        elements.workspace.dataset.filtersCollapsed = String(collapsed);
        elements.filterToggle.setAttribute("aria-expanded", String(!collapsed));
        elements.filterToggle.title = collapsed ? "Show filters" : "Hide filters";
        elements.filterToggleLabel.textContent = collapsed ? "Show" : "Hide";
        if (persist) {
            try {
                window.localStorage.setItem(filtersCollapsedKey, String(collapsed));
            } catch {
                // The layout still works when browser storage is unavailable.
            }
        }
        updateActiveFilterCount();
    }

    function emptyToNull(value) {
        const normalized = value.trim();
        return normalized === "" ? null : normalized;
    }

    async function claim({replaceCurrent = false, includeRemaining = false} = {}) {
        if (state.claimController) {
            state.claimController.abort();
        }
        const controller = new AbortController();
        state.claimController = controller;
        setBusy(true, "Loading assignment…");
        try {
            const response = await fetch("/api/review-tasks/claim", {
                method: "POST",
                headers: requestHeaders(),
                body: JSON.stringify({
                    filters: filters(),
                    replaceCurrent,
                    includeRemaining
                }),
                signal: controller.signal
            });
            const payload = await responsePayload(response);
            if (!payload) return;
            if (payload.remaining != null) {
                updateQueueSummary(payload);
            }
            if (payload.item) {
                renderItem(payload.item);
            } else {
                showEmpty("No screenshots are available for these filters.");
            }
        } catch (error) {
            if (error.name === "AbortError") {
                return;
            }
            elements.decisionMessage.textContent = error.message;
            if (!state.item) {
                showEmpty("Could not load an assignment. Refresh the page or change the filters.");
            }
        } finally {
            if (state.claimController === controller) {
                state.claimController = null;
                setBusy(false);
            }
        }
    }

    async function decide(decision) {
        if (!state.item || state.busy) {
            return;
        }
        setBusy(true, "Saving decision…");
        try {
            const response = await fetch(
                `/api/review-tasks/${encodeURIComponent(state.item.imageId)}/decision`,
                {
                    method: "POST",
                    headers: requestHeaders(),
                    body: JSON.stringify({decision, filters: filters()})
                }
            );
            if (response.status === 409) {
                state.item = null;
                await claim({includeRemaining: remainingCountEnabled});
                return;
            }
            const payload = await responsePayload(response);
            if (!payload) return;
            if (payload.remaining != null) {
                updateQueueSummary(payload);
            } else {
                decrementRemaining(Boolean(payload.item));
            }
            if (payload.item) {
                renderItem(payload.item);
            } else {
                showEmpty("No screenshots are available for these filters.");
            }
        } catch (error) {
            elements.decisionMessage.textContent = error.message;
        } finally {
            setBusy(false);
        }
    }

    async function errorMessage(response) {
        try {
            const problem = await response.json();
            return problem.detail || "Operation failed.";
        } catch {
            return "Operation failed.";
        }
    }

    async function responsePayload(response) {
        if (response.status === 401 || isLoginRedirect(response)) {
            window.location.replace("/login?expired");
            return null;
        }
        if (!response.ok) {
            throw new Error(await errorMessage(response));
        }
        const contentType = response.headers.get("content-type") || "";
        if (!contentType.includes("application/json")) {
            throw new Error("The server returned an unexpected response. Refresh the page.");
        }
        return response.json();
    }

    function isLoginRedirect(response) {
        if (!response.redirected || !response.url) return false;
        return new URL(response.url, window.location.origin).pathname === "/login";
    }

    function renderItem(item) {
        state.item = item;
        prepareViewForNextItem(state);
        elements.stage.classList.remove("dragging");
        elements.fileName.textContent = item.fileName;
        setText(elements.gameSummary, item.gameCode);
        setText(elements.game, item.gameCode);
        setText(elements.session, item.sessionId);
        setText(elements.created, formatUtcDate(item.fileCreatedAt));
        setText(elements.processed, formatUtcDate(item.processedAt));
        setText(elements.duration, item.recognitionDurationMs == null
            ? null
            : `${item.recognitionDurationMs} ms`);
        setText(elements.dealer, item.dealerCards);
        setText(elements.activeCards, item.activeUserCards);
        setText(elements.inactiveCards, item.inactiveUserCards);
        setText(elements.notificationValue, item.notification ? "Yes" : "No");
        setText(elements.buttons, item.notification ? null : item.buttonsRaw);
        setText(elements.flags, actionFlags(item));
        setParseStatus(item.parseStatus);
        elements.viewerMessage.hidden = true;
        elements.image.hidden = false;
        elements.image.src = item.imageUrl;
        elements.decisionMessage.textContent = "Compare the screenshot with the recognition result";
        updateActions();
    }

    function showEmpty(message) {
        state.item = null;
        elements.image.hidden = true;
        elements.image.removeAttribute("src");
        elements.viewerMessage.hidden = false;
        elements.viewerMessage.textContent = message;
        clearMetadata();
        elements.gameSummary.textContent = "Queue is empty";
        elements.decisionMessage.textContent = "Change the filters or wait for new files";
        updateActions();
    }

    function updateRemaining(value) {
        if (!remainingCountEnabled) {
            return;
        }
        state.remaining = Number(value);
        elements.remainingCount.value = String(state.remaining);
        elements.remainingCount.textContent = String(state.remaining);
    }

    function updateQueueSummary(payload) {
        updateRemaining(payload.remaining);
        updateQueueDate(elements.queueOldestDate, payload.oldestCreatedAt);
        updateQueueDate(elements.queueNewestDate, payload.newestCreatedAt);
    }

    function updateQueueDate(element, value) {
        if (!element) return;
        element.textContent = formatUtcDate(value) || "—";
        if (value) {
            element.dateTime = value;
        } else {
            element.removeAttribute("datetime");
        }
    }

    function decrementRemaining(hasNextItem) {
        if (!remainingCountEnabled) {
            return;
        }
        if (!hasNextItem) {
            updateRemaining(0);
        } else if (state.remaining != null) {
            updateRemaining(Math.max(1, state.remaining - 1));
        }
    }

    function clearMetadata() {
        [
            elements.game,
            elements.gameSummary,
            elements.fileName,
            elements.session,
            elements.created,
            elements.processed,
            elements.duration,
            elements.dealer,
            elements.activeCards,
            elements.inactiveCards,
            elements.notificationValue,
            elements.buttons,
            elements.flags,
            elements.parseStatus
        ].forEach(element => setText(element, null));
        elements.parseStatus.classList.remove("warning-value");
    }

    function setText(element, value) {
        element.textContent = value == null || value === "" ? "—" : value;
    }

    function setParseStatus(value) {
        setText(elements.parseStatus, value);
        elements.parseStatus.classList.toggle(
            "warning-value",
            value != null && value !== "" && value !== "SUCCESS"
        );
    }

    function actionFlags(item) {
        const values = [];
        if (item.stand) values.push("stand");
        if (item.hit) values.push("hit");
        if (item.doubleAction) values.push("double");
        if (item.split) values.push("split");
        if (item.surrender) values.push("surrender");
        return values.length ? values.join(" · ") : "—";
    }

    function setBusy(busy, message) {
        state.busy = busy;
        if (message) {
            elements.decisionMessage.textContent = message;
        }
        updateActions();
    }

    function updateActions() {
        const disabled = state.busy || !state.item;
        elements.accept.disabled = disabled;
        elements.reject.disabled = disabled;
    }

    function zoom(delta) {
        state.scale = clampScale(state.scale + delta);
        writeStoredScale(window.sessionStorage, scaleStorageKey, state.scale);
        applyTransform();
    }

    function resetView() {
        state.scale = 1;
        writeStoredScale(window.sessionStorage, scaleStorageKey, state.scale);
        resetPosition();
    }

    function resetPosition() {
        state.x = 0;
        state.y = 0;
        applyTransform();
    }

    function applyTransform() {
        elements.image.style.transform =
            `translate(${state.x}px, ${state.y}px) scale(${state.scale})`;
        elements.zoomValue.value = `${Math.round(state.scale * 100)}%`;
        elements.zoomValue.textContent = elements.zoomValue.value;
    }

    function applyFilters() {
        clearTimeout(state.filterTimer);
        persistFilters();
        updateActiveFilterCount();
        claim({
            replaceCurrent: true,
            includeRemaining: remainingCountEnabled
        });
    }

    function scheduleFilterApplication() {
        clearTimeout(state.filterTimer);
        persistFilters();
        updateActiveFilterCount();
        state.filterTimer = setTimeout(applyFilters, 400);
    }

    restoreFilters();
    const dateRange = createReviewDateRange(
        window.UtcDateTimePicker,
        elements,
        applyFilters
    );

    elements.filterForm.addEventListener("submit", event => {
        event.preventDefault();
        applyFilters();
    });

    [
        elements.gameCode,
        elements.notification,
        elements.hasUserHand
    ]
        .forEach(element => element.addEventListener("change", applyFilters));

    [elements.tokenId, elements.sessionId].forEach(element => {
        element.addEventListener("input", scheduleFilterApplication);
        element.addEventListener("keydown", event => {
            if (event.key === "Enter") {
                event.preventDefault();
                applyFilters();
            }
        });
    });

    elements.clearFilters.addEventListener("click", () => {
        elements.filterForm.reset();
        dateRange.clear();
        try {
            window.sessionStorage.removeItem(filtersStorageKey);
        } catch {
            // The reset still applies when browser storage is unavailable.
        }
        applyFilters();
    });

    elements.filterToggle.addEventListener("click", () => {
        setFiltersCollapsed(!isFiltersCollapsed());
    });

    elements.faqOpen.addEventListener("click", () => {
        if (!elements.faqDialog.open) {
            elements.faqDialog.showModal();
        }
    });
    elements.faqClose.addEventListener("click", () => elements.faqDialog.close());
    elements.faqDialog.addEventListener("click", event => {
        if (event.target === elements.faqDialog) {
            elements.faqDialog.close();
        }
    });

    elements.accept.addEventListener("click", () => decide("ACCEPTED"));
    elements.reject.addEventListener("click", () => decide("REJECTED"));
    elements.zoomIn.addEventListener("click", () => zoom(0.25));
    elements.zoomOut.addEventListener("click", () => zoom(-0.25));
    elements.zoomReset.addEventListener("click", resetView);
    elements.fullscreen.addEventListener("click", () => {
        if (document.fullscreenElement) {
            document.exitFullscreen();
        } else {
            elements.stage.requestFullscreen();
        }
    });

    elements.stage.addEventListener("wheel", event => {
        if (!state.item) return;
        event.preventDefault();
        zoom(event.deltaY < 0 ? 0.2 : -0.2);
    }, {passive: false});

    elements.stage.addEventListener("pointerdown", event => {
        if (!state.item) return;
        state.dragging = true;
        state.pointerStartX = event.clientX;
        state.pointerStartY = event.clientY;
        state.originX = state.x;
        state.originY = state.y;
        elements.stage.classList.add("dragging");
        elements.stage.setPointerCapture(event.pointerId);
    });

    elements.stage.addEventListener("pointermove", event => {
        if (!state.dragging) return;
        state.x = state.originX + event.clientX - state.pointerStartX;
        state.y = state.originY + event.clientY - state.pointerStartY;
        applyTransform();
    });

    function finishDrag(event) {
        if (!state.dragging) return;
        state.dragging = false;
        elements.stage.classList.remove("dragging");
        if (elements.stage.hasPointerCapture(event.pointerId)) {
            elements.stage.releasePointerCapture(event.pointerId);
        }
    }

    elements.stage.addEventListener("pointerup", finishDrag);
    elements.stage.addEventListener("pointercancel", finishDrag);

    elements.image.addEventListener("error", () => {
        if (!state.item) return;
        state.item = null;
        showEmpty("The file is no longer available. Loading the next assignment…");
        claim({includeRemaining: remainingCountEnabled});
    });

    document.addEventListener("keydown", event => {
        const tag = event.target.tagName;
        if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA") {
            return;
        }
        if (event.key === "ArrowRight" || event.key.toLowerCase() === "a") {
            event.preventDefault();
            decide("ACCEPTED");
        } else if (event.key === "ArrowLeft" || event.key.toLowerCase() === "r") {
            event.preventDefault();
            decide("REJECTED");
        } else if (event.key === "0") {
            resetView();
        } else if (event.key === "+" || event.key === "=") {
            zoom(0.25);
        } else if (event.key === "-") {
            zoom(-0.25);
        }
    });

    setFiltersCollapsed(storedFiltersCollapsed(), false);
    claim({includeRemaining: remainingCountEnabled});
})();
}
