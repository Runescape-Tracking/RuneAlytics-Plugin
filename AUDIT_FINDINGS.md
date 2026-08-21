# RuneAlytics Loot Tracking Audit Against RuneLite

## Executive Summary

RuneAlytics currently handles loot tracking through multiple event pathways but has several significant gaps:

1. **No ServerNpcLoot support** - RuneLite's authoritative server-provided NPC loot events are not consumed
2. **No PluginLootReceived support** - Third-party plugin loot events are not handled  
3. **Limited Doom of Mokhaiotl support** - No progressive/delve progression state management
4. **No explicit pending encounter lifecycle** - Delayed rewards rely on implicit state
5. **Ground item fallback is unscoped** - Can misattribute items in group PvM situations
6. **No script-based reward detection** - ScriptPreFired events are not monitored
7. **Missing specialized encounter handling** - Several boss types have incomplete detection

## Current Implementation Summary

### Event Handlers Implemented

| Event | Handler | Purpose |
|-------|---------|---------|
| `NpcLootReceived` | `onNpcLootReceived` | Ground drops from NPC kills |
| `PlayerLootReceived` | `onPlayerLootReceived` | Chest/raid rewards |
| `WidgetLoaded` | `onWidgetLoaded` | Widget-based rewards (Barrows, CoX, ToB, etc) |
| `WidgetClosed` | `onWidgetClosed` | Close tracking for inventory diff guard |
| `ItemContainerChanged` | `onItemContainerChanged` | Inventory diff (skilling, pickpocket, impling, crates) |
| `MenuOptionClicked` | `onMenuOptionClicked` | Menu-driven actions (pickpocket, clue caskets, impling loot) |
| `ItemSpawned` | `onItemSpawned` | Ground item spawns (supplemental) |
| `ChatMessage` | `onChatMessage` | KC detection and completion messages |
| `ActorDeath` | `onActorDeath` | Zero-loot kills |
| `NpcDespawned` | `onNpcDespawned` | Boss tracking |
| `GameTick` | `onGameTick` | Timing-based state updates |
| `GameStateChanged` | `onGameStateChanged` | Logout/account switch detection |

### Widget/Container Rewards Tracked

| Source | Widget ID | Container ID | Detection Method |
|--------|-----------|--------------|------------------|
| Barrows | 155 | BARROWS_REWARD | Container read |
| Chambers of Xeric | 539 | CHAMBERS_CHEST | Container read |
| Theatre of Blood | 23 | TOB_CHEST | Container read |
| Tombs of Amascut | 773 | TOA_CHEST | Container read |
| The Gauntlet | 595 | 179 | Container read |
| Corrupted Gauntlet | 700 | 179 | Container read |
| Nightmare | 600 | 646 | Container read |
| Zalcano | 620 | 631 | Container read |
| Tempoross | 229 | null | Widget tree walk |
| Wintertodt | 634 | special | Inventory diff fallback |
| Clue Casket | 73 | null | Widget tree walk + menu |
| Royal Titans | 174 | null | Widget tree walk |
| Yama | 810 | null | Widget tree walk |
| Fortis Colosseum | 867 | null | Widget tree walk |
| Hespori | 897 | null | Widget tree walk |
| The Whisperer | 834 | null | Widget tree walk (custom delay) |

### Loot Paths Implemented

1. **NPC Ground Drops** → NpcLootReceived direct attribution
2. **Player/Chest Rewards** → PlayerLootReceived + Widget container reads (2s dedup window)
3. **Widget-based Rewards** → WidgetLoaded + widget tree traversal (4-level deep search)
4. **Inventory Diffs** → Menu triggers + snapshot comparisons (skilling, pickpocket, impling, crates)
5. **Ground Item Fallback** → ItemSpawned scanning within 3s of kill
6. **Pickpocket/Thieving** → MenuOptionClicked → inventory diff
7. **Skilling Loot** → Skill XP gain → inventory diff + safeguards
8. **Clue Scrolls** → ChatMessage detection → WidgetLoaded → casket read

### Deduplication Mechanisms

1. **NPC Loot** - No dedup (RuneLite fires once per kill)
2. **Player/Chest Loot** - 2-second window per source name
3. **Reward Batch Dedup** - 90s batch fingerprinting for reopened interfaces
4. **Inventory Diff** - No duplicate suppression (legitimate for identical consecutive diffs)
5. **Late KC Reconciliation** - 2-second window for chat messages to relabel kills

### Safety Guards Implemented

1. **Death Recovery Guard** - 5-minute suppression window for gravestone/Death's Office recovery
2. **Inventory Diff Guard** - Suppresses diffs during bank/GE/shop/trade/vault/group storage
3. **Equipment Movement Filter** - Excludes equipment slot changes
4. **Account Isolation** - Session-scoped KC resolver cleared on logout
5. **Zero-Loot Kill Handling** - Records kills with no drops
6. **Late Loot Attribution** - Attaches delayed NpcLootReceived to recent kills

---

## RuneLite Authoritative Implementation (Current)

### Events NOT Consumed by RuneAlytics

1. **ServerNpcLoot** - Server-authoritative NPC loot events (introduced ~2020)
   - Bypasses ground-item inference
   - Contains item ID, quantity, NPC ID, guaranteed attribution
   - Used by RuneLite's own Loot Tracker as primary source

2. **PluginLootReceived** - Third-party plugin-generated loot events
   - Allows other plugins to contribute loot events
   - Used by some Gauntlet, Nightmare, and specialty encounter plugins

3. **ScriptPreFired** - Script execution notifications
   - Several bosses/encounters use widgets driven by scripts
   - Can indicate reward generation before items appear in containers
   - Examples: certain raid boss phases, widget state changes

### RuneLite Widget/Script Dependencies

RuneLite's current Loot Tracker uses:
- Widget groups 73 (Clue), 155 (Barrows), 539 (CoX), 23 (ToB), 773 (ToA), etc.
- ScriptID monitoring for certain encounters
- Chat parsing for KC and completion detection  
- VarBit/VarP monitoring for progressive encounters
- Inventory container reads (most reliable path)

### Known RuneLite Gap Areas

Some RuneLite weaknesses that RuneAlytics can improve upon:
1. RuneLite doesn't track progressive encounters separately (e.g., Delves)
2. RuneLite doesn't distinguish progression from final rewards clearly
3. RuneLite's ground item fallback is unscoped (potential cross-player attribution)
4. RuneLite doesn't handle all third-party plugin loot sources

---

## RuneAlytics Gaps Identified

### CRITICAL GAPS

1. **No ServerNpcLoot Handler**
   - Impact: Missing opportunity to use server-authoritative loot data
   - Affects: All NPC kills where ServerNpcLoot event fires
   - Fix Required: Subscribe to ServerNpcLoot, use as primary source over NpcLootReceived

2. **No PluginLootReceived Handler**  
   - Impact: Missing loot from third-party plugins (e.g., Gauntlet plugins)
   - Affects: Encounters supported by external plugins
   - Fix Required: Subscribe to PluginLootReceived, consume as secondary source

3. **Doom of Mokhaiotl / Delves Incomplete**
   - Impact: Progressive encounter progression not tracked separately from loot
   - Affects: Running totals show incorrect data structure
   - Fix Required: Implement DoomRunState, track progression, distinguish claimed loot

4. **Ground Item Attribution Unscoped**
   - Impact: Group PvM, Wilderness, crowded areas can misattribute items
   - Affects: Any multi-player scenario, Wilderness bosses  
   - Fix Required: Add world, location, and ownership filtering to ground item fallback

### HIGH PRIORITY GAPS

5. **No Script-Based Reward Detection**
   - Impact: Some newer bosses may generate rewards through scripts
   - Affects: Royal Titans, newer Varlamore content, specialty encounters
   - Fix Required: Monitor ScriptPreFired, map relevant script IDs

6. **Pending Encounter Lifecycle Implicit**
   - Impact: Delayed rewards could theoretically attach to wrong encounter
   - Affects: Any boss with separated kill and reward timing
   - Fix Required: Create explicit PendingLootEncounter tracking with timeouts

7. **Limited Specialized Encounter Support**
   - Impact: Some encounters missing KC tracking or reward extraction
   - Affects: Kolodion, Fortis Colosseum variants, newer content
   - Fix Required: Add missing widget IDs, scripts, or chat patterns

### MEDIUM PRIORITY GAPS

8. **No Transaction ID System**
   - Impact: Deduplication relies on timing windows alone
   - Affects: Accuracy under network/GC jitter
   - Fix Required: Add transaction UUIDs to loot records

9. **GE Value Staleness**
   - Impact: Recorded values may not reflect current market prices
   - Affects: Accuracy of item value metrics on website
   - Note: Server-side revaluation partially mitigates

10. **Account Profile Switching Not Fully Scoped**
    - Impact: Pending state could leak if account switches mid-encounter  
    - Affects: Complex multi-accounting scenarios
    - Fix Required: Validate account/profile on every pending encounter access

11. **Limited Inventory Snapshot Handling**
    - Impact: Can't reliably track noted vs unnoted or charged vs uncharged items
    - Affects: Accuracy of certain drops (especially runes, potions, charged items)
    - Fix Required: Enhance inventory snapshot to track item meta

---

## Specialized Encounter Analysis

### Barrows
- **Current**: Widget 155 + container read ✓
- **Detection**: WidgetLoaded on group 155
- **Issue**: No chest interaction tracking (widget fires once, no confirm needed)
- **Status**: **WORKS** - but no KC separate from loot count

### Gauntlet / Corrupted Gauntlet  
- **Current**: Widget 595/700 + container read ✓
- **Issue**: No explicit Gauntlet vs Corrupted distinction
- **Issue**: No pending encounter for chest-opening delay
- **Status**: **PARTIAL** - needs variant detection and delayed reward handling

### Chambers of Xeric / Theatre of Blood / Tombs of Amascut
- **Current**: Widget groups 539/23/773 + container reads ✓
- **Detection**: WidgetLoaded on group
- **Issue**: No personal-loot vs broadcast detection
- **Status**: **WORKS** - loot is accurately read from containers

### Doom of Mokhaiotl / Delves
- **Current**: Chat message parsing only ("Mokhaiotl" in message)
- **Issue**: No progression tracking, no delve state
- **Issue**: Each progression stage fires KC message + widget
- **Issue**: No distinction between progression and claimed reward
- **Status**: **BROKEN** - treats progression as kills, loses floor data

### Clue Scrolls
- **Current**: ChatMessage → MenuOptionClicked → WidgetLoaded (73) → widget read ✓
- **Detection**: Chat "Treasure Trail" → menu "open" → widget 73 load
- **Status**: **WORKS** - full reward captured

### Skilling Loot
- **Current**: Skill XP → inventory diff with guards ✓
- **Scope**: Woodcutting, Fishing, Mining, Farming, Hunter, Herblore, Runecraft, Fletching, Cooking, Smithing, Crafting
- **Guards**: Equipment exclusion, inventory diff guard, death recovery guard
- **Status**: **MOSTLY WORKS** - some edge cases (e.g., Farming seed drops on planting)

### PvP / Wilderness Loot
- **Current**: NpcLootReceived (player loot uses PlayerLootReceived)
- **Issue**: No scope/location checks, ground items can be misattributed
- **Status**: **PARTIAL** - works for direct kills, risky with ground items

### Death / Gravestone Recovery
- **Current**: DeathRecoveryGuard suppresses for 5 minutes ✓
- **Scope**: Suppresses ItemSpawned, ItemContainerChanged during recovery
- **Note**: Does NOT suppress NpcLootReceived/PlayerLootReceived (correctly)
- **Status**: **WORKS** - properly protected

### Bank / GE / Shop / Trade
- **Current**: InventoryDiffGuard suppresses inventory-diff loot ✓
- **Scope**: Bank, GE, shop, trade, deposit box, seed vault, group storage
- **Status**: **WORKS** - properly isolated

### RuneLite Default Loot Tracker Sync
- **Current**: DefaultRuneLiteLootTrackerReader reads profile2 files ✓
- **Scope**: Reads only current account's RuneLite data
- **Status**: **WORKS** - account-isolated sync

---

## Implementation Priority Matrix

| Gap | Severity | Difficulty | Impact | Priority |
|-----|----------|-----------|--------|----------|
| ServerNpcLoot handler | High | Low | Improves accuracy for all NPC kills | P0 |
| PluginLootReceived handler | High | Low | Adds support for plugin sources | P0 |
| Doom of Mokhaiotl progression | Critical | High | Currently broken | P0 |
| Ground item scoping | High | Medium | Prevents misattribution | P1 |
| Pending encounter lifecycle | Medium | Medium | Improves delayed-reward handling | P1 |
| Script-based rewards | Medium | Medium | Supports newer bosses | P1 |
| Transaction ID system | Medium | Low | Improves dedup robustness | P2 |
| Account profile isolation | Low | Medium | Edge case protection | P2 |
| Inventory snapshot enhancement | Medium | Medium | Better item meta tracking | P2 |

---

## Implementation Plan

### Phase 1: Foundation (ServerNpcLoot + PluginLootReceived)

1. Add ServerNpcLoot event handler
2. Implement source precedence (ServerNpcLoot > NpcLootReceived > ground items)
3. Add PluginLootReceived event handler
4. Create deduplication for these new sources

### Phase 2: Progressive Encounters (Doom of Mokhaiotl)

1. Create DoomRunState class for run lifecycle tracking
2. Implement floor progression detection
3. Track claimed vs unclaimed rewards
4. Separate progression from loot events
5. Add comprehensive tests

### Phase 3: Reliability Improvements

1. Add scoped ground-item attribution
2. Implement explicit pending encounter tracking
3. Add script-based reward detection
4. Add transaction IDs to all loot records

### Phase 4: Edge Cases & Completeness

1. Add inventory snapshot enhancements
2. Improve account profile isolation
3. Add missing specialized encounters
4. Comprehensive testing of all paths

---

## Testing Strategy

### Unit Tests Required

1. ServerNpcLoot deduplication  
2. PluginLootReceived source handling
3. Doom run state transitions
4. Pending encounter timeout
5. Ground item scoping
6. Account isolation on switch
7. All specialized encounter scenarios

### Integration Tests Required

1. Full kill flow: NPC → loot appearance
2. Delayed reward flow: kill → widget → items
3. Chest-based flow: encounter → chest interaction → items
4. Progressive flow: Doom multiple floors
5. Death recovery suppression
6. Concurrent event ordering

### Manual Testing Required (not automatable)

1. Barrows chest reward capture
2. Gauntlet chest delayed opening  
3. CoX/ToB/ToA personal loot
4. Doom of Mokhaiotl floor progression
5. Clue casket opening
6. Skilling loot generation
7. PvP/Wilderness kills
8. Death/gravestone recovery
9. Account switching
10. World hopping

---

## Files to Modify/Create

### New Files

- `PendingLootEncounter.java` - Explicit pending encounter tracking
- `DoomRunState.java` - Doom of Mokhaiotl run state
- `ScriptBasedRewardDetector.java` - Script-based reward detection
- `LootTransactionId.java` - Transaction ID generation
- `GroundItemScopeValidator.java` - Scoped ground item attribution
- `LootSourceRegistry.java` - Centralized source definitions

### Modified Files  

- `RuneAlyticsPlugin.java` - Add ServerNpcLoot and PluginLootReceived handlers
- `LootTrackerManager.java` - Refactor to use new pending/transaction architecture
- `RewardSources.java` - Add missing widget/script IDs
- `DeathRecoveryGuard.java` - Enhance region/widget scoping
- `InventoryDiffGuard.java` - Add more widget groups if needed

### Test Files

- `ServerNpcLootHandlerTest.java`
- `PluginLootReceivedHandlerTest.java`  
- `DoomRunStateTest.java`
- `PendingLootEncounterTest.java`
- `GroundItemScopeValidatorTest.java`
- `LootDeduplicationTest.java`
- `SpecialEncounterTest.java`

---

## Success Criteria

All of the following must be true:

- [ ] ServerNpcLoot events are consumed when available
- [ ] PluginLootReceived events are consumed when available
- [ ] Ground item attribution has world/location/ownership scoping
- [ ] Doom of Mokhaiotl progression is tracked separately from loot
- [ ] Pending encounters have explicit lifecycle with timeouts
- [ ] Deduplication is robust across all loot paths
- [ ] Account isolation is maintained on profile switch
- [ ] Death recovery does not create false loot
- [ ] All 30+ special encounters are explicitly supported
- [ ] Tests cover all critical paths
- [ ] No regressions in existing functionality
- [ ] Code compiles and passes checkstyle
