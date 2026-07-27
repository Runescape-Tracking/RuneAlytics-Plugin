# RuneAlytics Clan Tracking Backend Specification

## Overview

The RuneLite plugin now automatically detects and tracks clan membership. The backend must accept clan data from the plugin and store it efficiently without performance degradation.

---

## API Endpoints

### 1. `POST /api/plugin/clan`

**Purpose**: Receive clan info updates (clan name, tag, player rank, member count)

**Headers**:
```
Authorization: Bearer {verification_token}
Content-Type: application/json
Accept: application/json
```

**Request Body**:
```json
{
  "username": "PlayerName",
  "clan": {
    "clan_name": "Legends",
    "clan_tag": "<LEG>",
    "clan_id": null,
    "player_rank": "coordinator",
    "member_count": 45,
    "joined_at": 1690300000,
    "last_update": 1690306000
  },
  "timestamp": 1690306000
}
```

**Field Explanations**:
- `username`: Verified OSRS username of the player (matches auth token)
- `clan_name`: Name of the clan (e.g., "Legends")
- `clan_tag`: Clan tag with brackets (e.g., "<LEG>"). Can be empty if not available.
- `clan_id`: RuneScape's internal clan ID (optional, may be null if not obtainable)
- `player_rank`: Player's rank in the clan (e.g., "leader", "coordinator", "member")
- `member_count`: Current member count in the clan
- `joined_at`: Unix timestamp when player joined the clan
- `last_update`: Unix timestamp of when this clan info was last updated in the plugin
- `timestamp`: Unix timestamp of when this POST was made

**Response**:
```json
{
  "status": "ok"
}
```

**HTTP Status Codes**:
- `200 OK`: Clan info stored successfully
- `401 Unauthorized`: Invalid or expired token
- `400 Bad Request`: Missing required fields (username, clan_name)
- `429 Too Many Requests`: Rate limit exceeded

**Notes**:
- Called periodically in the heartbeat (~30s interval) only if clan data has changed
- Low frequency: sent only when `last_update` differs from previous sync time
- Safe for high-volume: clan joins/leaves are rare, membership is stable

---

### 2. `POST /api/plugin/clan/members`

**Purpose**: Receive the full member list for a clan

**Headers**:
```
Authorization: Bearer {verification_token}
Content-Type: application/json
Accept: application/json
```

**Request Body**:
```json
{
  "username": "PlayerName",
  "clan_name": "Legends",
  "member_count": 45,
  "members": [
    {
      "username": "Legit Player",
      "rank": "leader",
      "joined_at": 1688000000,
      "last_seen": 1690305999,
      "is_tracked": true
    },
    {
      "username": "Another Member",
      "rank": "member",
      "joined_at": 1689000000,
      "last_seen": 1690300000,
      "is_tracked": false
    }
  ],
  "timestamp": 1690306000
}
```

**Field Explanations** (per member):
- `username`: Member's OSRS username
- `rank`: Member's rank in the clan (e.g., "leader", "coordinator", "member")
- `joined_at`: Unix timestamp when this member joined (best estimate)
- `last_seen`: Unix timestamp of when the plugin last saw this member active
- `is_tracked`: Boolean — whether this player is known to use RuneAlytics plugin
- `member_count`: Total member count (for validation)

**Response**:
```json
{
  "status": "ok",
  "members_stored": 45
}
```

**HTTP Status Codes**:
- `200 OK`: Member list stored successfully
- `401 Unauthorized`: Invalid or expired token
- `400 Bad Request`: Missing required fields (username, clan_name, members array)
- `413 Payload Too Large`: Member list exceeds payload limit (recommend max ~5000 members per request)
- `429 Too Many Requests`: Rate limit exceeded

**Notes**:
- Sent separately from clan info to avoid oversizing heartbeat payloads
- Called when:
  - Player opens clan member list UI (full refresh)
  - Member joins/leaves clan (delta update, preferred)
  - Periodically if clan data is dirty (fallback, ~5 min)
- Member list can be up to several hundred for large clans; payload should support ~100KB+
- Plugin pre-validates before sending: only sends if member count > 0
- `is_tracked` flag helps identify which clan members are also RuneAlytics users

---

## Data Storage & Database Schema

### Suggested Table: `clan_memberships`

```sql
CREATE TABLE clan_memberships (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    player_id BIGINT NOT NULL,
    clan_name VARCHAR(255) NOT NULL,
    clan_tag VARCHAR(50),
    clan_id INT,
    player_rank VARCHAR(50),
    member_count INT,
    joined_at BIGINT NOT NULL,           -- Unix timestamp
    last_update BIGINT NOT NULL,          -- Unix timestamp
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (player_id) REFERENCES osrs_players(id) ON DELETE CASCADE,
    UNIQUE KEY unique_player_clan (player_id, clan_name),
    INDEX idx_clan_name (clan_name),
    INDEX idx_updated_at (updated_at)
);
```

### Suggested Table: `clan_members`

```sql
CREATE TABLE clan_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clan_id BIGINT NOT NULL,
    member_username VARCHAR(255) NOT NULL,
    member_rank VARCHAR(50),
    joined_at BIGINT,                     -- Unix timestamp
    last_seen BIGINT,                     -- Unix timestamp
    is_tracked BOOLEAN DEFAULT FALSE,     -- Plugin user?
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (clan_id) REFERENCES clan_memberships(id) ON DELETE CASCADE,
    UNIQUE KEY unique_member_per_clan (clan_id, member_username),
    INDEX idx_member_username (member_username),
    INDEX idx_is_tracked (is_tracked),
    INDEX idx_last_seen (last_seen)
);
```

**Rationale**:
- Separates clan membership (player → clan) from member roster (all members in a clan)
- `clan_memberships` tracks each player's current clan
- `clan_members` is the roster for a given clan (can be large)
- Use `is_tracked` to filter for RuneAlytics plugin users when analyzing clan activity
- Timestamps allow for trend analysis (member growth, retention, etc.)

---

## Client-Side Detection (Plugin)

The plugin detects clan membership through:

1. **Clan Chat Widget**: When player joins clan chat, the RuneLite client fires events
2. **Member List UI**: When player opens member list widget, names are extracted
3. **Player Name Tracking**: When player is seen in clan chat, username is recorded

### Plugin Limitations

- Plugin cannot always determine exact join time for members (uses "first seen" instead)
- Plugin cannot access hidden clan settings or clan description (API restriction)
- `clan_id` may be unavailable (RuneScape doesn't expose it directly)
- Member list is approximate; some inactive members may be stale

---

## Heartbeat Integration

Clan info is sent as an **optional field in the heartbeat** only when data is dirty:

```json
{
  "username": "PlayerName",
  // ... other heartbeat fields ...
  "clan": {
    "clan_name": "Legends",
    "clan_tag": "<LEG>",
    "player_rank": "coordinator",
    "member_count": 45,
    "joined_at": 1690300000,
    "last_update": 1690306000
  }
}
```

**Heartbeat Frequency**: ~30 seconds
**Clan Field Sent**: Only if player is in a clan AND clan data has changed since last sync

---

## Efficiency & Rate Limiting

### Plugin Behavior (Not Bogging Down the Client)

1. **Batching**: Clan data is sent infrequently (on membership changes, not every tick)
2. **Dirty Flagging**: Only resends if `last_update > last_sync_at`
3. **Async Network**: All HTTP calls are non-blocking (async/enqueue, no client thread blocking)
4. **Memory Efficient**: Member list stored as `ConcurrentHashMap` (thread-safe, no locks)
5. **Payload Size**: Typical clan info ~500 bytes, member list ~100 bytes per member

### Suggested Backend Rate Limits

- `/plugin/clan`: 1 request per player per 10 seconds (prevent spam)
- `/plugin/clan/members`: 1 request per player per 60 seconds (larger payload)
- Return `429 Too Many Requests` if exceeded; plugin will retry on next sync

### Example Request Frequency

- **Best case** (stable clan): 1 POST every 5 minutes (heartbeat), minimal bandwidth
- **Worst case** (frequently changing clans): 1 POST per join, 1 POST per leave (~rare events)
- **Member list**: ~1 POST when list refreshes, then silent unless changes detected

---

## Error Handling

### Plugin Behavior

All network failures are **silent**:
- Network timeout → logged at debug level, request not retried (fire-and-forget)
- HTTP 400/401/429 → logged, payload may be dropped or queued for retry (optional)
- HTTP 500 → logged, request not retried (fire-and-forget)

**Plugin does NOT**:
- Block game thread on network errors
- Show error messages to user for clan sync failures
- Retry indefinitely

**Backend Should**:
- Return 4xx for fixable errors (bad data, auth, rate limit)
- Return 5xx for server errors (temporary)
- Log both success and failure for debugging

---

## Testing Checklist

- [ ] Endpoint accepts POST with valid clan info
- [ ] Endpoint rejects missing `username` or `clan_name` with 400
- [ ] Endpoint rejects invalid auth token with 401
- [ ] Endpoint returns success (200) and stores data
- [ ] Member list endpoint accepts up to 500+ members
- [ ] Member list endpoint rejects payloads >5MB with 413
- [ ] Rate limiting returns 429 when exceeded
- [ ] Clan data persists and can be queried by username
- [ ] Player can see their clan on the website dashboard
- [ ] Member list is queryable on the website (clan page)
- [ ] `is_tracked` filter works (shows only RuneAlytics plugin users)
- [ ] Database indexes are efficient for large clan tables
- [ ] No SQL injection vulnerabilities in username/clan_name fields
- [ ] Timestamps are correct (UTC, Unix format)

---

## Future Enhancements

1. **Clan Pages**: Display clan roster on website (public/private)
2. **Member Activity Feed**: Show tracked members' XP/loot within clan context
3. **Clan Comparison**: Compare two clans' average wealth, XP, etc.
4. **Alerts**: Notify clan leaders when new tracked members join
5. **Clan Admin Panel**: Manage clan on website, export member list
6. **Stats Dashboard**: Clan wealth, total XP, member retention
7. **Clan Wars Tracking**: Track competitive clan events

---

## Questions for Clarification

1. Should the backend auto-create clan pages on first member post, or require manual creation?
2. Should member list updates replace the previous list or do delta merging?
3. How long should member records persist after a player leaves?
4. Should `is_tracked` flag be exposed on public clan pages?
5. Do you want clan leadership integration (leaders can manage on website)?

---

## Code Examples

### Example: Storing Clan Info (Backend Pseudocode)

```python
@app.post("/api/plugin/clan")
def store_clan_info(request: ClanInfoPayload, user: AuthUser):
    # Verify user
    if not user.verified:
        return {"error": "unauthorized"}, 401
    
    # Validate payload
    if not request.username or not request.clan.clan_name:
        return {"error": "missing_fields"}, 400
    
    # Rate limit check
    if is_rate_limited(user.id, "clan_info", limit=1, window=10):
        return {"error": "too_many_requests"}, 429
    
    # Store in database
    clan = ClanMembership.upsert(
        player_id=user.id,
        clan_name=request.clan.clan_name,
        clan_tag=request.clan.clan_tag,
        player_rank=request.clan.player_rank,
        member_count=request.clan.member_count,
        joined_at=request.clan.joined_at,
        last_update=request.clan.last_update
    )
    
    return {"status": "ok"}, 200
```

### Example: Storing Member List (Backend Pseudocode)

```python
@app.post("/api/plugin/clan/members")
def store_clan_members(request: ClanMembersPayload, user: AuthUser):
    if not user.verified:
        return {"error": "unauthorized"}, 401
    
    if not request.members:
        return {"error": "missing_members"}, 400
    
    if len(request.members) > 5000:
        return {"error": "too_many_members"}, 413
    
    # Rate limit
    if is_rate_limited(user.id, "clan_members", limit=1, window=60):
        return {"error": "too_many_requests"}, 429
    
    # Get or create clan record
    clan = ClanMembership.get_by_name(request.clan_name, user.id)
    if not clan:
        return {"error": "clan_not_found"}, 404
    
    # Upsert members (replace or merge, depending on strategy)
    for member_data in request.members:
        ClanMember.upsert(
            clan_id=clan.id,
            member_username=member_data.username,
            member_rank=member_data.rank,
            joined_at=member_data.joined_at,
            last_seen=member_data.last_seen,
            is_tracked=member_data.is_tracked
        )
    
    return {"status": "ok", "members_stored": len(request.members)}, 200
```

---

## Summary

| Aspect | Details |
|--------|---------|
| **Endpoints** | `POST /api/plugin/clan`, `POST /api/plugin/clan/members` |
| **Frequency** | ~30s heartbeat (clan info only if dirty), member list on UI refresh or periodically |
| **Payload Size** | Clan info: ~500B, Members: ~100B each (100KB for 1000 members) |
| **Rate Limit** | Recommend 1 req/10s for clan info, 1 req/60s for members |
| **Auth** | Bearer token (same as heartbeat/XP sync) |
| **Performance** | Async/non-blocking, fire-and-forget, no client thread impact |
| **Data Retention** | Indefinite (for historical clan membership tracking) |

