# Tempo: interval timer (HIIT, Tabata, EMOM, AMRAP, custom)

Tempo is split into two parts.

| Part | Where | What |
|------|-------|------|
| Phone app (iOS + Android) | **Replit** (Expo / React Native + TypeScript) | Timer engine, all screens, history and charts, local storage, the `HealthService` abstraction, a mock adapter, Jest tests |
| Native parts | **This repo, `native/`** | watchOS app, Wear OS app, HealthKit and Health Connect modules, watch sync, timer engine ported to Swift and Kotlin with tests |

**Why React Native/Expo:** one TypeScript codebase for both phones, the same language as the timer engine, and Expo modules make it easy to add Swift and Kotlin where native code is required (HealthKit, Health Connect, watches).

```
native/
├── SYNC_PROTOCOL.md            JSON protocol between phone ↔ watch
├── SECURITY.md                 Security and privacy model
├── ios/
│   ├── TempoCore/              Swift package: TimerEngine + HeartRate + XCTest
│   └── TempoWatch Watch App/   watchOS SwiftUI app (HKWorkoutSession, WatchConnectivity, en/sv)
├── android/
│   ├── timer-core/             Pure Kotlin: TimerEngine + HeartRate + JUnit (runs without the Android SDK)
│   └── wear/                   Wear OS app (Compose, Health Services, Data Layer, Ongoing Activity, en/sv)
└── expo-modules/tempo-health/  Expo module: HealthKit (Swift) + Health Connect (Kotlin) + watch bridge + config plugin
```

## Running the tests

```bash
cd native/android/timer-core && gradle test      # 26 tests, JVM only
cd native/ios/TempoCore && swift test            # requires macOS/Xcode
```

## Connecting the Replit app to the native parts

1. Download the Replit project, or clone it with Git, onto a Mac.
2. Copy `native/expo-modules/tempo-health` into `artifacts/tempo/modules/tempo-health`.
3. In `app.json` → `plugins`, add `"./modules/tempo-health/app.plugin.js"`.
4. In the code that creates the health adapters:
   ```ts
   import { createNativeHealthBridge } from '../modules/tempo-health/src';
   const bridge = createNativeHealthBridge(); // undefined in Expo Go → mock is used
   new AppleHealthKitAdapter(bridge); new AndroidHealthConnectAdapter(bridge);
   ```
5. Build a development build: `npx expo prebuild && eas build --profile development`.
6. **watchOS:** in Xcode, File → New → Target → *Watch App for iOS App*, then add the files from `native/ios/TempoWatch Watch App/` and the local package `native/ios/TempoCore`. Enable the HealthKit capability on the watch target.
7. **Wear OS:** open `native/android` in Android Studio and run `wear`. `applicationId` must match the phone app (`app.tempo.timer`), and both must be signed with the same key.

## Limitations and paid accounts

- **Apple Developer Program, $99/year.** Required for HealthKit on a real device, the watch app, TestFlight and the App Store. HealthKit does not run in the simulator for watch HR.
- **Google Play Console, $25 one-time.** Health Connect also requires a filled-in *Health apps declaration* and a privacy policy before publishing.
- **Samsung Health Data SDK.** Requires approval as a Samsung partner. Before that, Samsung data syncs through Health Connect (recommended).
- **Huawei Health Kit / Wear Engine.** Requires a Huawei developer account (identity verification) and an application for Health Kit scopes. HMS does not exist on Google phones.
- **Garmin, Fitbit, Polar, Strava.** Require separate developer approval and OAuth. They are stubs for now.
- **Expo Go and the Replit preview** cannot run HealthKit, Health Connect, BLE or the watches. They use the mock adapter.
- **iOS Live Activities** require a separate Widget Extension in Xcode (not built yet).
- **Swift code** has not been compiled in this environment (no macOS). Kotlin `timer-core` has been compiled and tested. The Wear OS app and Expo modules have not been built here, because the Android SDK is not available.

See `native/SECURITY.md` for the security model.
