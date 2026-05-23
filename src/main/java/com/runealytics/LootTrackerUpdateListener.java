package com.runealytics;

/**
 * Listener for loot-tracker updates.
 *
 * <p>The previous {@code onKillRecorded(NpcKillRecord, BossKillStats)} hook
 * was removed because no caller ever invoked it; new kills are signalled via
 * {@link #onLootUpdated} which carries the canonical
 * {@link LootStorageData.KillRecord} type used everywhere else in the plugin.</p>
 */
public interface LootTrackerUpdateListener
{
    /**
     * Fired once after every kill is recorded and every time aggregated
     * loot for an existing kill changes (e.g. Ring of Wealth coin pickup).
     */
    void onLootUpdated(BossKillStats stats, LootStorageData.KillRecord kill);

    /** Fired after a full data reload (login, account switch, manual sync). */
    void onDataRefresh();
}