package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the current clan state for a player.
 * Thread-safe using ConcurrentHashMap for member list.
 * Lightweight snapshots to avoid data bloom.
 */
@Getter
@Setter
public class ClanInfo
{
    /** Clan name (e.g., "Legends") */
    private String clanName;

    /** Clan tag (e.g., "<tag>") if available */
    private String clanTag;

    /** Clan ID from RuneScape (if obtainable) */
    private Integer clanId;

    /** Player's rank in the clan */
    private String playerRank;

    /** Map of member username → ClanMember; concurrent-safe */
    private final Map<String, ClanMember> members = new ConcurrentHashMap<>();

    /** Unix timestamp when this clan was first joined */
    private long joinedAt;

    /** Unix timestamp of last update to this clan info */
    private long lastUpdateAt;

    /** Unix timestamp of last time members list was synced to server */
    private volatile long lastSyncAt;

    /**
     * Constructs a new clan snapshot.
     */
    public ClanInfo(String clanName)
    {
        this.clanName = clanName;
        this.joinedAt = System.currentTimeMillis() / 1000;
        this.lastUpdateAt = this.joinedAt;
        this.lastSyncAt = 0; // not yet synced
    }

    /**
     * Adds or updates a member in the clan.
     * If member exists, updates last-seen; if new, records join time.
     */
    public void upsertMember(String username, boolean isTracked)
    {
        members.computeIfAbsent(username, u ->
            new ClanMember(u, System.currentTimeMillis() / 1000, isTracked)
        ).updateLastSeen();
        this.lastUpdateAt = System.currentTimeMillis() / 1000;
    }

    /**
     * Remove a member from tracking (when they leave the clan).
     */
    public void removeMember(String username)
    {
        members.remove(username);
        this.lastUpdateAt = System.currentTimeMillis() / 1000;
    }

    /**
     * Gets a snapshot of members for serialization.
     * Creates a copy to avoid concurrent modification during iteration.
     */
    public List<ClanMember> getMemberSnapshot()
    {
        return new ArrayList<>(members.values());
    }

    /**
     * Gets member count. Safe for concurrent access.
     */
    public int getMemberCount()
    {
        return members.size();
    }

    /**
     * Checks if we know of a member.
     */
    public boolean hasMember(String username)
    {
        return members.containsKey(username);
    }

    /**
     * Converts to JSON for network transmission.
     * Includes only essential fields and member count (not full member list).
     * Full member list sent separately via syncClanMembers() to the /plugin/clan/members endpoint.
     */
    public JsonObject toJson()
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("clan_name",     clanName != null ? clanName : "");
        obj.addProperty("clan_tag",      clanTag != null ? clanTag : "");
        obj.addProperty("player_rank",   playerRank != null ? playerRank : "member");
        obj.addProperty("member_count",  members.size());
        obj.addProperty("joined_at",     joinedAt);
        obj.addProperty("last_update",   lastUpdateAt);
        if (clanId != null)
        {
            obj.addProperty("clan_id", clanId);
        }
        return obj;
    }

    /**
     * Converts member list to JSON array for member sync endpoint.
     * Called separately to avoid oversized heartbeat payloads.
     */
    public JsonArray membersToJsonArray()
    {
        JsonArray arr = new JsonArray();
        for (ClanMember member : getMemberSnapshot())
        {
            arr.add(member.toJson());
        }
        return arr;
    }

    /**
     * Checks if the clan data has been modified since last sync.
     */
    public boolean isDirty()
    {
        return lastUpdateAt > lastSyncAt;
    }
}
