const fs = require('node:fs');
const assert = require('node:assert/strict');

const overlay = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/VietmapHudOverlay.kt',
  'utf8'
);
const presentation = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/CarPresentation.kt',
  'utf8'
);
const policy = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/WazeAlertPolicy.kt',
  'utf8'
);
const state = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/VietmapState.kt',
  'utf8'
);
const hlp = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/WazeHlpWebSocketManager.kt',
  'utf8'
);
const notifications = fs.readFileSync(
  'app/src/main/java/com/carhud/aaproxy/VietmapNotificationListenerService.kt',
  'utf8'
);

// HUD visibility is controlled from the phone app only.
assert.doesNotMatch(overlay, /Tắt bong bóng cảnh báo/);
assert.doesNotMatch(overlay, /closeButton/);
assert.doesNotMatch(overlay, /onCloseRequested/);
assert.doesNotMatch(overlay, /hitTestClose/);
assert.doesNotMatch(presentation, /onCloseRequested\s*=/);
assert.match(overlay, /fun hitTestLock\(/);

// Alert stream stability.
assert.match(policy, /fun dedupeAlerts\(alerts: List<WazeAlertItem>\)/);
assert.match(policy, /kotlin\.math\.abs\(da - db\) <= 80/);
assert.match(state, /val currentTelemetryFresh = cur\.isConnected/);
assert.match(state, /val distanceSaysPassed =/);
assert.match(state, /currentDistance != null[\s\S]*?currentDistance > 0/);
assert.match(state, /fun clearAlertFromSource\(source: String\)/);
assert.match(state, /WazeAlertPolicy\.dedupeAlerts\(upcomingAlerts\)/);
assert.match(state, /private fun scheduleAlertExpiry\(\)/);
assert.match(state, /WazeAlertPolicy\.alertTtlMs\(snapshot\)/);
assert.match(state, /!WazeAlertPolicy\.isAlertFresh\(current\)/);

assert.match(hlp, /lastStateReceivedAtMs/);
assert.match(hlp, /rollback > 30_000L \|\| \(receiveGap > 2_000L && rollback > 1_000L\)/);
assert.match(hlp, /HLP timestamp reset detected/);
assert.match(hlp, /VietmapStateRepository\.beginHlpSession\(\)/);
assert.match(hlp, /WazeAlertPolicy\.dedupeAlerts\(parsed\)/);
assert.match(hlp, /serverStarted/);
assert.match(hlp, /serverStarting/);
assert.match(hlp, /private fun scheduleServerRestart\(delayMs: Long\)/);
assert.match(hlp, /KeepAlive: listener unavailable, auto-restarting Waze Mod server/);
assert.match(hlp, /scheduleServerRestart\(if \(bindError\) 5_000L else 1_500L\)/);

assert.match(notifications, /activeWazeAlertNotificationKeys/);
assert.match(notifications, /updateWazeAlertNotificationKey\(sbn\.key, false\)/);
assert.match(notifications, /clearAlertFromSource\("WAZE_NOTIFICATION"\)/);

console.log('PASS Waze stability: no in-car close button + robust reconnect/dedupe/clear');
