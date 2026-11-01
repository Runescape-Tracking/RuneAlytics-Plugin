# Achievement progress sync (quests, diaries, combat achievements)

Code: `src/main/java/com/runealytics/progress/`. Target: RuneLite 1.13.4 (`latest.release` on 2026-11-01).

## What is captured

| Category | Source (RuneLite gameval) | Notes |
|---|---|---|
| Quest state (every `net.runelite.api.Quest`) | `Quest.getState(client)` → script 4029 `QUEST_STATUS_GET` | `quest_id` = `Quest#getId()` (stable); `key` = enum constant name (informational). |
| Diary tiers (12 regions × 4 tiers) | `VarbitID.<R>_DIARY_<T>_COMPLETE`, `<R>_<T>_REWARD`, `<R>_<T|MED>_COUNT`; Karamja easy–hard use `ATJUN_*_DONE/REWARD` | Sent **raw** with source varbit ids. Meaning of COMPLETE vs REWARD to be verified in game. Individual tasks: `not_supported`. |
| Combat achievements | `VarPlayerID.CA_TASK_COMPLETED_0..20` (unsigned words), `CA_TOTAL_TASKS_COMPLETED_*`, `CA_POINTS`, `CA_TIER_STATUS_*`, `CA_LAMP_CLAIMED_*` | Raw bits; the website decodes with a catalog. Coverage is `complete` only when set-bit count = sum of tier counters. |

## Flow

1. `LOGGED_IN` arms a bounded readiness gate (≥2 ticks, 1 quiet tick, account hash known, local baseline loaded; gives up after 12 ticks).
2. Scan on `GameTick` (never inside `VarbitChanged`, because `runScript` is not reentrant). Sanity checks: quests are `unavailable` if 0 finished but QP > 0; CA is `unavailable` if all words are 0 but counters > 0.
3. `VarbitChanged` only marks state dirty. A rescan runs after 2 quiet ticks with ≥10 ticks between scans (forced after 50 ticks). There is also a full re-read every 30 min.
4. `ProgressDiffer` compares against the per-account baseline (account hash + `RuneScapeProfileType`; beta worlds skipped).
   - First observation = historical import, so **no events**.
   - Forward changes become `transitions` (`live`, or `catch_up` for the first scan after login).
5. `ProgressOutbox` (persisted in `plugin-data/runealytics/progress/`) queues changed keys and transitions. One frozen batch is in flight at a time; retries resend identical bytes; backoff runs 5 s → 10 min with ±30% jitter.

**Uploads** go to `POST {apiUrl}/plugin/v1/accounts/me/progress` with the existing `Authorization: Bearer <verification code>` plus `Idempotency-Key: <batch_id>`. They are sent only when the server feature flag `achievements` is on and the config toggle **Enable Achievement Sync** is on. Capture continues locally while uploads are off.

## Response handling expected from the server

| Status | Plugin behaviour |
|---|---|
| 2xx | ack; batch dropped from outbox (a replay of an already-stored `batch_id` must also return 2xx) |
| 401 | keep batch; unbind credentials until re-verified |
| 404 / 405 / 410 | keep batch; retry in 30 min (endpoint not deployed) |
| 408 / 425 / 429 / 5xx / network | keep batch; exponential backoff |
| other 4xx (400, 403, 409, 413, 422) | drop batch; queue a full snapshot; 1 h cool-down |

## Payload (schema_version 1)

Envelope: `schema_version, batch_id (uuid), kind (snapshot|delta), session_id (uuid), installation_id, sequence (per account, monotonic), username, world_mode, profile_type, client{plugin_progress_version, catalog_version}, captured_at, built_at, coverage{…}, quests[], diaries[], combat_achievements{}, transitions[]`.

- `coverage` values: `complete`, `unavailable`, `raw_unverified`, `partial_changed_only`, `not_supported`. A category missing from a delta's `coverage` means "not included in this batch".
- `transitions[]`: `id (uuid), category (quest|diary|combat_achievement_task|combat_achievement_tier), key, name?, field, from, to, observed_at, detection (live|catch_up), previous_observed_at?`.
  - Quest key: the quest id.
  - Diary key: `REGION:TIER`, with field `complete|reward|task_count`.
  - CA task key: `<varpId>:<bit>`.
  - CA tier key: the tier name, with field `status|lamp_claimed`.
- No completion dates are invented. `observed_at` is when RuneAlytics saw the value.

## In-game verification still needed

1. **Diary flags:** complete a tier without claiming the reward, then log `COMPLETE` / `REWARD` / `COUNT` before and after claiming.
2. **CA counters:** confirm `CA_TOTAL_TASKS_COMPLETED_*` and `CA_POINTS` are populated without opening the interface. Check a task stored at bit 31.
3. **Login timing:** log the tick of the last `VarbitChanged` after login, and the scan duration (debug log `[Progress] scan #… took … µs`).
4. **Quest list:** compare the quest list against the cache quest table (`DBTableID.Quest`) to find quests missing from the RuneLite enum.
