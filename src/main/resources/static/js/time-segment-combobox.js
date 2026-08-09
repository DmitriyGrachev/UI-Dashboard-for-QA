(function (root, factory) {
    const api = factory();
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    }
    if (root && typeof root === "object") {
        root.TimeSegmentCombobox = api;
    }
})(typeof window !== "undefined" ? window : globalThis, () => {
    "use strict";

    function normalize(value, min, max) {
        const text = String(value ?? "").trim();
        if (!/^\d{1,2}$/.test(text)) return null;
        const number = Number(text);
        if (!Number.isInteger(number) || number < min || number > max) {
            return null;
        }
        return String(number).padStart(2, "0");
    }

    function createValues(min, max) {
        return Array.from(
            {length: max - min + 1},
            (_, index) => String(min + index).padStart(2, "0")
        );
    }

    function create({
        input,
        toggle,
        listbox,
        min,
        max,
        onCommit = () => {},
        onInvalid = () => {}
    }) {
        if (!input || !toggle || !listbox) {
            throw new TypeError("Time combobox input, toggle and listbox are required.");
        }

        const documentObject = input.ownerDocument;
        const wrapper = input.parentElement;
        const values = createValues(min, max);
        let acceptedValue = normalize(input.value, min, max) || values[0];
        let destroyed = false;

        input.value = acceptedValue;
        input.setAttribute("role", "combobox");
        input.setAttribute("aria-autocomplete", "list");
        input.setAttribute("aria-haspopup", "listbox");
        input.setAttribute("aria-expanded", "false");
        if (listbox.id) input.setAttribute("aria-controls", listbox.id);
        listbox.setAttribute("role", "listbox");
        listbox.hidden = true;

        function setInvalid(invalid) {
            if (invalid) {
                input.setAttribute("aria-invalid", "true");
            } else {
                input.removeAttribute("aria-invalid");
            }
        }

        function markSelected(value) {
            Array.from(listbox.children).forEach(option => {
                const selected = option.getAttribute("data-value") === value;
                option.setAttribute("aria-selected", String(selected));
                if (selected && option.id) {
                    input.setAttribute("aria-activedescendant", option.id);
                }
            });
        }

        function close() {
            listbox.hidden = true;
            input.setAttribute("aria-expanded", "false");
            toggle.setAttribute("aria-expanded", "false");
        }

        function open() {
            listbox.hidden = false;
            input.setAttribute("aria-expanded", "true");
            toggle.setAttribute("aria-expanded", "true");
            markSelected(normalize(input.value, min, max) || acceptedValue);
        }

        function commitValue(rawValue, notify = true) {
            const nextValue = normalize(rawValue, min, max);
            if (nextValue === null) {
                setInvalid(true);
                return false;
            }
            const changed = nextValue !== acceptedValue;
            input.value = nextValue;
            acceptedValue = nextValue;
            setInvalid(false);
            markSelected(nextValue);
            if (changed && notify) onCommit(nextValue);
            return true;
        }

        function onInput() {
            input.value = input.value.replace(/\D/g, "").slice(0, 2);
            setInvalid(false);
        }

        function onKeyDown(event) {
            if (event.key === "ArrowUp" || event.key === "ArrowDown") {
                event.preventDefault();
                const current = Number(normalize(input.value, min, max) || acceptedValue);
                const delta = event.key === "ArrowUp" ? 1 : -1;
                const next = Math.min(max, Math.max(min, current + delta));
                commitValue(String(next));
                return;
            }
            if (event.key === "Enter") {
                event.preventDefault();
                if (commitValue(input.value)) {
                    close();
                } else {
                    onInvalid();
                }
                return;
            }
            if (event.key === "Tab") {
                if (!commitValue(input.value)) onInvalid();
                close();
                return;
            }
            if (event.key === "Escape") {
                event.preventDefault();
                input.value = acceptedValue;
                setInvalid(false);
                close();
            }
        }

        function onBlur(event) {
            if (wrapper?.contains(event.relatedTarget)) return;
            if (!commitValue(input.value)) onInvalid();
            close();
        }

        function onToggle(event) {
            event.preventDefault();
            if (listbox.hidden) {
                open();
            } else {
                close();
            }
        }

        function onOutsidePointer(event) {
            if (!wrapper?.contains(event.target)) close();
        }

        const options = values.map((value, index) => {
            const option = documentObject.createElement("button");
            option.type = "button";
            option.id = listbox.id ? `${listbox.id}-option-${index}` : "";
            option.textContent = value;
            option.setAttribute("role", "option");
            option.setAttribute("data-value", value);
            option.setAttribute("aria-selected", String(value === acceptedValue));
            option.addEventListener("click", event => {
                event.preventDefault();
                commitValue(value);
                close();
                input.focus();
            });
            return option;
        });
        listbox.replaceChildren(...options);
        markSelected(acceptedValue);

        input.addEventListener("input", onInput);
        input.addEventListener("keydown", onKeyDown);
        input.addEventListener("blur", onBlur);
        toggle.addEventListener("click", onToggle);
        documentObject.addEventListener("pointerdown", onOutsidePointer);

        return {
            getValue() {
                return normalize(input.value, min, max);
            },
            setValue(value, notify = false) {
                return commitValue(value, notify);
            },
            validate() {
                return commitValue(input.value, false);
            },
            reset() {
                commitValue(values[0], false);
                close();
            },
            close,
            destroy() {
                if (destroyed) return;
                destroyed = true;
                input.removeEventListener("input", onInput);
                input.removeEventListener("keydown", onKeyDown);
                input.removeEventListener("blur", onBlur);
                toggle.removeEventListener("click", onToggle);
                documentObject.removeEventListener("pointerdown", onOutsidePointer);
            }
        };
    }

    return {
        create,
        createValues,
        normalize
    };
});
