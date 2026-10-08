const fs = require('node:fs');
const assert = require('node:assert/strict');

const blocker = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/WebAdBlocker.kt',
  'utf8'
);
const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);
const settings = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/SettingsActivity.kt',
  'utf8'
);
const phone = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/MainActivity.kt',
  'utf8'
);

// Network filter stays conservative and never blocks the top-level page.
assert.match(blocker, /if \(request\.isForMainFrame\) return null/);
for (const host of [
  'doubleclick.net',
  'googlesyndication.com',
  'googleadservices.com',
  'taboola.com',
  'outbrain.com',
  'admicro.vn',
  'eclick.vn'
]) {
  assert.ok(blocker.includes('"' + host + '"'), 'missing ad host: ' + host);
}
assert.match(blocker, /host\.startsWith\("adservice\.google\."\)/);
assert.match(blocker, /host == domain \|\| host\.endsWith\("\.\$domain"\)/);

// Cosmetic filter must target known ad slots, not broad ".ad" selectors.
for (const selector of [
  'ins.adsbygoogle',
  '[id^="div-gpt-ad"]',
  '[data-ad-slot]',
  '[id*="taboola"]',
  '.OUTBRAIN'
]) {
  assert.ok(blocker.includes(selector), 'missing cosmetic selector: ' + selector);
}
assert.doesNotMatch(blocker, /'\.ad'/);

// Car Browser is the only car-app surface using this generic blocker.
assert.match(
  presentation,
  /currentActiveAppId == "web"[\s\S]*?KEY_BROWSER_ADBLOCK[\s\S]*?WebAdBlocker\.shouldIntercept/
);
assert.match(
  presentation,
  /else if \(currentActiveAppId == "web"\)[\s\S]*?WebAdBlocker\.applyCosmeticFiltering/
);
assert.match(
  presentation,
  /currentActiveAppId == "youtube"[\s\S]*?YouTubeAdBlocker\.shouldIntercept/
);

// User can disable Browser ad blocking from Settings.
assert.match(settings, /KEY_BROWSER_ADBLOCK = "browser_adblock_enabled"/);
assert.match(settings, /title = "Chặn quảng cáo khi duyệt Web"/);
assert.match(settings, /key = KEY_BROWSER_ADBLOCK,[\s\S]*?default = true/);

// Phone Browser mode follows the same preference while YouTube keeps its own blocker.
assert.match(phone, /private var phoneWebMode: String = "youtube"/);
assert.match(phone, /phoneWebMode = "web"[\s\S]*?loadUrl\("https:\/\/google\.com"\)/);
assert.match(
  phone,
  /phoneWebMode == "web"[\s\S]*?WebAdBlocker\.shouldIntercept[\s\S]*?else \{[\s\S]*?YouTubeAdBlocker\.shouldIntercept/
);

console.log('PASS web adblock: scoped network + cosmetic filtering, toggle, car/phone Browser only');
