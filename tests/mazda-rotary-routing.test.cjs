const fs = require('node:fs');
const assert = require('node:assert/strict');

const screen = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarHudAutoScreen.kt',
  'utf8'
);
const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);

// Android Auto host path: expose PAN mode so non-touch rotary/nudge input can
// be translated into SurfaceCallback events on head units such as Mazda.
assert.match(screen, /\.addAction\(Action\.PAN\)/);
assert.match(screen, /\.setMapActionStrip\(mapActionStrip\)/);
assert.match(screen, /\.setPanModeListener\s*\{/);
assert.match(screen, /reportSurfaceInput\([\s\S]*?"SCROLL/);

// VirtualDisplay/raw path: probe rotary encoder and navigation key families.
assert.match(presentation, /InputDevice\.SOURCE_ROTARY_ENCODER/);
assert.match(presentation, /MotionEvent\.AXIS_SCROLL/);
assert.match(presentation, /KEYCODE_SYSTEM_NAVIGATION_UP/);
assert.match(presentation, /KEYCODE_NAVIGATE_NEXT/);
assert.match(presentation, /KEYCODE_DPAD_CENTER/);

// Cursor emulator: D-pad/PAN moves a visible pointer and center/select clicks
// through the app's existing touch dispatcher (including WebView synthetic touch).
assert.match(presentation, /fun showCommanderCursor\(source: String = "DPAD"\)/);
assert.match(presentation, /fun moveCommanderCursor\(dx: Float, dy: Float/);
assert.match(presentation, /fun clickCommanderCursor\(source: String = "DPAD"\): Boolean/);
assert.match(presentation, /dispatchTouch\(x, y\)/);
assert.match(presentation, /dy < 0f[\s\S]*?dispatchScroll\(0f, -140f\)/);
assert.match(presentation, /dy > 0f[\s\S]*?dispatchScroll\(0f, 140f\)/);
assert.match(screen, /panModeEnabled[\s\S]*?moveCommanderCursorFromSurface/);
assert.match(screen, /panModeEnabled[\s\S]*?clickCommanderTarget\("HOST SELECT"\)/);

// Diagnostics stay enabled in the test build so a real Mazda can reveal which
// path delivered each Commander event.
assert.match(presentation, /fun showRotaryDiagnostic\(message: String\)/);
assert.match(presentation, /Mazda test:/);

console.log('PASS Mazda Commander routing: host PAN + raw rotary/DPAD -> smart focus or cursor fallback');
