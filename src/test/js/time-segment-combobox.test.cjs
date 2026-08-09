const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const modulePath = path.resolve(
    __dirname,
    "../../main/resources/static/js/time-segment-combobox.js"
);
const moduleExists = fs.existsSync(modulePath);
const api = moduleExists ? require(modulePath) : {};

test("time segment combobox module exists", () => {
    assert.equal(moduleExists, true);
    assert.equal(typeof api.normalize, "function");
    assert.equal(typeof api.createValues, "function");
    assert.equal(typeof api.create, "function");
});

test("time segment normalization accepts only an inclusive numeric range", {
    skip: typeof api.normalize !== "function"
}, () => {
    assert.equal(api.normalize("2", 0, 23), "02");
    assert.equal(api.normalize("00", 0, 23), "00");
    assert.equal(api.normalize("23", 0, 23), "23");
    assert.equal(api.normalize("59", 0, 59), "59");
    assert.equal(api.normalize("", 0, 23), null);
    assert.equal(api.normalize("-1", 0, 23), null);
    assert.equal(api.normalize("2a", 0, 23), null);
    assert.equal(api.normalize("24", 0, 23), null);
    assert.equal(api.normalize("60", 0, 59), null);
});

test("time segment values include every two-digit option", {
    skip: typeof api.createValues !== "function"
}, () => {
    const hours = api.createValues(0, 23);
    const minutes = api.createValues(0, 59);

    assert.equal(hours.length, 24);
    assert.equal(hours[0], "00");
    assert.equal(hours[23], "23");
    assert.equal(minutes.length, 60);
    assert.equal(minutes[0], "00");
    assert.equal(minutes[37], "37");
    assert.equal(minutes[59], "59");
});

function fakeDocument() {
    const document = {
        activeElement: null,
        listeners: new Map(),
        createElement(tagName) {
            return fakeElement(document, {tagName});
        },
        addEventListener(name, listener) {
            this.listeners.set(name, listener);
        },
        removeEventListener(name) {
            this.listeners.delete(name);
        },
        dispatch(name, event) {
            this.listeners.get(name)?.(event);
        }
    };
    return document;
}

function fakeElement(document, {value = "", tagName = "div"} = {}) {
    const attributes = new Map();
    const listeners = new Map();
    const element = {
        ownerDocument: document,
        tagName: tagName.toUpperCase(),
        value,
        hidden: false,
        textContent: "",
        children: [],
        parentElement: null,
        setAttribute(name, attributeValue) {
            attributes.set(name, String(attributeValue));
        },
        removeAttribute(name) {
            attributes.delete(name);
        },
        getAttribute(name) {
            return attributes.get(name) ?? null;
        },
        addEventListener(name, listener) {
            const entries = listeners.get(name) ?? [];
            entries.push(listener);
            listeners.set(name, entries);
        },
        removeEventListener(name, listener) {
            const entries = listeners.get(name) ?? [];
            listeners.set(name, entries.filter(entry => entry !== listener));
        },
        dispatch(name, extra = {}) {
            const event = {
                target: element,
                currentTarget: element,
                preventDefault() {
                    this.defaultPrevented = true;
                },
                ...extra
            };
            (listeners.get(name) ?? []).forEach(listener => listener(event));
            return event;
        },
        appendChild(child) {
            child.parentElement = element;
            element.children.push(child);
            return child;
        },
        replaceChildren(...children) {
            element.children = [];
            children.forEach(child => element.appendChild(child));
        },
        contains(candidate) {
            return candidate === element
                || element.children.some(child => child.contains(candidate));
        },
        focus() {
            document.activeElement = element;
        },
        closest() {
            return element.parentElement;
        }
    };
    return element;
}

function createFixture(initialValue = "00") {
    const document = fakeDocument();
    const wrapper = fakeElement(document);
    const input = fakeElement(document, {value: initialValue, tagName: "input"});
    const toggle = fakeElement(document, {tagName: "button"});
    const listbox = fakeElement(document);
    wrapper.appendChild(input);
    wrapper.appendChild(toggle);
    wrapper.appendChild(listbox);
    return {document, wrapper, input, toggle, listbox};
}

test("combobox supports typing, list selection and keyboard commits", {
    skip: typeof api.create !== "function"
}, () => {
    const fixture = createFixture("00");
    const commits = [];
    const combobox = api.create({
        input: fixture.input,
        toggle: fixture.toggle,
        listbox: fixture.listbox,
        min: 0,
        max: 23,
        onCommit: value => commits.push(value)
    });

    assert.equal(fixture.listbox.children.length, 24);
    assert.equal(fixture.input.getAttribute("aria-expanded"), "false");

    fixture.input.value = "2";
    fixture.input.dispatch("keydown", {key: "Tab"});
    assert.equal(fixture.input.value, "02");
    assert.deepEqual(commits, ["02"]);

    fixture.input.dispatch("keydown", {key: "ArrowUp"});
    assert.equal(fixture.input.value, "03");
    assert.deepEqual(commits, ["02", "03"]);

    fixture.toggle.dispatch("click");
    assert.equal(fixture.listbox.hidden, false);
    assert.equal(fixture.input.getAttribute("aria-expanded"), "true");

    fixture.listbox.children[7].dispatch("click");
    assert.equal(fixture.input.value, "07");
    assert.equal(fixture.listbox.hidden, true);
    assert.deepEqual(commits, ["02", "03", "07"]);

    fixture.input.value = "09";
    fixture.input.dispatch("keydown", {key: "Escape"});
    assert.equal(fixture.input.value, "07");
    assert.deepEqual(commits, ["02", "03", "07"]);

    combobox.destroy();
});

test("combobox rejects invalid input without committing it", {
    skip: typeof api.create !== "function"
}, () => {
    const fixture = createFixture("00");
    const commits = [];
    const invalidAttempts = [];
    const combobox = api.create({
        input: fixture.input,
        toggle: fixture.toggle,
        listbox: fixture.listbox,
        min: 0,
        max: 59,
        onCommit: value => commits.push(value),
        onInvalid: () => invalidAttempts.push(fixture.input.value)
    });

    fixture.input.value = "60";
    fixture.input.dispatch("keydown", {key: "Enter"});

    assert.equal(combobox.validate(), false);
    assert.equal(fixture.input.getAttribute("aria-invalid"), "true");
    assert.deepEqual(commits, []);
    assert.deepEqual(invalidAttempts, ["60"]);
    combobox.destroy();
});
