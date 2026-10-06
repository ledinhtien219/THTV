const fs = require('node:fs');
const assert = require('node:assert/strict');

const src = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);

const start = src.indexOf('private fun buildYouTubeMediaToolbar');
assert.ok(start >= 0, 'YouTube toolbar builder missing');
const end = src.indexOf('private val autoHideHandler', start);
const block = src.slice(start, end);

const home = block.indexOf('toolButton(R.drawable.ic_bar_home, "Trang chủ")');
const mic = block.indexOf('toolButton(R.drawable.ic_bar_mic, "Giọng nói")');
const back = block.indexOf('toolButton(R.drawable.ic_bar_back, "Quay lại")');
const play = block.indexOf('toolButton(R.drawable.ic_bar_play_pause, "Phát / Dừng")');
const next = block.indexOf('toolButton(R.drawable.ic_bar_next, "Tiếp theo")');
const quality = block.indexOf('toolButton(R.drawable.ic_bar_setting, "Chất lượng")');

assert.ok(home >= 0 && mic > home, 'Mic must appear below Home');
assert.ok(back > mic, 'Back must appear below Mic');
assert.ok(play > back, 'Play/Pause order incorrect');
assert.ok(next > play, 'Next order incorrect');
assert.ok(quality > next, 'Quality order incorrect');
assert.match(block, /startVoiceSearch\(\)/);
assert.match(block, /micBtn = youtubeMicButton\.getChildAt\(0\) as\? ImageView/);

console.log('PASS YouTube toolbar: Home -> Voice -> Back -> Play/Pause -> Next -> Quality');
