# RuneAlytics XP Tracker — Website Integration Spec

This document describes how the RuneAlytics website should handle the data the
new **XP Tracker** sends from the RuneLite plugin, so you can store it and build
per-session and long-term XP analytics.

Give this whole file to whoever builds the site side as the implementation
prompt.

---

## 1. What the plugin already sends today (unchanged)

- **`POST /api/xp/batch`** — incremental XP gained per skill in a rolling 30s
  window. Authoritative for "total XP over time". Keep as-is.
- **`POST /api/plugin/heartbeat`** — may contain an `xp_preview` object (XP
  gained so far in the open 30s window). **Non-authoritative** — never add it to
  a player's totals; it is only a live preview.

Do **not** change how those are handled.

---

## 2. New endpoint: `POST /api/xp/session`

The plugin now sends a **whole-session snapshot** so you can track XP/hr and
per-session XP gained, and give the user good analysis.

### When it is sent

| Trigger | Frequency |
|---|---|
| Auto-sync while playing | every 60s |
| User clicks **Sync Session** | on demand |
| User **resets/clears** the session | once, right before the session is cleared |
| Player **logs out** | once |
| Plugin/client **shuts down** (window closed) | once (best-effort) |

Because the same session is posted repeatedly (the 60s auto-sync) and again at
the end, **the end-of-session post is the authoritative final snapshot** — it
carries the final XP/hr and duration for the session.

### Auth

- `Authorization: Bearer <verification_token>` header.
- Resolve the token to a user server-side. If the token is invalid, or the
  `username` in the body does not match the token's account, **reject** (the
  plugin only ever sends the currently logged-in, verified account).

### Request body

```json
{
  "username": "genifer",
  "profile_id": "rsprofile--abc123",
  "game_mode": "regular",
  "account_type": "normal",
  "session_start": 1751560320,
  "session_duration": 5077,
  "total_xp_gained": 12450000,
  "timestamp": 1751565397,
  "skills": [
    {
      "skill": "magic",
      "xp_gained": 2340000,
      "xp_per_hour": 1660000,
      "level": 94,
      "current_xp": 6512000
    },
    {
      "skill": "ranged",
      "xp_gained": 1770000,
      "xp_per_hour": 1250000,
      "level": 92,
      "current_xp": 5900000
    }
  ]
}
```

### Field reference

| Field | Type | Meaning |
|---|---|---|
| `username` | string | Normalized (lowercase, single-spaced) RuneScape name. Scope everything to this account. |
| `profile_id` | string \| absent | RuneLite rsprofile/account id. Optional. Use to distinguish alts that share a display name if you ever need to. |
| `game_mode` | string | `regular`, `ironman`, `leagues`, `deadman`, `fresh_start`, `grid_master`. |
| `account_type` | string | `normal`, `ironman`, `hardcore_ironman`, `ultimate_ironman`, `group_ironman`, `hardcore_group_ironman`. |
| `session_start` | int (epoch **seconds**) | When the session began. **Use as the session key** together with the user. |
| `session_duration` | int (**seconds**) | **Active** session time — already excludes logged-out time (and AFK gaps when the user enabled that). Use this, not `timestamp - session_start`, for rate math. |
| `total_xp_gained` | int | Total XP gained across all skills this session (includes skills the user hid in the UI). |
| `timestamp` | int (epoch **seconds**) | When this snapshot was produced. |
| `skills[]` | array | One entry per skill that gained XP this session. |
| `skills[].skill` | string | Lowercase skill name (`attack`, `woodcutting`, …). |
| `skills[].xp_gained` | int | XP gained in this skill this session. |
| `skills[].xp_per_hour` | int | Plugin-computed XP/hr for this skill (based on active time). Store it, but you can also recompute — see below. |
| `skills[].level` | int | Current level (1–99). |
| `skills[].current_xp` | int | Current total XP in the skill. |

---

## 3. How to store it (idempotent upsert)

**The session is identified by `(user_id, session_start)`.** Repeated posts for
the same session must **update the same record**, not create duplicates.

Suggested schema:

```sql
CREATE TABLE xp_sessions (
  id             BIGSERIAL PRIMARY KEY,
  user_id        BIGINT NOT NULL REFERENCES users(id),
  profile_id     TEXT NULL,
  game_mode      TEXT NOT NULL,
  account_type   TEXT NOT NULL,
  session_start  TIMESTAMPTZ NOT NULL,
  duration_sec   INT NOT NULL,
  total_xp       BIGINT NOT NULL,
  last_update    TIMESTAMPTZ NOT NULL,
  ended          BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (user_id, session_start)
);

CREATE TABLE xp_session_skills (
  session_id   BIGINT NOT NULL REFERENCES xp_sessions(id) ON DELETE CASCADE,
  skill        TEXT NOT NULL,
  xp_gained    BIGINT NOT NULL,
  xp_per_hour  BIGINT NOT NULL,
  level        INT NOT NULL,
  current_xp   BIGINT NOT NULL,
  PRIMARY KEY (session_id, skill)
);
```

Upsert logic per request:

1. Resolve `user_id` from the bearer token; reject on mismatch with `username`.
2. `UPSERT xp_sessions` on `(user_id, session_start)`:
   - Always take the **largest** `total_xp` / `duration_sec` seen (snapshots are
     monotonically increasing within a session; guard against out-of-order posts).
   - Set `last_update = to_timestamp(timestamp)`.
3. For each `skills[]` entry, `UPSERT xp_session_skills` on `(session_id, skill)`
   with the latest values.
4. Optionally mark `ended = true` when you receive the logout/shutdown/reset
   post. (The plugin doesn't flag which post is the last one, so treat "no
   further updates for N minutes" as ended if you need an explicit end state.)

> **Idempotency is essential:** the 60s auto-sync means you'll get ~1 post/minute
> for a long session, all with the same `session_start`. Without the unique key
> you'd store dozens of duplicate rows.

---

## 4. Recomputing / validating XP/hr

The plugin computes `xp_per_hour` from **active** time (logged-out time and, if
the user enabled it, AFK gaps are excluded). You can trust `session_duration`
for the overall rate:

```
overall_xp_per_hour ≈ total_xp_gained / (session_duration / 3600)
```

Per skill, prefer the plugin's `skills[].xp_per_hour` (it knows each skill's own
active window). If you recompute from `xp_gained / duration`, note that a skill
trained for only part of the session will look slower than it actually was —
that's why the plugin sends a per-skill rate.

---

## 5. Analytics you can now build

With sessions + per-skill breakdown you can offer:

- **Session history**: list of sessions with duration, total XP, top skill,
  overall XP/hr.
- **XP/hr leaderboards per skill / method** (join with `game_mode` /
  `account_type` for fair comparisons).
- **Best/average XP/hr per skill** for a user, and personal bests.
- **Daily / weekly XP** (sum `total_xp` by day; the plugin also tracks a local
  "today" figure, but the server is the source of truth across devices).
- **Trends over time**: plot a user's XP/hr for a skill across sessions.
- **Time-to-goal estimates**: `remaining_xp / recent_xp_per_hour`.

---

## 6. Rules / gotchas

- **Scope strictly to `username`** (the token's account). Never merge in other
  local RuneLite profiles — the plugin never sends them.
- **Hidden skills still count**: a user can hide a skill from the plugin UI, but
  its XP is still in `total_xp_gained` and in `skills[]`. Don't special-case.
- **Seconds, not milliseconds**: `session_start`, `session_duration`, and
  `timestamp` are all in seconds.
- **`session_duration` is active time**, already excluding logged-out time — do
  not try to derive it from wall-clock timestamps.
- **Don't double count** with `/api/xp/batch`: batch is incremental total-XP;
  session is a self-contained snapshot. Keep them in separate tables/metrics;
  never add `xp_per_hour` or session `xp_gained` onto a player's lifetime XP.
- Respond `2xx` on success. On failure the plugin logs at debug and shows a
  small "Sync failed" badge; it never retries aggressively, so transient `5xx`
  is fine.

---

## 7. Why you may be seeing `404 The route api/xp/session could not be found`

The plugin already POSTs to `https://runealytics.com/api/xp/session`, but that
route isn't registered on the site yet — so Laravel returns 404. The plugin
handles this gracefully (debug log + "Sync failed" badge, no crash). To start
receiving data, register the route below. **It is a server-side change only —
no plugin change is required.**

Use the **same auth** you already use for `POST /api/xp/batch` (the plugin sends
the identical `Authorization: Bearer <verification_token>` header on both).

### routes/api.php

```php
use App\Http\Controllers\Api\XpSessionController;

// Mirror whatever middleware/group your existing /xp/batch route uses.
Route::post('/xp/session', [XpSessionController::class, 'store']);
```

### Migration

```php
Schema::create('xp_sessions', function (Blueprint $t) {
    $t->id();
    $t->foreignId('user_id')->constrained()->cascadeOnDelete();
    $t->string('profile_id')->nullable();
    $t->string('game_mode')->default('regular');
    $t->string('account_type')->default('normal');
    $t->unsignedBigInteger('session_start');   // epoch seconds (session key)
    $t->unsignedInteger('duration_sec')->default(0);
    $t->unsignedBigInteger('total_xp')->default(0);
    $t->unsignedBigInteger('last_update');      // epoch seconds
    $t->timestamps();
    $t->unique(['user_id', 'session_start']);   // <- makes repeated posts idempotent
});

Schema::create('xp_session_skills', function (Blueprint $t) {
    $t->foreignId('xp_session_id')->constrained()->cascadeOnDelete();
    $t->string('skill');
    $t->unsignedBigInteger('xp_gained')->default(0);
    $t->unsignedBigInteger('xp_per_hour')->default(0);
    $t->unsignedInteger('level')->default(1);
    $t->unsignedBigInteger('current_xp')->default(0);
    $t->primary(['xp_session_id', 'skill']);
});
```

### Controller (app/Http/Controllers/Api/XpSessionController.php)

```php
namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\XpSession;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class XpSessionController extends Controller
{
    public function store(Request $request)
    {
        // 1) Resolve the user from the bearer token EXACTLY like /xp/batch does.
        $user = $this->resolveUserFromToken($request); // reuse your existing helper
        if (!$user) {
            return response()->json(['message' => 'Unauthorized'], 401);
        }

        $data = $request->validate([
            'username'         => 'required|string',
            'profile_id'       => 'nullable|string',
            'game_mode'        => 'required|string',
            'account_type'     => 'required|string',
            'session_start'    => 'required|integer',
            'session_duration' => 'required|integer',
            'total_xp_gained'  => 'required|integer',
            'timestamp'        => 'required|integer',
            'skills'                 => 'required|array',
            'skills.*.skill'         => 'required|string',
            'skills.*.xp_gained'     => 'required|integer',
            'skills.*.xp_per_hour'   => 'required|integer',
            'skills.*.level'         => 'required|integer',
            'skills.*.current_xp'    => 'required|integer',
        ]);

        // 2) Only accept the account the token belongs to.
        if (mb_strtolower(trim($data['username'])) !== mb_strtolower(trim($user->osrs_rsn))) {
            return response()->json(['message' => 'Username does not match token'], 403);
        }

        DB::transaction(function () use ($user, $data) {
            // 3) Idempotent upsert keyed by (user_id, session_start).
            $session = XpSession::updateOrCreate(
                ['user_id' => $user->id, 'session_start' => $data['session_start']],
                [
                    'profile_id'   => $data['profile_id'] ?? null,
                    'game_mode'    => $data['game_mode'],
                    'account_type' => $data['account_type'],
                    // snapshots grow monotonically within a session — keep the max.
                    'duration_sec' => max($data['session_duration'], 0),
                    'total_xp'     => $data['total_xp_gained'],
                    'last_update'  => $data['timestamp'],
                ]
            );

            foreach ($data['skills'] as $s) {
                $session->skills()->updateOrCreate(
                    ['skill' => mb_strtolower($s['skill'])],
                    [
                        'xp_gained'   => $s['xp_gained'],
                        'xp_per_hour' => $s['xp_per_hour'],
                        'level'       => $s['level'],
                        'current_xp'  => $s['current_xp'],
                    ]
                );
            }
        });

        return response()->json(['status' => 'ok']);
    }
}
```

### Models

```php
// app/Models/XpSession.php
class XpSession extends Model
{
    protected $guarded = [];
    public function skills() { return $this->hasMany(XpSessionSkill::class); }
    public function user()   { return $this->belongsTo(User::class); }
}

// app/Models/XpSessionSkill.php
class XpSessionSkill extends Model
{
    protected $guarded = [];
    public $incrementing = false;
    protected $primaryKey = null;
}
```

> Swap `resolveUserFromToken()` and `$user->osrs_rsn` for whatever your existing
> `/xp/batch` controller already uses to authenticate and identify the account —
> the goal is that `/xp/session` authenticates identically. Once this route
> returns `2xx`, the plugin's "Sync failed" badge becomes "Synced" and the 404s
> stop.
