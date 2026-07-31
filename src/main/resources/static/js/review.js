(() => {
    "use strict";

    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    const elements = {
        filterForm: document.getElementById("filter-form"),
        clearFilters: document.getElementById("clear-filters"),
        createdFrom: document.getElementById("created-from"),
        createdTo: document.getElementById("created-to"),
        sessionId: document.getElementById("session-id"),
        gameCode: document.getElementById("game-code"),
        notification: document.getElementById("notification"),
        fileName: document.getElementById("file-name"),
        stage: document.getElementById("image-stage"),
        image: document.getElementById("review-image"),
        viewerMessage: document.getElementById("viewer-message"),
        decisionMessage: document.getElementById("decision-message"),
        accept: document.getElementById("accept-button"),
        reject: document.getElementById("reject-button"),
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
        originY: 0
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

    function toIso(value) {
        return value ? new Date(value).toISOString() : null;
    }

    function emptyToNull(value) {
        const normalized = value.trim();
        return normalized === "" ? null : normalized;
    }

    async function claim() {
        setBusy(true, "Loading assignment…");
        try {
            const response = await fetch("/api/review-tasks/claim", {
                method: "POST",
                headers: requestHeaders(),
                body: JSON.stringify(filters())
            });
            if (response.status === 204) {
                showEmpty("No screenshots are available for these filters.");
                return;
            }
            if (!response.ok) {
                throw new Error(await errorMessage(response));
            }
            renderItem(await response.json());
        } catch (error) {
            elements.decisionMessage.textContent = error.message;
            if (!state.item) {
                showEmpty("Could not load an assignment. Refresh the page or change the filters.");
            }
        } finally {
            setBusy(false);
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
                    body: JSON.stringify({decision})
                }
            );
            if (!response.ok) {
                throw new Error(await errorMessage(response));
            }
            state.item = null;
            await claim();
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

    function renderItem(item) {
        state.item = item;
        resetView();
        elements.fileName.textContent = item.fileName;
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
        setText(elements.buttons, item.buttonsRaw);
        setText(elements.flags, actionFlags(item));
        setText(elements.parseStatus, item.parseStatus);
        elements.viewerMessage.hidden = true;
        elements.image.hidden = false;
        elements.image.src = item.imageUrl;
        elements.decisionMessage.textContent = "Compare the cards in the screenshot with the recognized values";
        updateActions();
    }

    function showEmpty(message) {
        state.item = null;
        elements.image.hidden = true;
        elements.image.removeAttribute("src");
        elements.viewerMessage.hidden = false;
        elements.viewerMessage.textContent = message;
        elements.fileName.textContent = "Queue is empty";
        clearMetadata();
        elements.decisionMessage.textContent = "Change the filters or wait for new files";
        updateActions();
    }

    function clearMetadata() {
        [
            elements.game,
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
    }

    function setText(element, value) {
        element.textContent = value == null || value === "" ? "—" : value;
    }

    function actionFlags(item) {
        const values = [];
        if (item.stand) values.push("stand");
        if (item.hit) values.push("hit");
        if (item.doubleAction) values.push("double");
        if (item.split) values.push("split");
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

    elements.filterForm.addEventListener("submit", event => {
        event.preventDefault();
        claim();
    });

    elements.clearFilters.addEventListener("click", () => {
        elements.filterForm.reset();
        claim();
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
        claim();
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

    claim();
})();
