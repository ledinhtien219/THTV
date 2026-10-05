const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const code = fs.readFileSync('app/src/main/assets/youtube_quality.js', 'utf8');
const calls = [];
const location = {hostname: 'm.youtube.com', pathname: '/watch', href: 'https://m.youtube.com/watch?v=first'};
const player = {
    getAvailableQualityLevels: () => ['hd720', 'medium', 'auto'],
    getPlaybackQuality: () => 'medium',
    setPlaybackQualityRange: (...args) => calls.push(args)
};
const context = {window: {}, location, document: {getElementById: () => player}};
vm.runInNewContext(code, context);
const api = context.window.__thtvVideoQuality;
const options = api.options();
assert.deepEqual(Array.from(options.levels), ['hd720', 'medium', 'auto']);
assert.equal(options.current, 'medium'); assert.deepEqual(calls, []); // Opening menu never changes quality.
assert.equal(api.select('hd720', options.page), true); assert.deepEqual(calls, [['hd720', 'hd720']]);
assert.equal(api.select('auto', options.page), true); assert.deepEqual(calls[1], ['auto', 'auto']);
assert.equal(api.select('hd2160', options.page), false); // No invented resolutions.
location.href += '&changed=1'; assert.equal(api.select('medium', options.page), false);
location.hostname = 'youtube.com.example.org'; assert.equal(api.options(), null);
assert.equal(api.select('auto', location.href), false);
location.hostname = 'www.youtube.com'; location.pathname = '/results'; assert.equal(api.options(), null);
location.pathname = '/watch'; delete player.setPlaybackQualityRange; assert.equal(api.options(), null);
assert.equal(calls.length, 2);
console.log('PASS YouTube quality: available resolutions, explicit choice, automatic mode, stale page and domain guards');
