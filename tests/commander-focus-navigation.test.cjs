const fs = require('node:fs');
const assert = require('node:assert/strict');

const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);
const dashboard = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarDashboardView.kt',
  'utf8'
);
const youtube = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/YouTubePlayerHelper.kt',
  'utf8'
);

// Smart dispatcher must prefer semantic focus on the two primary car screens.
assert.match(presentation, /fun moveCommanderTarget\(dx: Float, dy: Float, source: String\)/);
assert.match(presentation, /dashboardView\?\.moveCommanderFocus\(dx, dy\)/);
assert.match(presentation, /YouTubePlayerHelper\.moveCommanderSelection\(web, ytStep\)/);
assert.match(presentation, /dashboardView\?\.clickCommanderFocus\(\)/);
assert.match(presentation, /YouTubePlayerHelper\.clickCommanderSelection\(web\)/);
assert.match(presentation, /nativeOverlayVisible/);

// Dashboard: visible/clickable regions become geometrically navigable and highlighted.
assert.match(dashboard, /private fun commanderTargets\(\): List<View>/);
assert.match(dashboard, /add\(cardYoutube\)/);
assert.match(dashboard, /add\(cardBrowser\)/);
assert.match(dashboard, /add\(cardBookmark\)/);
assert.match(dashboard, /favChannelsRow\?\.let/);
assert.match(dashboard, /fun moveCommanderFocus\(dx: Float, dy: Float\): Boolean/);
assert.match(dashboard, /fun clickCommanderFocus\(\): Boolean/);
assert.match(dashboard, /foreground = commanderOutline\(\)/);
assert.match(dashboard, /performClick\(\)/);

// YouTube results: focus individual watch cards, keep selection visible, then activate it.
assert.match(youtube, /fun moveCommanderSelection\(view: WebView\?, step: Int\): Boolean/);
assert.match(youtube, /location\.pathname\.indexOf\('\/results'\)/);
assert.match(youtube, /a\[href\*="\/watch"\]/);
assert.match(youtube, /carhud-commander-selected/);
assert.match(youtube, /scrollIntoView/);
assert.match(youtube, /fun clickCommanderSelection\(view: WebView\?\): Boolean/);
assert.match(youtube, /sessionStorage\.removeItem\('carhud_auto_play'\)/);
assert.match(youtube, /typeof link\.click === 'function'/);

console.log('PASS Commander semantic focus: Dashboard regions + YouTube search result cards');
