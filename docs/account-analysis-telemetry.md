# Account Analysis training telemetry (plugin side)

Server contract and analysis rules: `runescape-api/docs/account-analysis/PLUGIN_TELEMETRY_CONTRACT.md`.

## What it does

Opt-in (**Settings → Sync → Training telemetry**, default **off**). For the
verified, currently linked account only, the plugin aggregates XP into 60 s
windows and uploads them to `POST {apiUrl}/plugin/v1/accounts/me/xp-telemetry`
(schema v1). Each window carries skill, XP before/after, logged-in elapsed time
and an *estimated* active time (`xp-gap-v1`: time within the AFK timeout after
an XP event). Windows without XP are sent with `skill: null` so logged-in time
is known. No chat, keystrokes, location, inventory or bank data are included.

## Threading

| Work | Thread |
|---|---|
| `StatChanged` → `TrainingTelemetryCollector.offer()` (enqueue immutable event, ~75 ns) | client thread |
| Login / hop / logout → enqueue control events | client thread |
| Window aggregation, spool file I/O, HTTP (OkHttp async, one request in flight) | RuneLite executor (`@Schedule(asynchronous = true)`, every 15 s) |
| UI | none (no Swing changes) |

Logout and shutdown only enqueue a "logout" event and hand the flush to the
executor; nothing blocks on the network. Shutdown spools to disk only.

## Reliability

* Batches are written to `~/.runelite/runealytics/telemetry/<verified-account>/`
  **before** upload; files are written atomically and deleted only on HTTP 200.
* 409/413/422 are permanent (dropped); 401/403 pause 10 min; 429/5xx/network
  retry with exponential backoff and full jitter (5 s → 10 min).
* Spool bounded to 500 files / 5 MB / 14 days (oldest evicted).
* `sent_at` is set per attempt and excluded from the server's idempotency hash,
  so retries replay safely; bucket ids make every bucket idempotent.
* New session id on login, account switch or world-mode change; world hops keep
  the session (`world_hop` flag); suspended clients do not invent time
  (`reconnect` flag); XP decreases re-baseline (`tracker_reset`).
* The built-in XP Tracker, its chart, `/xp/batch` and `/xp/session` are unchanged.

## Measured overhead

`TelemetryOverheadBenchmark` (2 M simulated observations, one per tick, 4 skills,
JDK 21, this dev container): `offer()` ≈ 75 ns/observation on the client thread;
background aggregation ≈ 102 ns/observation; 0 dropped events. Numbers include
`System.nanoTime()` overhead and are indicative, not a JMH result.

## Verification status

The RuneLite-independent classes (`TrainingTelemetryCollector`, `TelemetrySpool`,
`TelemetryUploader`, `TelemetryBucket`) were compiled and their 16 unit tests run
with plain `javac`/JUnit. The glue in `RuneAlyticsPlugin` / `RunealyticsConfig`
could **not** be compiled in the authoring environment because
`repo.runelite.net` was blocked; run `./gradlew build test` before release.
