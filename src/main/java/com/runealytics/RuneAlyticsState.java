package com.runealytics;

import lombok.Getter;
import lombok.Setter;
import net.runelite.http.api.loottracker.LootRecord;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
@Getter
@Setter
public class RuneAlyticsState
{
    private boolean loggedIn;
    private boolean verified;
    private String verifiedUsername;
    private String verificationCode;
    private int prestige;

    // Additional state for loot tracking
    private boolean syncInProgress;
    private long lastSyncTime;
    private int pendingLootCount;
    private List<LootRecord> lootRecords = new ArrayList<>();

    /**
     * Current game mode resolved at the time of the last loot/XP event.
     * Possible values: "regular", "ironman", "leagues", "deadman",
     * "fresh_start", "grid_master".
     */
    private String currentGameMode = "regular";

    /**
     * Current OSRS account subtype for server-side filtering.
     * Possible values: "normal", "ironman", "hardcore_ironman",
     * "ultimate_ironman", "group_ironman", "hardcore_group_ironman",
     * "unranked_group_ironman".
     */
    private String currentAccountSubtype = "normal";
    private int matchWins;
    private int matchLosses;

    // A map that stores Boss Name -> Number of Kills
    private Map<String, Integer> killCounts = new HashMap<>();

    public void reset()
    {
        loggedIn = false;
        verified = false;
        verifiedUsername = null;
        verificationCode = null;
        syncInProgress = false;
        lastSyncTime = 0;
        pendingLootCount = 0;
        currentGameMode = "regular";
        currentAccountSubtype = "normal";
    }

    public boolean canSync()
    {
        return loggedIn && verified && !syncInProgress;
    }

    public void startSync()
    {
        syncInProgress = true;
        lastSyncTime = System.currentTimeMillis();
    }

    public void endSync()
    {
        syncInProgress = false;
    }
    public void addLootRecord(LootRecord record)
    {
        if (lootRecords == null) {
            lootRecords = new ArrayList<>();
        }
        lootRecords.add(record);
    }

    public void incrementKillCount(String bossName)
    {
        int currentCount = killCounts.getOrDefault(bossName, 0);
        killCounts.put(bossName, currentCount + 1);
    }

    public int getKillCount(String bossName)
    {
        return killCounts.getOrDefault(bossName, 0);
    }
}