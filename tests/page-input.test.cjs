const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const code = fs.readFileSync('app/src/main/assets/page_input.js', 'utf8');
function fixture() {
    class Input {
        constructor(type = 'search', tag = 'INPUT') {
            this.tagName = tag; this.type = type; this.isConnected = true;
            this.events = []; this.submits = 0; this.focuses = 0;
            this.form = {requestSubmit: () => this.submits++};
        }
        get value() { return this._value || ''; }
        set value(value) { this._value = value; }
        dispatchEvent(event) { this.events.push(event.type); }
        focus() { this.focuses++; }
        getAttribute(name) { return name === 'placeholder' ? this.placeholder || '' : ''; }
        closest() { return this; }
    }
    class Textarea extends Input {
        constructor() { super('textarea', 'TEXTAREA'); }
        get value() { return super.value; }
        set value(value) { super.value = value; }
    }
    const listeners = {};
    const context = {window: {}, document: {addEventListener: (name, callback) => {listeners[name] = callback;}}, location: {href: 'https://www.google.com/search?q=old'}, HTMLInputElement: Input, HTMLTextAreaElement: Textarea, Event: class {constructor(type) {this.type = type;}}};
    vm.runInNewContext(code, context);
    return {context, Input, Textarea, listeners, api: context.window.__thtvPageInput};
}
{
    const {api, Input, context} = fixture();
    const field = new Input();
    const originalPage = context.location.href;
    const capture = api.capture(field);
    assert.equal(api.fill(capture.token, '24h'), 'OK');
    assert.equal(field.value, '24h');
    assert.deepEqual(field.events, ['input']);
    assert.equal(field.submits, 0); assert.equal(field.focuses, 0);
    assert.equal(context.location.href, originalPage);
    assert.equal(api.fill(capture.token, 'duplicate'), 'NO_TARGET');
}
{
    const {api, Textarea} = fixture();
    const field = new Textarea();
    field.value = 'old';
    const capture = api.capture(field);
    assert.equal(api.capture(field).token, capture.token); // Bridge + touch fallback keep the same target.
    assert.equal(api.fill(capture.token, '24h tiếng Việt / ? # "'), 'OK');
    assert.equal(field.value, '24h tiếng Việt / ? # "');
    assert.equal(api.fill(api.capture(field).token, ''), 'OK');
    assert.equal(field.value, '');
}
{
    const {api, Input, context} = fixture();
    const field = new Input();
    const capture = api.capture(field);
    field.isConnected = false;
    assert.equal(api.fill(capture.token, 'wrong'), 'NO_TARGET');
    const replacement = new Input();
    const newer = api.capture(replacement);
    assert.equal(api.fill(capture.token, 'wrong'), 'NO_TARGET');
    context.location.href += '&changed=true';
    assert.equal(api.fill(newer.token, 'wrong'), 'NO_TARGET');
    assert.equal(replacement.value, '');
}
{
    const {api, Input} = fixture();
    const field = new Input();
    field.isContentEditable = true; field.tagName = 'DIV';
    assert.equal(api.fill(api.capture(field).token, 'xin chào'), 'OK');
    assert.equal(field.textContent, 'xin chào');
    assert.equal(api.capture(new Input('password')), null);
    field.readOnly = true; assert.equal(api.capture(field), null);
}
for (const [page, hint] of [['https://m.youtube.com/', 'Tìm trên YouTube'], ['file:///android_asset/iptv_player.html', 'Tìm kênh TV'], ['https://www.google.com/', 'Tìm Google']]) {
    const {api, Input, context, listeners} = fixture();
    context.location.href = page;
    const field = new Input(); field.placeholder = hint;
    const requests = [];
    context.window.CarHudInput = {openKeyboard: (...args) => requests.push(args)};
    api.install();
    listeners.click({target: {closest: () => null}, composedPath: () => [field], preventDefault() {}, stopImmediatePropagation() {}});
    assert.equal(requests.length, 1); assert.equal(requests[0][2], hint);
    assert.equal(api.fill(requests[0][0], '24h'), 'OK');
    assert.equal(field.value, '24h'); assert.equal(field.submits, 0);
    assert.equal(context.location.href, page);
}
console.log('PASS shared input: YouTube, IPTV, Google, shadow fields, Vietnamese, empty values and stale targets; no keyboard navigation or submit');
