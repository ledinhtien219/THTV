const fs = require('node:fs');
const assert = require('node:assert/strict');

const helper = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/YouTubePlayerHelper.kt',
  'utf8'
);

// The shared persistent WebView must start neutral. Global force-dark is unsafe
// for arbitrary news/web pages because transparent backgrounds can become black
// while site text keeps its original dark color.
const perf = helper.match(
  /fun applyUltraPerformance\(web: WebView\)[\s\S]*?fun applyUniversalWebTheme/
)?.[0] || '';
assert.notEqual(perf, '');
assert.match(perf, /isAlgorithmicDarkeningAllowed = false/);
assert.match(perf, /settings\.forceDark = WebSettings\.FORCE_DARK_OFF/);
assert.match(perf, /setBackgroundColor\(Color\.WHITE\)/);

// Generic browser pages must not receive THTV's forced color-scheme/darkening.
// YouTube remains explicitly themed; IPTV keeps its own black/CSS surface.
const universal = helper.match(
  /fun applyUniversalWebTheme\(view: WebView\?, isDay: Boolean\? = null\)[\s\S]*?\n    fun applyTheme/
)?.[0] || '';
assert.notEqual(universal, '');
assert.match(universal, /val isYouTube = host == "youtube\.com"/);
assert.match(universal, /if \(isYouTube\)[\s\S]*?applyTheme\(view, effectiveIsDay\)/);
assert.match(universal, /view\.settings\.isAlgorithmicDarkeningAllowed = false/);
assert.match(universal, /view\.settings\.forceDark = WebSettings\.FORCE_DARK_OFF/);
assert.match(universal, /view\.setBackgroundColor\(if \(isIptv\) Color\.BLACK else Color\.WHITE\)/);
assert.match(universal, /getElementById\('carhud-universal-theme'\)/);
assert.match(universal, /removeProperty\('color-scheme'\)/);
assert.doesNotMatch(
  universal,
  /style\.textContent = ':root, html, body \{ color-scheme:/
);

console.log('PASS web theme safety: generic Browser pages keep site-native colors; YouTube/IPTV remain controlled');
