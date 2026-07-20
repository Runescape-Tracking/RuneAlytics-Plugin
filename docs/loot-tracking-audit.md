# RuneAlytics Boss Loot & Kill-Count Tracking — Architecture Audit

Date: 2026-07-20 · Plugin version audited: 2.0.5 (branch `V2.0.5`)

This document is the Phase-1 deliverable of the loot-tracking overhaul: a
complete audit of the current collection architecture, every existing loot and
KC path, known race conditions and duplicate risks, a boss coverage matrix,
the recommended target architecture, and the phased implementation plan.

---

## 1. Current architecture summary

All loot flows through two classes:

- **`RuneAlyticsPlugin`** — owns every RuneLite `@Subscribe` handler and all
  *transient attribution state* (ground-loot sessions, pending pickpockets,
  crate-wait flags, Whisperer window, RoW snapshot, zero-loot pending deaths).
  It decides *which* collection path an event belongs to and forwards
  normalized `ItemStack` lists to the manager.
- **`LootTrackerManager`** — the single write path (`recordKill`) for every
  loot source. Owns boss-name normalization, the boss whitelist
  (`TRACKED_BOSS_IDS`), chest/widget readers, dedup state, the in-memory
  `BossKillStats` display cache, RuneLite-tracker import, and the debounced
  live sync.

Supporting services:

| Class | Role |
|---|---|
| `RewardSources` | Static widget-group → container-ID registry for chest/reward interfaces |
| `LootStorageManager` / `LootStorageData` | Per-account JSON persistence (`runealytics-loot-<user>.json`), debounced atomic saves |
| `LootTrackerApiClient` | Bulk-sync upload / server history download |
| `LootSyncMergeService` + `DefaultRuneLiteLootTrackerReader` | Absolute-merge reconcile of website + RuneLite's native loot-tracker rsprofile data, scoped by account |
| `DeathRecoveryGuard` | Suppresses inventory-diff loot during player death / gravestone / Death's Office recovery |
| `CurrentPlayerIdentityService` | Account scoping for sync (normalized RSN, mismatch detection) |
| `ItemValueResolver` | GE/alch value resolution incl. noted/charged variants |

Storage keys are normalized source names (`"Zulrah"`, `"Pickpocket: Guard"`,
`"Skilling: Fishing"`, `"Impling: Nature"`). One JSON file per account.

---

## 2. Existing loot collection paths

| # | Path | Trigger | Attribution | Dedup |
|---|------|---------|-------------|-------|
| 1 | **NPC ground drops** | `NpcLootReceived` | RuneLite kill attribution (authoritative) | none (fires once/kill) |
| 1b | **Zero-loot kills** | `HitsplatApplied(isMine)` → `ActorDeath` → 3-tick flush | NPC index bookkeeping; cancelled by `NpcLootReceived`; late loot upgrades the zero-loot kill | index-keyed upgrade window (8 s) |
| 2 | **Player/chest loot** | `PlayerLootReceived` | `lastChestSource` string set by chat/widget detection | 2 s window per source name |
| 3 | **Widget/container reads** | `WidgetLoaded` → `RewardSources.BY_WIDGET` → container read or deep widget walk (depth 4) | widget group ID | same 2 s window |
| 4 | **Inventory diff (crates)** | Tempoross/Wintertodt chat → snapshot → `ItemContainerChanged` diff | 60 s crate window | equipment-movement exclusion |
| 5 | **Ground item spawns** | `ItemSpawned` within 5 tiles of a kill ≤3 s old | per-kill `GroundLootSession`, closest-kill matching | supplement mode subtracts already-recorded items (10 s) |
| 6 | **Pickpocket** | `MenuOptionClicked("Pickpocket")` → snapshot → inventory diff (1.8 s window) | pending NPC name | each diff = one action |
| 7 | **Impling jars** | `MenuOptionClicked("Loot-jar")` → snapshot → diff (1.5 s) | jar item name | window consumed on first diff |
| 8 | **Skilling diff** | `StatChanged` (11 tracked skills) → snapshot → diff (1.5 s rolling) | active skill name | equipment exclusion, lamp/book suppression |
| 9 | **Direct container reads** | `ItemContainerChanged` for Wilderness Loot Chest / Lunar Chest container IDs | container ID | 2 s source window |
| 10 | **The Whisperer special flow** | KC chat → ground-item window (12 tiles, debounced 450 ms flush) | chat-parsed game KC attached | 2 s source window |
| 11 | **Ring of Wealth** | RoW chat message → inventory diff vs kill-time snapshot → append to last kill | 4 s snapshot window | append (no new kill) |
| 12 | **Pet messages** | "funny feeling…" chat → inventory diff or generic Pet record → append to last kill | 30 s `lastKilledBoss` window | pet appended, never a new kill |
| 13 | **RuneLite tracker import** | Manual/auto sync; reads `profiles2` rsprofile keys scoped to the account | per-item shortfall delta vs already-stored totals | delta import is idempotent |

## 3. Existing KC collection paths

| Path | Status |
|---|---|
| Local counter (`BossKillStats.killCount + 1`) | **Primary for everything** — this is the plugin's KC for almost every boss |
| Whisperer chat KC (`kill count is: N`) | Parsed and applied (`processPlayerLootWithGameKC`, seeds counter to `N-1`) |
| Generic chat KC (`parseKillCountMessage`) | **Parsed but discarded** — logs only, never applied ⚠ |
| RuneLite import `killCount` | Used as a floor when importing (`max(local+1, recKC)`) |
| Server merge KC | Max-wins during manual sync merge |
| Widget KC / varbit KC / scoreboard KC | **Not implemented** |

**Key finding:** the game tells us the authoritative KC in chat for nearly
every boss, raid, Barrows, Gauntlet, Wintertodt and Tempoross — and the plugin
throws it away for everything except the Whisperer. This is the single
highest-value gap.

## 4. Known race conditions

1. **`NpcLootReceived` vs zero-loot flush** — handled (index-keyed upgrade
   window), but the 8 s window is wall-clock, not tick-based.
2. **KC chat vs loot event ordering** — the KC message can land on the tick
   before *or* after `NpcLootReceived`. Nothing correlates them today (except
   the Whisperer's bespoke flow).
3. **Widget read vs `PlayerLootReceived`** — both can fire for one chest;
   only the 2 s name-keyed window protects, so a *reopened* chest interface
   (>2 s later) records the same reward twice. ⚠
4. **`lastChestSource` is a single global string** — a chat line for source A
   followed by a widget for source B within the same window mislabels loot.
5. **Executor-scheduled container reads (300–600 ms)** race interface close:
   container may be empty or gone → loot silently lost (logged only).
6. **Skilling window vs banking** — a bank withdrawal inside the 1.5 s
   post-XP-drop window is recorded as skilling loot. Equipment moves are
   excluded; bank/GE/shop/trade moves are **not**. ⚠
7. **Account switch mid-window** — transient state is cleared on
   `LOGIN_SCREEN`, but `HOPPING` preserves attribution windows across worlds
   (mostly correct, but ground sessions keep stale `WorldPoint`s).

## 5. Known duplicate risks

| Risk | Current mitigation | Gap |
|---|---|---|
| Chest widget parsed twice (reopen) | 2 s per-source window | reopen after 2 s duplicates the whole batch ⚠ |
| `PlayerLootReceived` + widget read | same 2 s window | same gap |
| Ground items + `NpcLootReceived` | supplement diff vs last kill (10 s) | quantity-level, OK |
| Zero-loot kill + late loot event | upgrade path | OK |
| Pet chat + pet item | single append, diff identifies item | OK |
| RoW coins + ItemSpawned | RoW items bypass ItemSpawned by design | OK |
| RuneLite import re-run | per-item shortfall delta | OK |
| Server re-upload | `synced_to_server` flag + sync slot | no server-side idempotency key (UUID/fingerprint) ⚠ |
| KC double-increment (chat + death + widget) | N/A — chat KC unused | must be solved when chat KC is wired in |

## 6. Boss coverage matrix

Legend — **Loot method**: `NLR` = NpcLootReceived, `ZLK` = zero-loot kill path,
`WID` = widget read, `CON` = container read, `DIF` = inventory diff,
`GND` = ground-item window. **KC**: `local` = plugin counter, `chat` = game chat KC.

| Boss | NPC/widget IDs | Loot method | KC method | Status |
|---|---|---|---|---|
| Giant Mole | (name match: none) | NLR/ZLK via `trackAllNpcs` | local | **Partial** — not in `TRACKED_BOSS_IDS`, relies on `trackAllNpcs` default-on |
| King Black Dragon | 50 (partial set) | NLR | local (chat KC ignored) | Supported |
| Chaos Elemental | 2054 | NLR | local | Supported |
| Scorpia / Chaos Fanatic | 6615 / 6619 | NLR | local | Supported |
| Obor / Bryophyta | not listed | `trackAllNpcs` fallback | local | **Partial** |
| Sarachnis | not listed | `trackAllNpcs` fallback | local | **Partial** |
| Kalphite Queen | 963/965 | NLR (two phases both listed) | local | Supported |
| Corporeal Beast | 319 | NLR | local | Supported |
| Kraken / Cerberus / Thermy | 494/496, 5862, 7605? | NLR | local | Supported (Kraken whirlpool phase OK — loot on final NPC) |
| Alchemical Hydra | 8609 (final phase only) | NLR | local | Supported — phase IDs 8615-8622 not listed but loot fires on final |
| Araxxor | 13668-13670 | NLR | local | Supported |
| Abyssal Sire | 5886 (one ID of several) | NLR | local | **Partial** — respawn phases 5887-5908 not listed; `matchesBossName("abyssal")` catches it |
| Grotesque Guardians | 7544? (7851/7852 missing) | NLR ×2 (Dusk drops) | local | **Partial** — Dusk final-form ID depends on RuneLite attribution (OK), whitelist stale |
| Dagannoth Kings | 2265-2267 | NLR | local | Supported |
| GWD bosses + Nex | listed | NLR | local | Supported (minion kills also tracked if `trackAllNpcs`) |
| Wilderness bosses | listed | NLR | local | Supported |
| Revenants | not listed | `trackAllNpcs` | local | Partial by design |
| Vorkath | 8059/8060 | NLR | local (chat KC ignored) | Supported |
| Zulrah | 2042-2044 | NLR (fires on final phase despawn) | local | Supported — phase rotations don't multi-count (single NpcLootReceived) |
| Muspah | **not listed anywhere** | `trackAllNpcs` fallback | local | **Unsupported as boss** ⚠ |
| Gauntlet / Corrupted | widget 700/595, container 179 | CON | local ("completion count" chat ignored) | Supported |
| Nightmare / Phosani | widget 600, container 646 | CON (name via chat) | local | Supported; Phosani-vs-Nightmare depends on chat ordering |
| Nex | 11278-11282 | NLR (personal loot) | local | Supported |
| Duke / Leviathan / Vardorvis | 12166/12167, 12193/12214, 12205(*) | NLR | local | Supported — (*) 12205 is mislabeled: it's listed under Whisperer's chest set |
| The Whisperer | 12225-12227, widget 834 | GND special flow | **chat game KC** ✓ | Supported (best-in-class path) |
| Tormented Demons / Demonic gorillas | 13147-13149 / not listed | NLR / `trackAllNpcs` | local | Supported / Partial |
| Barrows | widget 155, container | CON | local ("chest count" chat ignored) | Supported |
| Chambers of Xeric | widget 539, container | CON | local ("completed count" ignored) | Supported |
| Theatre of Blood | widget 23, container | CON | local | Supported |
| Tombs of Amascut | widget 773, container | CON | local | Supported |
| Moons of Peril | Lunar Chest container | CON (direct container change) | local | Supported |
| Fortis Colosseum | widget 867 | WID walk | local | Supported |
| Yama / Royal Titans / Hueycoatl | widgets 810/174, chest IDs | WID walk / CON | local | Supported |
| Wintertodt | widget 634 + DIF fallback | WID + DIF | local ("subdued count" ignored) | Supported, double-source risk mitigated by 2 s window |
| Tempoross | widget 229 + DIF fallback | WID + DIF | local | Supported |
| Zalcano | widget 620, container 631 | CON | local | Supported |
| Hespori | widget 897 | WID walk | local | Supported |
| Skotizo | 6230-6234 | NLR | local | Supported |
| Clues (all tiers) | widget 73 child 10 | WID walk | n/a (completion count ignored) | Supported |
| Wilderness Loot Chest | container | CON | n/a | Supported |
| Guardians of the Rift | — | — | — | **Unsupported** |
| Hueycoatl | 14000-14014 | chest set | local | Supported |
| Doom of Mokhaiotl | name match | NLR/name | local | Supported (adds blacklisted) |

## 7. Recommended target architecture

Centralize toward an encounter-correlation model, incrementally:

- **`KillCountResolver`** *(implemented in this change)* — single source of
  truth for authoritative game KC. Every KC-bearing chat form is parsed into a
  per-boss observation; the kill write path consumes observations within a
  correlation window; late observations retro-fix the just-recorded kill.
  Ordered precedence: game chat KC → RuneLite event KC → local counter.
  Monotonic guard rejects regressions; state is account-scoped and cleared on
  logout.
- **`RewardBatchDeduplicator`** *(implemented)* — canonical fingerprint
  (source + sorted `itemId:qty` multiset) over a longer window than the 2 s
  name lock, so a reopened chest/widget interface can never re-record the same
  reward batch.
- **`InventoryDiffGuard`** *(implemented)* — bank / GE / shop / trade /
  deposit-box / seed-vault interface awareness. All inventory-diff paths
  (skilling, pickpocket, impling, crate) are suspended while these are open
  and for a short cooldown after they close, mirroring `DeathRecoveryGuard`.
- **`EncounterRegistry`** *(future, Phase 3+)* — migrate `TRACKED_BOSS_IDS`,
  `CHEST_LOOT_NPC_IDS`, `BOSS_NAME_TO_ID`, `RewardSources`, and the special
  cases in `onWidgetLoaded` into data-driven `EncounterDefinition`s (NPC ID
  sets incl. phase/final IDs, reward method, reward delay, KC patterns,
  dedupe window, finalize trigger).
- **`LootCaptureCoordinator` / `ActiveEncounter`** *(future, Phase 3+)* —
  every signal becomes a `LootCandidate`; the coordinator validates account,
  source and encounter state before the single `recordKill` write. The current
  `GroundLootSession`/window fields in the plugin become encounter state.
- **Sync idempotency** *(future, requires server change)* — attach a stable
  event UUID + fingerprint per kill record so the server can upsert instead of
  insert. Until then, the `synced_to_server` flag + single sync slot remains
  the guard.

## 8. Implementation plan

| Phase | Content | Risk |
|---|---|---|
| 1 (this change) | Audit + coverage matrix; `BossNames` extraction (pure name canonicalisation, seed of the registry); `KillCountResolver` wired into `recordKill` (game KC for all bosses, once, monotonic); `RewardBatchDeduplicator` (chest/widget re-read protection); `InventoryDiffGuard` (bank/trade/shop false-positive protection); persistence of last known game KC per boss; name coverage for Muspah/Sarachnis/Obor/Bryophyta/Giant Mole/Lunar Chest; tests | Low — additive, single write path untouched |
| 2 | Event UUID + fingerprint on `KillRecord`; include in bulk-sync payload (server upsert required) | Server coordination |
| 3 | `EncounterRegistry` data model; migrate whitelist/name/chest constants; add missing bosses (Muspah, Sarachnis, Obor, Bryophyta, Grotesque current IDs) | Medium — behavior-preserving refactor |
| 4 | `ActiveEncounter` + coordinator; route ground/widget/chat/container signals as candidates | Medium |
| 5 | Raid adapters (points, team size, raid level), varbit/widget KC sources | Medium |
| 6 | Replay-fixture test harness (tick-scripted event timelines) | Low |
| 7 | Unrecognized-encounter diagnostics + debug export | Low |

### Non-negotiables honored in Phase 1

- No working path removed; all changes are additive guards/enrichment.
- Game KC never double-increments: an observation is consumed exactly once,
  and a late observation only relabels the already-recorded kill.
- KC regressions rejected (per-boss monotonic within an account session).
- Account isolation: resolver + dedup state cleared on logout/account switch.
- Fail-safe: every new component is exception-isolated from event handlers.
