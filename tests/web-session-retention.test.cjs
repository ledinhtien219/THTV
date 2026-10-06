const fs = require('node:fs');
const assert = require('node:assert/strict');

const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);
const media = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarMediaManager.kt',
  'utf8'
);

// Browser and YouTube each keep their own navigation snapshot.
assert.match(media, /data class WebAppSession\(/);
assert.match(media, /private val webAppSessions = mutableMapOf<String, WebAppSession>\(\)/);
assert.match(media, /fun saveWebAppSession\(appId: String, web: WebView\?\)/);
assert.match(media, /web\.saveState\(state\)/);
assert.match(media, /scrollX = web\.scrollX/);
assert.match(media, /scrollY = web\.scrollY/);
assert.match(media, /videoPositionSec = if \(appId == "youtube"\) currentPositionSec else 0/);

assert.match(media, /fun restoreWebAppSession\(appId: String, web: WebView\?\): Boolean/);
assert.match(media, /web\.restoreState\(android\.os\.Bundle\(session\.state\)\)/);
assert.match(media, /web\.scrollTo\(session\.scrollX, session\.scrollY\)/);
assert.match(media, /YouTubePlayerHelper\.seekTo\(web, session\.videoPositionSec\.toLong\(\)\)/);

// Switching THTV apps snapshots the outgoing app and restores the incoming one.
assert.match(presentation, /CarMediaManager\.saveWebAppSession\(previousAppId, web\)/);
assert.match(presentation, /CarMediaManager\.hasWebAppSession\(app\.id\)/);
assert.match(presentation, /CarMediaManager\.restoreWebAppSession\(app\.id, web\)/);
assert.match(presentation, /if \(!restoredSession && \(!isAlreadyLoaded \|\| startUrl != null\)\)/);

// Switching to another Android Auto app also captures the current state.
assert.match(presentation, /override fun onStop\(\)[\s\S]*?saveWebAppSession\(currentActiveAppId, web\)/);
assert.match(presentation, /override fun dismiss\(\)[\s\S]*?saveWebAppSession\(currentActiveAppId, web\)/);

console.log('PASS web session retention: Browser + YouTube preserve state across app switches');
