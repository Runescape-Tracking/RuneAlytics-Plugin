# RuneAlytics Plugin: Log Configuration Specification

## Overview

This specification defines how the RuneAlytics plugin receives and applies server-provided logging configuration through the heartbeat endpoint. This enables selective enabling/disabling of log categories to reduce noise while keeping critical logs.

## Design Principles

1. **Default: All Enabled** - If the server doesn't provide log configuration, all categories log by default
2. **Graceful Degradation** - Missing log_config in heartbeat response doesn't break anything
3. **Per-Category Control** - Fine-grained control over which features log
4. **Zero Configuration** - Plugin works perfectly with no server changes; logging config is optional

## Server Heartbeat Response Format

The `/plugin/heartbeat` endpoint should return log configuration in this format:

```json
{
  "status": "ok",
  "players": [...],
  "log_config": {
    "loot_tracker": true,
    "xp_tracker": true,
    "matchmaking": false,
    "loot_sync": true,
    "api_client": true,
    "death_recovery": true,
    "live_map": false,
    "skill_economy": true,
    "heartbeat": false,
    "widget_loading": true,
    "loot_pending": true,
    "inventory_diff": false,
    "chat_messages": true
  }
}
```

### Response Fields

- `status` (string, required): Standard "ok" or "error"
- `players` (array, optional): Existing live map players array
- `log_config` (object, optional): Logging configuration object
  - All fields in `log_config` are optional booleans
  - Omitted categories keep their current state (default: true)
  - Missing entire `log_config` object: all logging enabled

## Log Categories

Each category controls logging for a specific feature/subsystem:

| Category | Key | Controls |
|----------|-----|----------|
| Loot Tracker | `loot_tracker` | Loot detection, kill recording, widget reading |
| XP Tracker | `xp_tracker` | XP gains, batch sending, experience calculations |
| Matchmaking | `matchmaking` | Player matchmaking, gear tracking, session data |
| Loot Sync | `loot_sync` | Server uploads, kill syncing, data merging |
| API Client | `api_client` | Network requests (excluding heartbeat) |
| Death Recovery | `death_recovery` | Death detection, loot loss handling, gravestone tracking |
| Live Map | `live_map` | Live map positioning, player visibility |
| Skill Economy | `skill_economy` | Skilling profit/loss calculations, supply tracking |
| Heartbeat | `heartbeat` | Periodic heartbeat requests (ALWAYS LOGGED) |
| Widget Loading | `widget_loading` | Widget detection, reward interface discovery |
| Loot Pending | `loot_pending` | Pending loot tracking (Mokhaiotl chests), claims, losses |
| Inventory Diff | `inventory_diff` | Inventory change detection, item tracking |
| Chat Messages | `chat_messages` | In-game chat parsing, KC detection, wave messages |

## Implementation Details

### Plugin-Side Parsing

The plugin parses the heartbeat response automatically:

```
1. Heartbeat succeeds with HTTP 200
2. Response body is parsed as JSON
3. log_config object is extracted (if present)
4. Each known category is checked:
   - If present and true/false: apply that setting
   - If missing: keep current state
5. Unknown categories are ignored
6. On parse error: log error, continue with current config
```

### Default State

```
On plugin startup:
- All 13 log categories are ENABLED
- No server response required for logging to work
- First heartbeat receives default (all enabled) state
- Server can override with log_config object
```

### State Persistence

- Log configuration is session-scoped (not persisted to disk)
- Resets to defaults on plugin restart
- Updated on each heartbeat response
- Player-specific (can differ per account)

## Usage Examples

### Scenario 1: Production Deployment (Minimal Logging)

Server response to reduce log volume:

```json
{
  "status": "ok",
  "log_config": {
    "loot_tracker": true,
    "xp_tracker": true,
    "loot_sync": true,
    "heartbeat": false,
    "api_client": false,
    "widget_loading": false,
    "inventory_diff": false,
    "death_recovery": false,
    "matchmaking": false,
    "live_map": false,
    "skill_economy": false,
    "chat_messages": false,
    "loot_pending": false
  }
}
```

Only loot, XP, and sync logs show. Heartbeat continues (always logged).

### Scenario 2: Debug Session (One Player)

Server response to enable all logs for a specific player:

```json
{
  "status": "ok",
  "log_config": {
    "heartbeat": false
  }
}
```

All categories enabled except heartbeat (reduces repetitive noise).

### Scenario 3: No Configuration (Default)

Server response without log_config:

```json
{
  "status": "ok",
  "players": [...]
}
```

Plugin continues with all logging enabled. Zero server changes needed.

### Scenario 4: Investigating Mokhaiotl Tracking Issues

Server enables only Mokhaiotl-related logs:

```json
{
  "status": "ok",
  "log_config": {
    "loot_pending": true,
    "loot_tracker": true,
    "chat_messages": true,
    "widget_loading": true,
    "loot_tracker": true,
    "loot_sync": true
  }
}
```

Only logs related to chest tracking, widget reading, and loot sync show.

## Client-Side Implementation

### LogConfiguration Class

Location: `src/main/java/com/runealytics/LogConfiguration.java`

Features:
- Parses heartbeat response JSON
- Maintains per-category enabled state
- Provides `isEnabled(LogCategory)` method
- Defaults to all enabled
- Gracefully handles missing/malformed config

### Usage in Code

```java
// Check if a category is enabled before logging
if (state.getLogConfiguration().isEnabled(LogConfiguration.LogCategory.LOOT_TRACKER))
{
    log.debug("Loot detail log message");
}

// For expensive operations, wrap entire block
if (state.getLogConfiguration().isEnabled(LogConfiguration.LogCategory.LOOT_SYNC))
{
    // Complex loot sync logging
    log.debug("Detailed sync info: {}", complexCalculation());
}
```

### Integration Points

1. **RunealyticsApiClient.java**
   - Heartbeat response handler calls `logConfiguration.updateFromHeartbeatResponse()`
   - Continues to work if log_config is missing

2. **Heartbeat Logging**
   - **ALWAYS** logged regardless of config (debugging critical)
   - No `isEnabled()` check on heartbeat logs

3. **All Other Categories**
   - Should wrap non-critical debug logs with `isEnabled()` check
   - Info and error logs are unaffected (still always logged)

## Migration Path

### Phase 1 (Current)
- Plugin supports log_config but server doesn't send it
- All logging enabled by default
- Plugin works identically to before

### Phase 2 (Optional)
- Server sends log_config in heartbeat response
- Players experiencing log spam get relief
- No client-side code changes needed

### Phase 3 (Future)
- Develop player-facing UI for log configuration
- Players can enable/disable from plugin panel
- Client pushes preference back to server

## Future Enhancements

1. **Persistent Configuration**: Store user's log preferences in local config
2. **UI Control**: Add checkbox panel for toggling categories
3. **Log Levels**: Support TRACE/DEBUG/INFO/WARN/ERROR levels
4. **Pattern Matching**: Enable logs only for specific bosses (e.g., only Mokhaiotl)
5. **Metrics**: Track how many logs are suppressed by category
6. **Remote Analysis**: Server can request log dumps for specific categories

## Troubleshooting

### Logs not appearing for category X

1. Check: `log_config.{category}` is true or missing
2. Check: Heartbeat response is successful (HTTP 200)
3. Check: Log message has `isEnabled()` check wrapping it
4. Check: Log message uses debug level (not info/warn/error)

### Log configuration not updating

1. Ensure heartbeat is completing successfully
2. Check heartbeat response contains valid JSON
3. Check `log_config` object is present (if sending config)
4. Monitor startup: first heartbeat sets configuration

### Too many heartbeat logs

Server should set `"heartbeat": false` to suppress. Note: Heartbeat start/end still log regardless (critical for debugging).

## Examples for Server Implementation

### Python (FastAPI)

```python
@router.post("/plugin/heartbeat")
async def plugin_heartbeat(request: HeartbeatRequest):
    # ... existing heartbeat logic ...
    
    # Add log configuration based on context
    log_config = {
        "loot_tracker": True,
        "xp_tracker": True,
        "loot_sync": True,
        "heartbeat": False,  # Reduce noise
        "api_client": False,
        "widget_loading": False,
        "inventory_diff": False,
        "death_recovery": debug_mode,
        "matchmaking": False,
        "live_map": False,
        "skill_economy": True,
        "chat_messages": debug_mode,
        "loot_pending": True,
    }
    
    return {
        "status": "ok",
        "players": visible_players,
        "log_config": log_config
    }
```

### JavaScript (Express)

```javascript
app.post('/plugin/heartbeat', (req, res) => {
  // ... existing heartbeat logic ...
  
  const logConfig = {
    loot_tracker: true,
    xp_tracker: true,
    loot_sync: true,
    heartbeat: false,
    api_client: false,
    widget_loading: false,
    inventory_diff: false,
    // ... others
  };
  
  res.json({
    status: 'ok',
    players: visiblePlayers,
    log_config: logConfig
  });
});
```

## Testing

### Test 1: Default Behavior (No Config)
- Heartbeat response omits log_config
- Verify all logs still appear
- Verify no errors in console

### Test 2: Partial Config
- Heartbeat response has only 3 categories in log_config
- Verify specified categories change
- Verify unspecified categories remain enabled

### Test 3: All Disabled
- Set all categories to false
- Verify almost no debug logs appear
- Verify info/warn/error still logged

### Test 4: Parse Errors
- Send malformed JSON in log_config
- Verify plugin logs error gracefully
- Verify logging continues with current state

### Test 5: Account Switching
- Login as account A (receives config)
- Logout and login as account B
- Verify config resets to defaults
- Verify account B doesn't inherit account A's config
