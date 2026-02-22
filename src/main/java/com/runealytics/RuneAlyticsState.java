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
        // Assuming you have a List of records inside this class
        if (lootRecords == null) {
            lootRecords = new ArrayList<>();
        }
        lootRecords.add(record);
    }
    // The method your Manager is looking for
    public void incrementKillCount(String bossName)
    {
        // Get the current count, add 1, and save it back
        int currentCount = killCounts.getOrDefault(bossName, 0);
        killCounts.put(bossName, currentCount + 1);
    }

    // You'll likely need this later for your UI panel
    public int getKillCount(String bossName)
    {
        return killCounts.getOrDefault(bossName, 0);
    }
}