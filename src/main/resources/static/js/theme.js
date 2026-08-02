(function () {
    "use strict";

    const storageKey = "recognition-validator-theme";
    const root = document.documentElement;

    function normalizeTheme(theme) {
        return theme === "light" ? "light" : "dark";
    }

    function readTheme() {
        try {
            return normalizeTheme(window.localStorage.getItem(storageKey));
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
            icon.textContent = targetTheme === "light" ? "☀" : "☾";
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
