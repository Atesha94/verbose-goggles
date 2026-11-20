# Security & privacy model

## Data flow
Health data (heart rate, calories, workouts) is created on the phone or watch, stored locally, and only
written to the health platform the user selected after an explicit opt-in. There is no Tempo server.
The template's `api-server` from the Replit scaffold must not receive any health data.

## Controls implemented
| Area | Control |
|------|---------|
| Permissions | Only HR read, resting-HR read, workout/exercise write, active-energy write, HR write. Config plugin strips location/camera/mic/contacts/storage permissions and their iOS usage strings. Bluetooth only when the user connects a strap. |
| Watch apps | Wear OS app has **no INTERNET permission**. watchOS app talks only to its paired iPhone. |
| Input validation | Every template/session from storage, the watch link or native bridges is size-capped and validated (Kotlin `validateTemplate`, Swift `validate`, native `WorkoutRecord.validate`). |
| Idempotency | Health writes keyed by session id → retries cannot duplicate workouts. |
| BLE | Only service 0x180D / characteristic 0x2A37; parser bounds-checks bytes and rejects < 25 or > 250 bpm. |
| Backups | Android `allowBackup=false` + data-extraction rules exclude all app data from Google cloud backup and device transfer. |
| Transport | Cleartext traffic disabled. Watch transports are OS-authenticated (same signing key / paired device). |
| Data Layer hygiene | Session DataItems are deleted after the phone has received them. |
| Secrets | Signing keys, `google-services.json`, `agconnect-services.json` are git-ignored. No API keys in code. |
| SDKs | No analytics, crash-reporting or ad SDKs. |
| GDPR | On-device by default, opt-in sync, "Export my data" (portability) and "Delete all my data" (erasure) in Settings, privacy policy screen. |

## Before release
- Run `npx expo-doctor` and inspect the merged `AndroidManifest.xml` (`./gradlew :app:processReleaseManifest`) to confirm no extra permissions slipped in through dependencies.
- Enable R8/minify for release builds (done for Wear OS).
- Publish a privacy policy URL (required by App Store, Google Play Health Connect declaration and Huawei).
- Keep the Health Connect "data use declaration" consistent with this file.
