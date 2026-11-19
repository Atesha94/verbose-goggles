/**
 * Expo config plugin: adds only the entitlements, Info.plist strings and Android manifest entries
 * the health integration needs. Add to app.json → "plugins": ["./modules/tempo-health/app.plugin.js"].
 */
const { withEntitlementsPlist, withInfoPlist, withAndroidManifest } = require('@expo/config-plugins');

const HEALTH_SHARE = 'Tempo reads your heart rate to show live heart-rate zones during workouts. Your data stays on your devices.';
const HEALTH_UPDATE = 'Tempo saves finished workouts, heart rate and active calories to Apple Health.';

const ANDROID_PERMISSIONS = [
  'android.permission.health.READ_HEART_RATE',
  'android.permission.health.READ_RESTING_HEART_RATE',
  'android.permission.health.WRITE_HEART_RATE',
  'android.permission.health.WRITE_EXERCISE',
  'android.permission.health.WRITE_ACTIVE_CALORIES_BURNED',
];

// Permissions that template dependencies sometimes add and that Tempo must never request.
const ANDROID_BLOCKED = [
  'android.permission.ACCESS_FINE_LOCATION',
  'android.permission.ACCESS_COARSE_LOCATION',
  'android.permission.ACCESS_BACKGROUND_LOCATION',
  'android.permission.CAMERA',
  'android.permission.RECORD_AUDIO',
  'android.permission.READ_CONTACTS',
  'android.permission.READ_EXTERNAL_STORAGE',
  'android.permission.WRITE_EXTERNAL_STORAGE',
  'android.permission.READ_MEDIA_IMAGES',
  'android.permission.SYSTEM_ALERT_WINDOW',
];

module.exports = function withTempoHealth(config) {
  config = withEntitlementsPlist(config, c => {
    c.modResults['com.apple.developer.healthkit'] = true;
    c.modResults['com.apple.developer.healthkit.access'] = [];
    return c;
  });

  config = withInfoPlist(config, c => {
    c.modResults.NSHealthShareUsageDescription = c.modResults.NSHealthShareUsageDescription ?? HEALTH_SHARE;
    c.modResults.NSHealthUpdateUsageDescription = c.modResults.NSHealthUpdateUsageDescription ?? HEALTH_UPDATE;
    // Remove usage strings for capabilities Tempo does not use (App Review rejects unused ones).
    for (const k of ['NSLocationWhenInUseUsageDescription', 'NSLocationAlwaysAndWhenInUseUsageDescription',
      'NSCameraUsageDescription', 'NSPhotoLibraryUsageDescription', 'NSMicrophoneUsageDescription', 'NSContactsUsageDescription']) {
      delete c.modResults[k];
    }
    return c;
  });

  config = withAndroidManifest(config, c => {
    const m = c.modResults.manifest;
    m.$['xmlns:tools'] = m.$['xmlns:tools'] ?? 'http://schemas.android.com/tools';
    m['uses-permission'] = (m['uses-permission'] ?? []).filter(p => !ANDROID_BLOCKED.includes(p.$['android:name']));
    for (const name of ANDROID_PERMISSIONS) {
      if (!m['uses-permission'].some(p => p.$['android:name'] === name)) m['uses-permission'].push({ $: { 'android:name': name } });
    }
    for (const name of ANDROID_BLOCKED) {
      m['uses-permission'].push({ $: { 'android:name': name, 'tools:node': 'remove' } });
    }
    m.queries = [{ package: [{ $: { 'android:name': 'com.google.android.apps.healthdata' } }] }];

    const app = m.application[0];
    app.$['android:allowBackup'] = 'false';
    app.$['android:usesCleartextTraffic'] = 'false';
    // Health Connect requires an activity that shows the privacy policy / permission rationale.
    const main = app.activity.find(a => a.$['android:name'] === '.MainActivity');
    if (main) {
      main['intent-filter'] = main['intent-filter'] ?? [];
      main['intent-filter'].push({ action: [{ $: { 'android:name': 'androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE' } }] });
    }
    app['activity-alias'] = (app['activity-alias'] ?? []).concat([{
      $: {
        'android:name': 'ViewPermissionUsageActivity', 'android:exported': 'true',
        'android:targetActivity': '.MainActivity', 'android:permission': 'android.permission.START_VIEW_PERMISSION_USAGE',
      },
      'intent-filter': [{
        action: [{ $: { 'android:name': 'android.intent.action.VIEW_PERMISSION_USAGE' } }],
        category: [{ $: { 'android:name': 'android.intent.category.HEALTH_PERMISSIONS' } }],
      }],
    }]);
    return c;
  });

  return config;
};
