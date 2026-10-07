const fs = require('node:fs');
const assert = require('node:assert/strict');

const proxy = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/IptvRequestProxy.kt',
  'utf8'
);
const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);
const player = fs.readFileSync(
  'app/src/main/assets/iptv_player.html',
  'utf8'
);

// IPTV HLS requests are bridged natively so file:// Hls.js can read them and
// provider-specific headers can be attached without forbidden browser headers.
assert.match(proxy, /object IptvRequestProxy/);
assert.match(proxy, /"Referer" to "https:\/\/fptplay\.vn\/"?/);
assert.match(proxy, /"Origin" to "https:\/\/fptplay\.vn"/);
assert.match(proxy, /rawHeaders\["Access-Control-Allow-Origin"\] = "\*"/);
assert.match(proxy, /FilterInputStream/);
assert.match(proxy, /incomingHeaders/);

assert.match(
  presentation,
  /currentActiveAppId == "iptv"[\s\S]*?IptvRequestProxy\.shouldIntercept\(request\)/
);
assert.match(
  presentation,
  /app\.id == "iptv"[\s\S]*?allowUniversalAccessFromFileURLs = true/
);
assert.match(presentation, /MIXED_CONTENT_ALWAYS_ALLOW/);
assert.match(presentation, /mediaPlaybackRequiresUserGesture = false/);

// Non-IPTV apps should not inherit universal file access.
assert.match(
  presentation,
  /else \{[\s\S]*?allowUniversalAccessFromFileURLs = false/
);

// YouTube-specific theme injection must not be applied blindly to IPTV.
assert.match(
  presentation,
  /if \(currentActiveAppId == "youtube"\)[\s\S]*?YouTubePlayerHelper\.applyTheme/
);

// HLS player rotates CDN/source candidates on hard network failures instead of
// retrying a single dead manifest indefinitely.
assert.match(player, /function buildIptvStreamCandidates\(channel\)/);
assert.match(player, /live\.fptplay53\.net/);
assert.match(player, /live-a\.fptplay53\.net/);
assert.match(player, /vips-livecdn\.fptplay\.net/);
assert.match(player, /dethich\.pw/);
assert.match(player, /status === 401 \|\| status === 403 \|\| status === 404 \|\| status >= 500/);
assert.match(player, /startCandidate\(candidateIndex \+ 1, 'network'\)/);
assert.match(player, /Đã thử ' \+ candidates\.length \+ ' nguồn/);

console.log('PASS IPTV playback networking: file access, native proxy headers/CORS, HLS failover');
