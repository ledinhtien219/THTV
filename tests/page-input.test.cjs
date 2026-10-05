const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const code = fs.readFileSync('app/src/main/assets/page_input.js', 'utf8');

function fixture() {
    class Form {
        constructor() {
            this.submits = 0;
            this.attrs = {};
        }
        requestSubmit() { this.submits++; }
        submit() { this.submits++; }
        getAttribute(name) { return this.attrs[name] || ''; }
    }

    class Input {
        constructor(type = 'text', tag = 'INPUT') {
            this.tagName = tag;
            this.type = type;
            this.isConnected = true;
            this.disabled = false;
            this.readOnly = false;
            this.isContentEditable = false;
            this.events = [];
            this.focuses = 0;
            this.attrs = {};
            this.form = new Form();
        }
        get value() { return this._value || ''; }
        set value(value) { this._value = value; }
        dispatchEvent(event) { this.events.push(event.type); return true; }
        focus() { this.focuses++; }
        getAttribute(name) { return this.attrs[name] || ''; }
        closest(selector) {
            if (selector === 'form') return this.form;
            if (selector.includes('input') || selector.includes('textarea') || selector.includes('contenteditable')) return this;
            return null;
        }
    }

    class Textarea extends Input {
        constructor() { super('', 'TEXTAREA'); }
        get value() { return super.value; }
        set value(value) { super.value = value; }
    }

    const listeners = {};
    const context = {
        window: {},
        document: {
            addEventListener: (name, callback) => { listeners[name] = callback; }
        },
        location: {href: 'https://www.google.com/'},
        HTMLInputElement: Input,
        HTMLTextAreaElement: Textarea,
        Event: class {
            constructor(type) { this.type = type; }
        },
        KeyboardEvent: class {
            constructor(type) { this.type = type; }
        }
    };

    vm.runInNewContext(code, context);
    return {context, Input, Textarea, listeners, api: context.window.__thtvPageInput};
}

{
    const {api, Input} = fixture();
    const field = new Input('search');
    field.attrs.placeholder = 'Hỏi Google';
    const capture = api.capture(field);

    assert.equal(capture.action, 'search');
    assert.equal(api.fill(capture.token, 'xin chào'), 'OK');
    assert.equal(field.value, 'xin chào');
    assert.deepEqual(field.events, ['input', 'change']);
    assert.equal(field.form.submits, 0);
    assert.equal(field.focuses, 1);
}

{
    const {api, Input} = fixture();
    const field = new Input('text');
    field.attrs.name = 'q';
    const capture = api.capture(field);

    assert.equal(capture.action, 'search');
    assert.equal(api.submit(capture.token, 'thời tiết Hà Nội'), 'SUBMITTED');
    assert.equal(field.value, 'thời tiết Hà Nội');
    assert.equal(field.form.submits, 1);
    assert.deepEqual(field.events, ['input', 'change']);
}

{
    const {api, Input} = fixture();
    const field = new Input('text');
    field.attrs.placeholder = 'Nhập họ tên';
    const capture = api.capture(field);

    assert.equal(capture.action, 'input');
    assert.equal(api.submit(capture.token, 'Phạm Nam'), 'OK');
    assert.equal(field.value, 'Phạm Nam');
    assert.equal(field.form.submits, 0);
}

{
    const {api, Textarea} = fixture();
    const field = new Textarea();
    field.attrs.role = 'searchbox';
    const capture = api.capture(field);

    assert.equal(capture.action, 'search');
    assert.equal(api.submit(capture.token, '24h tiếng Việt / ? # "'), 'SUBMITTED');
    assert.equal(field.value, '24h tiếng Việt / ? # "');
    assert.equal(field.form.submits, 1);
}

{
    const {api, Input, context} = fixture();
    const field = new Input('search');
    const capture = api.capture(field);
    field.isConnected = false;
    assert.equal(api.fill(capture.token, 'wrong'), 'NO_TARGET');

    const replacement = new Input('search');
    const newer = api.capture(replacement);
    context.location.href += 'search?q=other';
    assert.equal(api.submit(newer.token, 'wrong'), 'NO_TARGET');
    assert.equal(replacement.value, '');
}

{
    const {api, Input, context, listeners} = fixture();
    const field = new Input('search');
    field.attrs.placeholder = 'Tìm Google';
    const requests = [];
    context.window.CarHudInput = {openKeyboard: (...args) => requests.push(args)};

    api.install();
    listeners.click({
        target: {closest: () => null},
        composedPath: () => [field],
        preventDefault() {},
        stopImmediatePropagation() {}
    });

    assert.equal(requests.length, 1);
    assert.equal(requests[0][2], 'Tìm Google');
    assert.equal(requests[0][3], 'search');
}

{
    const {api, Input} = fixture();
    const field = new Input('password');
    assert.equal(api.capture(field), null);
    field.type = 'text';
    field.readOnly = true;
    assert.equal(api.capture(field), null);
}

console.log('PASS shared page input: search fields stay on their page, generic fields only fill, stale targets are rejected');
