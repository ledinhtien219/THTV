const fs = require('node:fs');
const assert = require('node:assert/strict');

const dashboard = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarDashboardView.kt',
  'utf8'
);
const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);

function section(source, startNeedle, endNeedle) {
  const start = source.indexOf(startNeedle);
  assert.notEqual(start, -1, `Missing section start: ${startNeedle}`);
  const end = source.indexOf(endNeedle, start + startNeedle.length);
  assert.notEqual(end, -1, `Missing section end: ${endNeedle}`);
  return source.slice(start, end);
}

// Dashboard taps must trigger exactly one visible app transition.
// switchWebApp() already enters fullscreen, so a second fullscreen callback
// only rebuilds chrome and re-runs resume logic.
assert.doesNotMatch(dashboard, /onFullscreenRequested\?\.invoke/);

// Quick IPTV chips must navigate through the real persistent WebView, not the
// retired embedded-WebView placeholder.
assert.match(dashboard, /iptv_player\.html#channel=/);
assert.match(dashboard, /android\.net\.Uri\.encode\(name\)/);
assert.doesNotMatch(dashboard, /currentEmbeddedWeb\?\.evaluateJavascript/);

// The presentation recognizes an explicit quick-channel URL and forces it to
// load even when IPTV is already the active app.
assert.match(
  presentation,
  /app\.id == "iptv" && it\.contains\("iptv_player\.html#channel="\)/
);
assert.match(
  presentation,
  /switchWebApp\(app, embedded = false, startUrl = explicitUrl\)/
);

const switchWebApp = section(
  presentation,
  'fun switchWebApp(app: WebAppItem',
  'val isShowingDashboard: Boolean'
);
assert.match(switchWebApp, /showWebFullscreen\(app\)/);
assert.doesNotMatch(switchWebApp, /rebuildTopToolbar\(\)/);
assert.doesNotMatch(switchWebApp, /rebuildAppGrid\(\)/);
assert.doesNotMatch(switchWebApp, /refreshAppsList\(\)/);
assert.doesNotMatch(switchWebApp, /resetAutoHideTimer\(\)/);

const fullscreen = section(
  presentation,
  'fun showWebFullscreen(app: WebAppItem?',
  'fun showDashboard()'
);
// Returning to a page must preserve a deliberate pause. The transition may
// resume only the active media app and only when playback was requested.
assert.doesNotMatch(fullscreen, /v\.play\(\)/);
assert.doesNotMatch(fullscreen, /p\.playVideo\(\)/);
assert.match(fullscreen, /val expectedAppId = currentActiveAppId/);
assert.match(fullscreen, /currentActiveAppId != expectedAppId/);
assert.match(fullscreen, /CarMediaManager\.activeAppId != expectedAppId/);
assert.match(fullscreen, /"youtube" -> scheduleSafeResume\(web, 450L\)/);
assert.match(fullscreen, /"iptv" -> if \(CarMediaManager\.userWantsPlayback\)/);
assert.match(fullscreen, /!window\.__iptvUserPaused/);

const safeResume = section(
  presentation,
  'private fun scheduleSafeResume',
  'private var isUltrawide'
);
assert.match(safeResume, /currentActiveAppId != "youtube"/);
assert.match(safeResume, /CarMediaManager\.activeAppId != "youtube"/);
assert.match(safeResume, /CarMediaManager\.getPersistentWebView\(\) !== target/);

// Launcher construction is deferred until the launcher is actually opened.
const showGrid = section(
  presentation,
  'fun showAppGridOverlay()',
  'fun hideAppGridOverlay()'
);
assert.match(showGrid, /rebuildAppGrid\(\)/);

console.log('PASS app switching: single transitions, quick IPTV routing, pause preservation, stale-resume guards');
