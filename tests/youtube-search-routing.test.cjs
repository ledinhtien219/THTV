const fs = require('node:fs');
const assert = require('node:assert/strict');

const helper = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/YouTubePlayerHelper.kt',
  'utf8'
);
const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);
const inputSession = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarInputSession.kt',
  'utf8'
);
const searchScreen = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarSearchScreen.kt',
  'utf8'
);

// Manual search must be the default.
assert.match(
  helper,
  /fun search\(view: WebView\?, query: String, autoPlayFirst: Boolean = false\)/
);

// Every search invalidates older delayed voice jobs.
assert.match(helper, /private val searchGeneration = AtomicLong\(0L\)/);
assert.match(helper, /val generation = searchGeneration\.incrementAndGet\(\)/);
assert.match(helper, /fun cancelSearchAutoPlay\(view: WebView\?\)/);
assert.match(
  helper,
  /if \(searchGeneration\.get\(\) != generation\) return@postDelayed/
);

// Manual keyboard paths explicitly disable/cancel auto-play.
assert.match(
  presentation,
  /YouTubePlayerHelper\.search\(web, q, autoPlayFirst = false\)/
);
assert.match(
  presentation,
  /YouTubePlayerHelper\.search\(web, query, autoPlayFirst = false\)/
);
assert.match(presentation, /YouTubePlayerHelper\.cancelSearchAutoPlay\(web\)/);

// Voice keeps the deliberate Google-Assistant-like direct play behavior.
assert.match(
  presentation,
  /YouTubePlayerHelper\.search\(web, mediaQuery, autoPlayFirst = true\)/
);

// Presentation recreation must never choose an arbitrary first result.
assert.doesNotMatch(
  presentation,
  /YouTubePlayerHelper\.playFirstAvailableVideo\((?:web|view)\)/
);

console.log(
  'PASS YouTube search routing: keyboard shows results, stale voice autoplay cancelled, voice can direct-play'
);


// Host/native keyboard submission is a final action, not a draft handoff.
assert.match(
  presentation,
  /private fun executeSearch\(query: String, broadcast: Boolean = false\)/
);
assert.match(
  presentation,
  /current\.executeSearch\(value, broadcast = false\)/
);
assert.match(
  presentation,
  /executeSearch\(searchInput\.text\.toString\(\), broadcast = false\)/
);

// The host action uses the semantic label supplied by the active input mode.
assert.match(inputSession, /val submitLabel: String = "Nhập"/);
assert.match(searchScreen, /setTitle\(input\.submitLabel\)/);
