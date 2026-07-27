# Clan Information Tracking - Implementation Guide

## Overview

The RuneAlytics plugin now includes automatic clan membership tracking. This guide explains:
- How to integrate clan tracking into the plugin
- What data is collected and when
- How to integrate with the heartbeat system
- Performance considerations

---

## Architecture

### Core Components

#### 1. `ClanMember.java`
Represents a single clan member with lightweight data:
- Username
- Rank (leader, coordinator, member, etc.)
- Join/last-seen timestamps
- Whether they use RuneAlytics plugin

#### 2. `ClanInfo.java`
Represents a player's current clan:
- Clan name & tag
- Player's rank in clan
- Member roster (thread-safe `ConcurrentHashMap`)
- Dirty flag (tracks if needs server sync)
- Timestamps

#### 3. `ClanManager.java`
Central manager for clan state:
- Singleton service (injected via RuneLite DI)
- Detects when player joins/leaves clan
- Tracks member updates
- Notifies listeners (e.g., UI updates)
- Safe for concurrent access

#### 4. API Extensions in `RunealyticsApiClient.java`
Two new async methods:
- `syncClanInfo(ClanInfo clan)` → POST `/plugin/clan`
- `syncClanMembers(ClanInfo clan)` → POST `/plugin/clan/members`

---

## How to Integrate into RuneAlyticsPlugin.java

### Step 1: Inject ClanManager

```java
@Inject
private ClanManager clanManager;
```

### Step 2: Listen for Clan Chat Events

Hook into RuneLite's clan chat events:

```java
@Subscribe
public void onClanChat(ChatMessage event)
{
    if (event.getType() != ChatMessageType.CLAN)
        return;
    
    // Extract username from message
    String username = event.getName();
    if (username != null && !username.isEmpty())
    {
        clanManager.recordMember(username, false); // Will check if tracked later
    }
}
```

### Step 3: Detect Clan Join/Leave

Hook into player state change:

```java
@Subscribe
public void onGameStateChanged(GameStateChanged event)
{
    if (event.getGameState() == GameState.LOGGED_OUT)
    {
        clanManager.onClanLeft();
        return;
    }
    
    if (event.getGameState() != GameState.LOGGED_IN)
        return;
    
    // Check if player is in a clan (requires reading game state)
    Player localPlayer = client.getLocalPlayer();
    if (localPlayer != null)
    {
        String clanName = getClanNameFromWidget(); // Helper method
        if (clanName != null && !clanName.isEmpty())
        {
            String clanTag = getClanTagFromWidget();   // Helper method
            clanManager.onClanJoined(clanName, clanTag);
        }
    }
}
```

### Step 4: Hook Member List Updates

When player opens clan member list UI:

```java
@Subscribe
public void onWidgetLoaded(WidgetLoaded event)
{
    // Clan member list widget ID (need to identify from RuneLite)
    if (event.getGroupId() == CLAN_MEMBER_LIST_WIDGET_ID)
    {
        List<String> members = extractMembersFromWidget(client);
        clanManager.updateMemberList(members);
    }
}
```

### Step 5: Integrate with Heartbeat

Modify the heartbeat call to include clan data (if dirty):

```java
private void sendHeartbeat()
{
    ClanInfo clan = clanManager.getCurrentClan();
    
    // ... existing heartbeat code ...
    
    // If clan data is dirty, sync it separately (before heartbeat)
    if (clan != null && clanManager.hasDirtyData())
    {
        apiClient.syncClanInfo(clan);
        apiClient.syncClanMembers(clan);
        clanManager.markSynced();
    }
    
    // ... existing heartbeat code ...
}
```

### Step 6: Reset on Logout

In your logout handler:

```java
private void onLogout()
{
    // ... existing cleanup ...
    clanManager.reset();
}
```

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                   RuneLite Game Client                       │
│  (Detects clan chat, member list widget, player state)      │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
         ┌───────────────────────┐
         │   ClanManager         │
         │  (Singleton Service)  │
         │                       │
         │  - Tracks clan state  │
         │  - Validates data     │
         │  - Manages members    │
         │  - Dirty flagging     │
         └───────────┬───────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
   ┌─────────┐ ┌──────────┐ ┌──────────────┐
   │ ClanInfo│ │ClanMember│ │UI Listeners  │
   │ (state) │ │(roster)  │ │(update UI)   │
   └────┬────┘ └──────────┘ └──────────────┘
        │
        ▼
┌──────────────────────────────────────┐
│  RunealyticsApiClient                │
│  (Async network calls)               │
├──────────────────────────────────────┤
│  syncClanInfo()  →  POST /clan       │
│  syncClanMembers() → POST /clan/... │
└───────────┬──────────────────────────┘
            │
            ▼
    ┌───────────────────┐
    │  RuneAlytics API  │
    │  (HTTPS)          │
    └───────────────────┘
            │
            ▼
    ┌───────────────────┐
    │  Database         │
    │  (clan_members)   │
    └───────────────────┘
```

---

## Performance Considerations

### Why This Won't Bog Down the Client

1. **Asynchronous**: All network calls use `OkHttpClient.newCall().enqueue()` (non-blocking)
2. **Fire-and-Forget**: Network failures are silent; no retries or blocking waits
3. **Batching**: Clan data sent only when it changes (dirty flag), not on every tick
4. **Lightweight Objects**: `ClanMember` and `ClanInfo` are small, immutable snapshots
5. **Thread-Safe**: Uses `ConcurrentHashMap` and volatile fields; no locks on game thread
6. **Infrequent Events**: Clan joins/leaves are rare; member list updates are user-initiated

### Memory Usage

- Per player: ~1KB for clan metadata
- Per member in roster: ~100 bytes
- 100-member clan: ~11KB total
- No memory leaks: old clan data cleared on logout

### Network Usage

- Clan info: ~500 bytes per sync (only when dirty)
- Member list: ~100 bytes per member, sent ~1x per 5 minutes
- Typical large clan (500 members): ~50KB per member list sync
- Not sent on every heartbeat; only when data changes

### Example Bandwidth

```
Typical Player in 50-member Clan:
  - Clan info sync: ~500B / 5 min = ~0.1 B/s
  - Member list sync: ~5KB / 5 min = ~1 B/s
  - Total overhead: ~1.1 B/s (essentially negligible)
  
For 1000 RuneAlytics users with average 100-member clans:
  - Total clan data bandwidth: ~110 KB/min = ~1.8 KB/s
  - (Compare to heartbeat: ~500 users × 500B × 1/30s = ~8 KB/s)
  - Clan data is <25% of heartbeat overhead
```

---

## Implementation Checklist

- [ ] Add `ClanMember.java` ✅
- [ ] Add `ClanInfo.java` ✅
- [ ] Add `ClanManager.java` ✅
- [ ] Add sync methods to `RunealyticsApiClient.java` ✅
- [ ] Inject `ClanManager` into `RuneAlyticsPlugin`
- [ ] Hook clan chat events (`onClanChat`)
- [ ] Hook game state changes (`onGameStateChanged`)
- [ ] Hook member list widget (`onWidgetLoaded`)
- [ ] Integrate with heartbeat loop
- [ ] Test clan join detection
- [ ] Test member list parsing
- [ ] Test API calls (check logs for `/plugin/clan` POSTs)
- [ ] Backend: Create endpoints & database schema
- [ ] Backend: Test 200 responses
- [ ] Test UI: Verify clan appears on website dashboard
- [ ] Load testing: Verify no lag with large clans (500+ members)

---

## Testing Guide

### Manual Testing

1. **Join a clan in-game**
   - Plugin should call `onClanJoined()`
   - Check logs: `[Clan] Joined clan: ...`

2. **Open member list**
   - Plugin should call `updateMemberList()`
   - Check logs: `[Clan] Member list updated: X members`

3. **Check API calls**
   - Look for logs: `[Clan] POST /plugin/clan`
   - Verify payload includes clan name, member count
   - Verify HTTP 200 response

4. **Verify no lag**
   - Game should remain smooth
   - No stutters during API calls
   - No logs like "Network failure" unless network is broken

5. **Leave clan**
   - Plugin should call `onClanLeft()`
   - Check logs: `[Clan] Left clan: ...`

### Automated Tests

```java
@Test
public void testClanJoin()
{
    ClanManager manager = new ClanManager();
    manager.onClanJoined("Legends", "<LEG>");
    
    assertNotNull(manager.getCurrentClan());
    assertEquals("Legends", manager.getCurrentClan().getClanName());
    assertEquals("<LEG>", manager.getCurrentClan().getClanTag());
}

@Test
public void testMemberAdded()
{
    ClanManager manager = new ClanManager();
    manager.onClanJoined("Legends", "<LEG>");
    manager.recordMember("Player1", true);
    
    assertTrue(manager.getCurrentClan().hasMember("Player1"));
    assertEquals(1, manager.getMemberCount());
}

@Test
public void testDirtyFlag()
{
    ClanManager manager = new ClanManager();
    manager.onClanJoined("Legends", "<LEG>");
    
    assertTrue(manager.hasDirtyData());
    manager.markSynced();
    assertFalse(manager.hasDirtyData());
    
    manager.recordMember("Player1", true);
    assertTrue(manager.hasDirtyData());
}
```

---

## Debugging

### Enable Debug Logging

The plugin logs clan events at INFO and DEBUG levels:

```
[Clan] Joined clan: Legends tag=<LEG>
[Clan] Member recorded: Player1 tracked=true
[Clan] Member list updated: 45 members
[Clan] Marked synced: Legends
[Clan] POST /plugin/clan | clan=Legends members=45 payload={...}
[Clan] OK HTTP 200 — {}
```

### If Clan Data Not Syncing

1. Check: Is player in a clan? (Open clan menu in-game)
2. Check: Are clan events firing? (Look for `[Clan] Joined clan:` in logs)
3. Check: Is heartbeat running? (Look for `[Heartbeat] POST` in logs)
4. Check: Is clan data dirty? (Look for `[Clan] POST /plugin/clan` in logs)
5. Check: API token valid? (Check `[Clan] Skipping — no verification token`)
6. Check: Network connectivity? (Look for `[Clan] Network failure:`)

### If Member List Not Updating

1. Check: Widget ID correct? (RuneLite widget IDs change with updates)
2. Check: Member list parsing logic correct? (Extract names from widget text)
3. Check: Events firing? (Look for `onWidgetLoaded` logs)

---

## Future Improvements

- [ ] Auto-detect clan from player sidebar (no widget parsing needed)
- [ ] Track clan rank changes
- [ ] Clan analytics dashboard (growth, member retention)
- [ ] Clan pages on website
- [ ] Export clan roster as CSV
- [ ] Clan comparison tool

---

## Questions?

If clan data is not syncing or you're unsure about implementation:

1. Check the logs for `[Clan]` prefixed messages
2. Verify the backend is responding with `200 OK`
3. Verify database schema matches specification
4. Check `RunealyticsApiClient.java` for the exact payload structure

See `CLAN_BACKEND_SPECIFICATION.md` for backend implementation details.

