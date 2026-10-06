const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const asset = name => fs.readFileSync(path.join(__dirname, '../app/src/main/assets', name), 'utf8');

const iptvHtml = asset('iptv_player.html');
assert.match(iptvHtml, /video#videoPlayer::\-webkit-media-controls-overlay-play-button/);
assert.match(iptvHtml, /video#videoPlayer::\-webkit-media-controls-start-playback-button/);
assert.match(
  iptvHtml,
  /video#videoPlayer::\-webkit-media-controls[\s\S]*?display: none !important;[\s\S]*?pointer-events: none !important;/
);

function fixture(storage = new Map(), native = { mode: null }, storageFails = false) {
  const elements = new Map();
  function element(id) {
    if (elements.has(id)) return elements.get(id);
    const classes = new Set();
    const listeners = new Map();
    const styles = new Map();
    const e = {
      id, value: '', textContent: '', innerHTML: '', paused: true, muted: false, volume: 1,
      classList: { add: (...xs) => xs.forEach(x => classes.add(x)), remove: (...xs) => xs.forEach(x => classes.delete(x)), contains: x => classes.has(x), toggle: x => classes.has(x) ? classes.delete(x) : classes.add(x) },
      style: { removeProperty: x => styles.delete(x), setProperty: (k,v) => styles.set(k,v) },
      addEventListener: (event, fn) => { if (!listeners.has(event)) listeners.set(event, []); listeners.get(event).push(fn); },
      emit: (event, data = {}) => (listeners.get(event) || []).forEach(fn => fn(data)),
      play: () => Promise.resolve(), pause() { this.paused = true; },
      appendChild: () => {}, setAttribute: () => {}, removeAttribute: () => {}, querySelector: () => null, querySelectorAll: () => [],
      getBoundingClientRect: () => ({ width: 960, height: 540 }), styles, classes
    };
    elements.set(id,e);
    return e;
  }
  const body = element('.main-body');
  const windowEvents = new Map();
  const document = { getElementById: element, querySelector: element, querySelectorAll: () => [], body,
    addEventListener: () => {}, createElement: () => element('created'), documentElement: element('html') };
  const localStorage = {
    getItem: k => { if (storageFails) throw Error('disabled'); return storage.get(k) || null; },
    setItem: (k,v) => { if (storageFails) throw Error('disabled'); storage.set(k,v); }
  };
  const window = { localStorage, location: { search: '', hash: '' },
    ThtvIptvAspect: { get: () => native.mode, save: mode => { native.mode = mode; } },
    addEventListener: (event,fn) => { if(!windowEvents.has(event)) windowEvents.set(event,[]); windowEvents.get(event).push(fn); }
  };
  const context = vm.createContext({ window, document, localStorage, console, setTimeout: () => 1,
    clearTimeout: () => {}, setInterval: () => 1, clearInterval: () => {}, requestAnimationFrame: () => 1,
    cancelAnimationFrame: () => {}, navigator: { userAgent: 'test' } });
  vm.runInContext(asset('iptv_aspect.js'), context);
  const inline = [...asset('iptv_player.html').matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)];
  inline.forEach(s => vm.runInContext(s[1], context));
  return { window, context, video: element('videoPlayer'), player: element('playerSection'),
    emitWindow: event => (windowEvents.get(event) || []).forEach(fn => fn()), storage, native };
}

for (const mode of ['fill','contain','cover','4:3','21:9']) {
  const storage = new Map();
  const native = { mode: null };
  const f = fixture(storage,native);
  f.window.setVideoAspectRatio(mode);
  assert.equal(native.mode,mode);
  assert.equal(storage.get('iptv_fit_mode'),mode);
  for (let i=0; i<20; i++) {
    vm.runInContext(`playStream({name:'Channel ${i}',streamUrl:'https://test/channel${i}.mp4'},null)`,f.context);
    f.video.emit('emptied');
    f.video.emit('loadedmetadata');
    f.video.emit('resize');
    f.emitWindow('pageshow');
    assert.ok(f.video.classes.has('fit-'+mode.replace(':','-')));
    assert.equal(native.mode,mode);
  }
  // Normal/rapid video taps must only reveal controls.
  const click = { target: { closest: () => null }, stopPropagation() {} };
  f.player.emit('click',click);
  f.player.emit('click',click);
  assert.equal(f.window.ThtvIptvFit.mode,mode);
  // Recreate the entire document, as switching apps/reconnecting AA can do.
  const reloaded=fixture(storage,native);
  reloaded.window.restoreVideoAspectRatio();
  assert.equal(reloaded.window.ThtvIptvFit.mode,mode);
  assert.ok(reloaded.video.classes.has('fit-'+mode.replace(':','-')));
}
const legacy=fixture(new Map([['iptv_fit_mode','4:3']]),{mode:null});
vm.runInContext('initVideoFit()',legacy.context);
assert.equal(legacy.native.mode,'4:3');
legacy.video.styles.set('object-fit','fill');
legacy.window.restoreVideoAspectRatio();
assert.equal(legacy.video.styles.has('object-fit'),false);
legacy.window.setVideoAspectRatio('channels');
assert.equal(legacy.window.ThtvIptvFit.mode,'4:3');
assert.equal(legacy.window.ThtvIptvFit.apply('invalid'),false);
const unavailable=fixture(new Map(),{mode:'cover'},true);
unavailable.window.setVideoAspectRatio('21:9');
assert.equal(unavailable.native.mode,'21:9');
console.log('IPTV aspect tests PASS: five modes, 100 channel changes, reload, rapid taps, migration, storage errors');
