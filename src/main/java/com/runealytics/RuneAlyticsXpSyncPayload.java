package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Immutable snapshot of the current account's XP session, ready to POST to the
 * RuneAlytics website ({@code /api/plugin/xp/session}).
 *
 * <p>Carries <em>only</em> the currently logged-in account's session data — it is
 * built by {@link RuneAlyticsXpSessionManager#buildPayload} which is scoped to a
 * single account key, so no other local RuneLite profile's XP can leak in.</p>
 *
 * <p>Contains no auth token — the token is attached as a {@code Bearer} header by
 * {@link RunealyticsApiClient#syncXpSession} so it never ends up in a logged
 * payload body.</p>
 */
final class RuneAlyticsXpSyncPayload
{
    /** Normalized RuneScape username of the account this session belongs to. */
    final String username;
    /** RuneLite rsprofile / account identifier, if resolvable ({@code null} otherwise). */
    final String profileId;
    final String gameMode;
    final String accountType;

    /** Session start (epoch seconds) — the session key on the server. */
    final long sessionStartSec;
    /** Active session duration in seconds (excludes logged-out time). */
    final long durationSec;
    /** Total XP gained across all skills this session. */
    final long totalXp;
    /** {@code true} for the final post of a session (logout / shutdown / reset). */
    final boolean ended;

    final List<SkillEntry> skills;

    RuneAlyticsXpSyncPayload(String username, String profileId, String gameMode, String accountType,
                             long sessionStartSec, long durationSec, long totalXp,
                             boolean ended, List<SkillEntry> skills)
    {
        this.username        = username;
        this.profileId       = profileId;
        this.gameMode        = gameMode;
        this.accountType     = accountType;
        this.sessionStartSec = sessionStartSec;
        this.durationSec     = durationSec;
        this.totalXp         = totalXp;
        this.ended           = ended;
        this.skills          = skills;
    }

    /** Per-skill line item in the payload. */
    static final class SkillEntry
    {
        final String skill;      // lowercase skill name
        final long   xpGained;   // gained this session
        final long   xpPerHour;
        final int    level;      // current level
        final long   currentXp;  // current total XP

        SkillEntry(String skill, long xpGained, long xpPerHour, int level, long currentXp)
        {
            this.skill     = skill;
            this.xpGained  = xpGained;
            this.xpPerHour = xpPerHour;
            this.level     = level;
            this.currentXp = currentXp;
        }
    }

    /**
     * Serializes to the JSON body expected by {@code POST /api/plugin/xp/session}:
     *
     * <pre>
     * {
     *   "username": "playername",
     *   "session_start": 1720000000,
     *   "duration_sec": 3600,
     *   "total_xp": 45000,
     *   "ended": true,
     *   "game_mode": "regular",
     *   "account_type": "normal",
     *   "profile_id": "abc123",
     *   "skills": [
     *     { "skill": "mining", "xp_gained": 12000, "xp_per_hour": 12000, "level": 70, "current_xp": 755000 }
     *   ]
     * }
     * </pre>
     */
    JsonObject toJson()
    {
        JsonObject root = new JsonObject();
        root.addProperty("username",     username);
        root.addProperty("session_start", sessionStartSec);
        root.addProperty("duration_sec", durationSec);
        root.addProperty("total_xp",     totalXp);
        root.addProperty("ended",        ended);
        root.addProperty("game_mode",    gameMode    != null ? gameMode    : "regular");
        root.addProperty("account_type", accountType != null ? accountType : "normal");
        if (profileId != null && !profileId.isEmpty())
        {
            root.addProperty("profile_id", profileId);
        }

        JsonArray skillsArr = new JsonArray();
        for (SkillEntry e : skills)
        {
            JsonObject s = new JsonObject();
            s.addProperty("skill",       e.skill);
            s.addProperty("xp_gained",   e.xpGained);
            s.addProperty("xp_per_hour", e.xpPerHour);
            s.addProperty("level",       e.level);
            s.addProperty("current_xp",  e.currentXp);
            skillsArr.add(s);
        }
        root.add("skills", skillsArr);

        return root;
    }
}
