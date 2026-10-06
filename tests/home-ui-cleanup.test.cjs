const fs = require('node:fs');
const assert = require('node:assert/strict');

const screen = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarHudAutoScreen.kt',
  'utf8'
);
const dashboardKt = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarDashboardView.kt',
  'utf8'
);
const dashboardXml = fs.readFileSync(
  'app/src/main/res/layout/dashboard.xml',
  'utf8'
);

// The host-rendered BACK action and list-template overlay must both be gone.
// Keep NavigationTemplate for the full app surface, but make the required
// placeholder action fully transparent.
assert.doesNotMatch(screen, /Action\.BACK/);
assert.doesNotMatch(screen, /PlaceListNavigationTemplate/);
assert.doesNotMatch(screen, /ItemList\.Builder/);
assert.match(screen, /CarColor\.createCustom\(0x00000000, 0x00000000\)/);
assert.match(screen, /ActionStrip\.Builder\(\)[\s\S]*?setTitle\("\\u200B"\)[\s\S]*?setBackgroundColor\(transparent\)/);
assert.match(screen, /NavigationTemplate\.Builder\(\)[\s\S]*?setActionStrip\(hiddenActionStrip\)/);

// Keep the existing weather-card bounds, but render weather with vector icons.
assert.match(dashboardXml, /android:id="@\+id\/weatherCard"[\s\S]*?android:layout_width="150dp"[\s\S]*?android:layout_height="76dp"/);
assert.match(dashboardXml, /<ImageView[\s\S]*?android:id="@\+id\/weatherIcon"[\s\S]*?android:layout_width="38dp"/);
assert.match(dashboardXml, /android:src="@drawable\/ic_weather_partly_cloudy"/);
assert.match(dashboardKt, /private lateinit var weatherIcon: ImageView/);
assert.match(dashboardKt, /fun weatherIconRes\(iconEmoji: String\): Int/);
assert.match(dashboardKt, /weatherIcon\.setImageResource\(weatherIconRes\(weather\.iconEmoji\)\)/);

for (const file of [
  'ic_weather_sunny.xml',
  'ic_weather_partly_cloudy.xml',
  'ic_weather_fog.xml',
  'ic_weather_rain.xml',
  'ic_weather_storm.xml'
]) {
  assert.ok(fs.existsSync('app/src/main/res/drawable/' + file), file + ' missing');
}

console.log('PASS home UI cleanup: back action removed + vector weather icons');
