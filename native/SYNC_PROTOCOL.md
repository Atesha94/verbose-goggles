# Phone ↔ watch sync protocol (v1)

All three ends (phone app, watchOS, Wear OS) exchange the same JSON envelope:

```json
{ "v": 1, "kind": "templates" | "session" | "command", "templates": [...], "session": {...}, "command": {...} }
```

| kind      | Direction        | iOS transport                          | Android transport                          |
|-----------|------------------|----------------------------------------|--------------------------------------------|
| templates | phone ↔ watch    | `updateApplicationContext` (latest wins) | DataItem `/tempo/templates`                |
| session   | watch → phone    | `transferUserInfo` (queued, durable)   | DataItem `/tempo/session/<id>` (deleted after delivery) |
| command   | phone → watch    | `sendMessage` (only when reachable)    | Message `/tempo/command`                   |

`command.action` ∈ `start | pause | resume | skip | back | stop`; `start` carries `templateId`.

Templates use the same field names as `WorkoutTemplate` in the Replit app (`type` is lowercase:
`tabata | hiit | emom | amrap | rounds | custom`). Sessions use the same fields as `Session`, plus `source`.

## Rules every receiver enforces
- Payloads over 512 KB are dropped.
- Unknown `v` is dropped (forward compatibility: old watches ignore new messages instead of crashing).
- Every template is re-validated (name 1–60 chars, durations 0–86 400 s, work ≥ 1, rounds/sets 1–100); invalid ones are dropped.
- At most 200 templates per message.
- Sessions are de-duplicated by `id` on the phone, and the health write uses `id` as the idempotency key
  (`clientRecordId` on Health Connect, metadata on HealthKit), so a retried sync never creates a duplicate workout.
- Transport is local only (Bluetooth/Wi-Fi via the OS). No cloud relay. Wear OS Data Layer only delivers between
  apps with the same `applicationId` **and** signing key; WatchConnectivity only between an iPhone app and its own watch app.
