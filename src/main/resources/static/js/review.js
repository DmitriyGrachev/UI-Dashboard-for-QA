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
        sessionId: document.getElementById("session-id"),
        gameCode: document.getElementById("game-code"),
        notification: document.getElementById("notification"),
        remainingCount: document.getElementById("remaining-count"),
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

    const state = {
        item: null,
        busy: false,
        scale: 1,
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
            createdFrom: toIso(elements.createdFrom.value),
            createdTo: toIso(elements.createdTo.value),
            sessionId: emptyToNull(elements.sessionId.value),
            gameCode: emptyToNull(elements.gameCode.value),
            notification: elements.notification.value === ""
                ? null
                : elements.notification.value === "true"
        };
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

    function toIso(value) {
        return value ? new Date(value).toISOString() : null;
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
                updateRemaining(payload.remaining);
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
                updateRemaining(payload.remaining);
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
        resetView();
        elements.fileName.textContent = item.fileName;
        setText(elements.gameSummary, item.gameCode);
        setText(elements.game, item.gameCode);
        setText(elements.session, item.sessionId);
        setText(elements.created, formatDate(item.fileCreatedAt));
        setText(elements.processed, formatDate(item.processedAt));
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

    function formatDate(value) {
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

    function clampScale(value) {
        return Math.min(6, Math.max(0.25, value));
    }

    function zoom(delta) {
        state.scale = clampScale(state.scale + delta);
        applyTransform();
    }

    function resetView() {
        state.scale = 1;
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
        updateActiveFilterCount();
        claim({
            replaceCurrent: true,
            includeRemaining: remainingCountEnabled
        });
    }

    function scheduleFilterApplication() {
        clearTimeout(state.filterTimer);
        updateActiveFilterCount();
        state.filterTimer = setTimeout(applyFilters, 400);
    }

    elements.filterForm.addEventListener("submit", event => {
        event.preventDefault();
        applyFilters();
    });

    [elements.createdFrom, elements.createdTo, elements.gameCode, elements.notification]
        .forEach(element => element.addEventListener("change", applyFilters));

    elements.sessionId.addEventListener("input", scheduleFilterApplication);
    elements.sessionId.addEventListener("keydown", event => {
        if (event.key === "Enter") {
            event.preventDefault();
            applyFilters();
        }
    });

    elements.clearFilters.addEventListener("click", () => {
        elements.filterForm.reset();
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
