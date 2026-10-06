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
assert.match(screen, /reportSurfaceInput\("SCROLL/);

// VirtualDisplay/raw path: probe rotary encoder and navigation key families.
assert.match(presentation, /InputDevice\.SOURCE_ROTARY_ENCODER/);
assert.match(presentation, /MotionEvent\.AXIS_SCROLL/);
assert.match(presentation, /KEYCODE_SYSTEM_NAVIGATION_UP/);
assert.match(presentation, /KEYCODE_NAVIGATE_NEXT/);
assert.match(presentation, /KEYCODE_DPAD_CENTER/);

// All probe paths must report visibly on the car display.
assert.match(presentation, /fun showRotaryDiagnostic\(message: String\)/);
assert.match(presentation, /Mazda test:/);

console.log('PASS Mazda Commander probe: host PAN + raw rotary/DPAD diagnostics enabled');
