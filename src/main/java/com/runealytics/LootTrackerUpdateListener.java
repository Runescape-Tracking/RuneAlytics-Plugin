package com.runealytics;

/**
 * Listener interface for loot tracking updates
 */
public interface LootTrackerUpdateListener
{
    /**
     * Called when a new kill is recorded
     */
    void onKillRecorded(NpcKillRecord kill, BossKillStats stats);

    /**
     * Called when data should be refreshed (after loading or prestige)
     */
    default void onDataRefresh()
    {
        // Optional - implement if needed
    }
}