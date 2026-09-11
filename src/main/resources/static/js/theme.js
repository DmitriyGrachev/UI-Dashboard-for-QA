(function () {
    "use strict";

    const storageKey = "recognition-validator-theme";
    const root = document.documentElement;

    function normalizeTheme(theme) {
        return theme === "light" ? "light" : "dark";
    }

    function readTheme() {
        try {
            const saved = window.localStorage.getItem(storageKey);
            return saved === "light" || saved === "dark" ? saved
                : window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
        } catch (_error) {
            return "dark";
        }
    }

    function saveTheme(theme) {
        try {
            window.localStorage.setItem(storageKey, theme);
        } catch (_error) {
            // The selected theme still applies to the current page.
        }
    }

    function syncToggle(toggle, theme) {
        const targetTheme = theme === "dark" ? "light" : "dark";
        const targetLabel = targetTheme === "light" ? "Light" : "Dark";
        const accessibleLabel = `Switch to ${targetTheme} theme`;

        toggle.dataset.targetTheme = targetTheme;
        toggle.setAttribute("aria-label", accessibleLabel);
        toggle.setAttribute("title", accessibleLabel);

        const icon = toggle.querySelector("[data-theme-icon]");
        const label = toggle.querySelector("[data-theme-label]");
        if (icon) {
            icon.innerHTML = '<svg aria-hidden="true" viewBox="0 0 20 20">' + (targetTheme === "light"
                ? '<circle cx="10" cy="10" r="3.5"/><path d="M10 1v2m0 14v2M1 10h2m14 0h2M3.6 3.6 5 5m10 10 1.4 1.4M3.6 16.4 5 15M15 5l1.4-1.4"/>'
                : '<path d="M16.8 12.2A7 7 0 0 1 7.8 3a7.2 7.2 0 1 0 9 9.2Z"/>') + '</svg>';
        }
        if (label) {
            label.textContent = targetLabel;
        }
    }

    function applyTheme(theme) {
        const normalizedTheme = normalizeTheme(theme);
        root.dataset.theme = normalizedTheme;
        root.style.colorScheme = normalizedTheme;
        document.querySelectorAll("[data-theme-toggle]")
                .forEach((toggle) => syncToggle(toggle, normalizedTheme));
    }

    function bindToggles() {
        applyTheme(root.dataset.theme);
        document.querySelectorAll("[data-theme-toggle]").forEach((toggle) => {
            if (toggle.dataset.themeBound === "true") {
                return;
            }
            toggle.dataset.themeBound = "true";
            toggle.addEventListener("click", () => {
                const targetTheme = root.dataset.theme === "light" ? "dark" : "light";
                applyTheme(targetTheme);
                saveTheme(targetTheme);
            });
        });
    }

    applyTheme(readTheme());

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindToggles, {once: true});
    } else {
        bindToggles();
    }
}());
