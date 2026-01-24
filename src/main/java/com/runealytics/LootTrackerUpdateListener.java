package com.runealytics;

/**
 * Listener for loot tracker updates
 */
public interface LootTrackerUpdateListener
{
    /**
     * Called when a new kill is recorded
     */
    void onKillRecorded(NpcKillRecord kill, BossKillStats stats);

    void onLootUpdated();

    /**
     * Called when data is refreshed
     */
    void onDataRefresh();

}