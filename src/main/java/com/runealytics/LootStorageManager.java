package com.runealytics;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

@Slf4j
@Singleton
public class LootStorageManager
{
    private static final String STORAGE_FILE_PREFIX = "runealytics-loot-";
    private static final String STORAGE_FILE_SUFFIX = ".json";
    private final Gson gson;
    private final RuneAlyticsState state;
    private LootStorageData currentData;

    private java.util.concurrent.ScheduledExecutorService saveExecutor = newSaveExecutor();
    private java.util.concurrent.ScheduledFuture<?> pendingSave = null;

    private static java.util.concurrent.ScheduledExecutorService newSaveExecutor()
    {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RuneAlytics-Save");
            t.setDaemon(true);
            return t;
        });
    }

    @Inject
    public LootStorageManager(RuneAlyticsState state, Gson gson)
    {
        this.state = state;
        this.gson = gson.newBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Load loot data for current username
     */
    public synchronized LootStorageData loadData()
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty())
        {
            log.warn("No verified username, cannot load loot data");
            currentData = new LootStorageData();
            return currentData;
        }

        File file = getStorageFile(username);
        if (!file.exists())
        {
            log.debug("No existing loot data file for {}", username);
            currentData = new LootStorageData();
            currentData.setUsername(username);
            return currentData;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath()))
        {
            currentData = gson.fromJson(reader, LootStorageData.class);
            if (currentData == null)
            {
                currentData = new LootStorageData();
                currentData.setUsername(username);
            }
            log.debug("Loaded loot data for {} - {} bosses, {} total kills",
                    username,
                    currentData.getBossKills().size(),
                    currentData.getBossKills().values().stream()
                            .mapToInt(LootStorageData.BossKillData::getKillCount)
                            .sum());
            return currentData;
        }
        catch (Exception e)
        {
            log.error("Failed to load loot data for {}", username, e);
            currentData = new LootStorageData();
            currentData.setUsername(username);
            return currentData;
        }
    }


    /**
     * Save current loot data.
     *
     * <p>Synchronized so it never serialises {@code currentData} while a mutator
     * ({@link #addKill}, {@link #appendDropsToLastKill}, {@link #mergeServerData}…)
     * is modifying the same maps on another thread — that race previously caused
     * {@link java.util.ConcurrentModificationException} / corrupt JSON. The write
     * is also atomic (temp file + rename) so a crash mid-write can't truncate the
     * user's loot history.</p>
     */
    public synchronized void saveData()
    {
        if (currentData == null)
        {
            log.warn("No data to save");
            return;
        }

        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty())
        {
            log.warn("No verified username, cannot save loot data");
            return;
        }

        File file = getStorageFile(username);

        try
        {
            // Ensure parent directory exists
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists())
            {
                parentDir.mkdirs();
            }

            // Write to a temp file first, then atomically swap it into place so a
            // crash / disk-full mid-write leaves the previous good file intact
            // rather than a truncated, unparseable one.
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp.toPath()))
            {
                gson.toJson(currentData, writer);
            }

            try
            {
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException atomicEx)
            {
                // Some filesystems don't support atomic moves — fall back to a
                // plain replace, which is still far safer than writing in place.
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("Saved loot data for {} - {} bosses", username, currentData.getBossKills().size());
        }
        catch (Exception e)
        {
            log.error("Failed to save loot data for {}", username, e);
        }
    }

    /**
     * Debounced save — coalesces rapid-fire mutations (e.g. repeated hide/unhide
     * clicks) into a single disk write 500ms later, off the calling thread.
     * Use this instead of {@link #saveData()} from UI-thread call sites so a
     * synchronous file write never blocks the EDT.
     */
    public synchronized void scheduleSave()
    {
        // The save executor is shut down on plugin shutDown(). Because this is a
        // @Singleton that RuneLite reuses across a disable→enable cycle, recreate
        // it on demand so a later kill doesn't hit a RejectedExecutionException.
        if (saveExecutor.isShutdown())
            saveExecutor = newSaveExecutor();

        if (pendingSave != null && !pendingSave.isDone())
            pendingSave.cancel(false);
        pendingSave = saveExecutor.schedule(this::saveData, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Add a kill to storage (no location — kept for callers that don't capture one).
     */
    public void addKill(String npcName, int npcId, int combatLevel, int killNumber, int world,
                        int prestige, List<LootStorageData.DropRecord> drops)
    {
        addKill(npcName, npcId, combatLevel, killNumber, world, prestige, drops, null);
    }

    /**
     * Add a kill to storage, recording the player's location at kill time so it
     * can be uploaded per-kill in the bulk-sync payload. {@code location} may be
     * {@code null}, in which case the kill simply carries no location.
     */
    public synchronized void addKill(String npcName, int npcId, int combatLevel, int killNumber, int world,
                        int prestige, List<LootStorageData.DropRecord> drops,
                        PlayerLocationSnapshot location)
    {
        if (currentData == null)
        {
            currentData = loadData();
        }

        // Get or create boss data
        LootStorageData.BossKillData bossData = currentData.getBossKills()
                .computeIfAbsent(npcName, k -> {
                    LootStorageData.BossKillData newBoss = new LootStorageData.BossKillData();
                    newBoss.setNpcName(npcName);
                    newBoss.setNpcId(npcId);
                    newBoss.setKillCount(0);
                    newBoss.setPrestige(prestige);
                    newBoss.setTotalLootValue(0);
                    return newBoss;
                });

        // Create kill record
        LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
        killRecord.setTimestamp(System.currentTimeMillis());
        killRecord.setKillNumber(killNumber);
        killRecord.setWorld(world);
        killRecord.setCombatLevel(combatLevel);
        killRecord.setDrops(drops);
        killRecord.setSyncedToServer(false);
        killRecord.setLocation(location);

        // Add kill to list
        bossData.getKills().add(killRecord);

        // Update aggregated stats
        bossData.setKillCount(killNumber);
        bossData.setPrestige(prestige);

        long killValue = 0;
        for (LootStorageData.DropRecord drop : drops)
        {
            killValue += drop.getTotalValue();

            // Update aggregated drops
            LootStorageData.AggregatedDrop aggDrop = bossData.getAggregatedDrops()
                    .computeIfAbsent(drop.getItemId(), k -> {
                        LootStorageData.AggregatedDrop newAgg = new LootStorageData.AggregatedDrop();
                        newAgg.setItemId(drop.getItemId());
                        newAgg.setItemName(drop.getItemName());
                        newAgg.setTotalQuantity(0);
                        newAgg.setDropCount(0);
                        newAgg.setTotalValue(0);
                        newAgg.setGePrice(drop.getGePrice());
                        newAgg.setHighAlch(drop.getHighAlch());
                        return newAgg;
                    });

            aggDrop.setTotalQuantity(aggDrop.getTotalQuantity() + drop.getQuantity());
            aggDrop.setDropCount(aggDrop.getDropCount() + 1);
            aggDrop.setTotalValue(aggDrop.getTotalValue() + drop.getTotalValue());

            // gePrice/highAlch are only seeded above when the entry is first
            // created — if that first drop had a 0 value (e.g. a legacy
            // import before the price-resolution fix), every later drop of
            // the same item kept bumping totalValue while the displayed
            // per-unit price stayed stuck at 0. Refresh it here too.
            if (aggDrop.getGePrice() <= 0 && drop.getGePrice() > 0)  aggDrop.setGePrice(drop.getGePrice());
            if (aggDrop.getHighAlch() <= 0 && drop.getHighAlch() > 0) aggDrop.setHighAlch(drop.getHighAlch());
        }

        bossData.setTotalLootValue(bossData.getTotalLootValue() + killValue);

        scheduleSave();

        log.debug("Added kill #{} for {} - {} drops, {} gp",
                killNumber, npcName, drops.size(), killValue);
    }

    /**
     * Appends extra drops to the most recent kill record for {@code npcName}.
     *
     * <p>Used exclusively for Ring of Wealth auto-collected coins that bypass
     * {@code ItemSpawned} and would otherwise be silently lost.</p>
     *
     * @param npcName normalised boss name matching the existing storage key
     * @param drops   additional drops to merge into the last kill
     */
    public synchronized void appendDropsToLastKill(String npcName, List<LootStorageData.DropRecord> drops)
    {
        if (currentData == null || drops == null || drops.isEmpty()) return;

        LootStorageData.BossKillData bossData = currentData.getBossKills().get(npcName);
        if (bossData == null || bossData.getKills().isEmpty()) return;

        LootStorageData.KillRecord lastKill =
                bossData.getKills().get(bossData.getKills().size() - 1);

        lastKill.getDrops().addAll(drops);
        lastKill.setSyncedToServer(false);

        // Update aggregated stats for the new drops
        for (LootStorageData.DropRecord drop : drops)
        {
            LootStorageData.AggregatedDrop agg = bossData.getAggregatedDrops()
                    .computeIfAbsent(drop.getItemId(), k -> {
                        LootStorageData.AggregatedDrop a = new LootStorageData.AggregatedDrop();
                        a.setItemId(drop.getItemId());
                        a.setItemName(drop.getItemName());
                        a.setTotalQuantity(0);
                        a.setDropCount(0);
                        a.setTotalValue(0);
                        a.setGePrice(drop.getGePrice());
                        a.setHighAlch(drop.getHighAlch());
                        return a;
                    });

            agg.setTotalQuantity(agg.getTotalQuantity() + drop.getQuantity());
            agg.setDropCount(agg.getDropCount() + 1);
            agg.setTotalValue(agg.getTotalValue() + drop.getTotalValue());
            bossData.setTotalLootValue(bossData.getTotalLootValue() + drop.getTotalValue());

            if (agg.getGePrice() <= 0 && drop.getGePrice() > 0)   agg.setGePrice(drop.getGePrice());
            if (agg.getHighAlch() <= 0 && drop.getHighAlch() > 0) agg.setHighAlch(drop.getHighAlch());
        }

        scheduleSave();
        log.debug("Appended {} RoW drop(s) to last '{}' kill", drops.size(), npcName);
    }

    /**
     * Mark kills as synced to server
     */
    public synchronized void markKillsSynced(String npcName, long fromTimestamp, long toTimestamp)
    {
        if (currentData == null) return;

        LootStorageData.BossKillData bossData = currentData.getBossKills().get(npcName);
        if (bossData == null) return;

        int syncedCount = 0;
        for (LootStorageData.KillRecord kill : bossData.getKills())
        {
            if (kill.getTimestamp() >= fromTimestamp && kill.getTimestamp() <= toTimestamp)
            {
                kill.setSyncedToServer(true);
                syncedCount++;
            }
        }

        if (syncedCount > 0)
        {
            saveData();
            log.debug("Marked {} kills as synced for {}", syncedCount, npcName);
        }
    }

    /**
     * Get unsynced kills for upload
     */
    public synchronized List<LootStorageData.KillRecord> getUnsyncedKills(String npcName)
    {
        if (currentData == null) return Collections.emptyList();

        LootStorageData.BossKillData bossData = currentData.getBossKills().get(npcName);
        if (bossData == null) return Collections.emptyList();

        List<LootStorageData.KillRecord> unsynced = new ArrayList<>();
        for (LootStorageData.KillRecord kill : bossData.getKills())
        {
            if (!kill.isSyncedToServer())
            {
                unsynced.add(kill);
            }
        }

        return unsynced;
    }

    /**
     * Get all unsynced kills across all bosses
     */
    public synchronized Map<String, List<LootStorageData.KillRecord>> getAllUnsyncedKills()
    {
        if (currentData == null) return Collections.emptyMap();

        Map<String, List<LootStorageData.KillRecord>> result = new HashMap<>();

        for (Map.Entry<String, LootStorageData.BossKillData> entry : currentData.getBossKills().entrySet())
        {
            List<LootStorageData.KillRecord> unsynced = getUnsyncedKills(entry.getKey());
            if (!unsynced.isEmpty())
            {
                result.put(entry.getKey(), unsynced);
            }
        }

        return result;
    }

    /**
     * Merge server data with local data - NEVER overwrite client when client has equal or more kills
     * CRITICAL: Should ONLY be called during manual sync operations
     */
    public synchronized void mergeServerData(Map<String, LootStorageData.BossKillData> serverData)
    {
        log.debug("mergeServerData() called during manual sync");

        // Merge into the LIVE in-memory data. This previously reloaded from disk
        // unconditionally, which silently discarded any kill recorded in the
        // last ~500ms that the debounced save thread hadn't flushed yet — manual
        // sync runs on a different thread, so a kill made just before pressing
        // sync would be dropped from both the UI and storage and never uploaded.
        // The in-memory copy is always at least as fresh as disk, so only fall
        // back to a disk load when nothing has been loaded yet this session.
        if (currentData == null)
        {
            currentData = loadData();
        }

        if (currentData == null)
        {
            log.error("Failed to load client data - aborting merge");
            return;
        }

        int killsAdded = 0;
        int dropsAdded = 0;
        int bossesSkipped = 0;

        for (Map.Entry<String, LootStorageData.BossKillData> entry : serverData.entrySet())
        {
            String npcName = entry.getKey();
            LootStorageData.BossKillData serverBoss = entry.getValue();

            // Check if boss exists in client data
            LootStorageData.BossKillData localBoss = currentData.getBossKills().get(npcName);

            // If boss exists locally
            if (localBoss != null)
            {
                // CRITICAL: Client has equal or MORE kills - DO NOT TOUCH CLIENT DATA
                if (localBoss.getKillCount() >= serverBoss.getKillCount())
                {
                    log.debug("❌ SKIPPING SERVER DATA: {} - Client KC {} >= Server KC {}",
                            npcName, localBoss.getKillCount(), serverBoss.getKillCount());
                    bossesSkipped++;
                    continue; // Skip this boss entirely - client has fresher data
                }

                // Server has MORE kills - merge the new ones
                log.debug("✅ MERGING: {} - Server KC {} > Client KC {}",
                        npcName, serverBoss.getKillCount(), localBoss.getKillCount());
            }
            else
            {
                // Boss doesn't exist locally - create new from server
                log.debug("➕ NEW BOSS from server: {} with {} kills", npcName, serverBoss.getKillCount());
                localBoss = new LootStorageData.BossKillData();
                localBoss.setNpcName(npcName);
                localBoss.setNpcId(serverBoss.getNpcId());
                localBoss.setKillCount(0);
                localBoss.setPrestige(0);
                localBoss.setTotalLootValue(0);
                currentData.getBossKills().put(npcName, localBoss);
            }

            // Per-boss counter — NOT the running cross-boss `killsAdded` total.
            // Using the global total here previously caused every boss merged
            // after the first to have its KC overwritten with the server value
            // even when nothing new was actually merged for it.
            int bossKillsAdded = 0;

            // Build set of existing kill timestamps and kill numbers (client data)
            Set<Long> existingTimestamps = new HashSet<>();
            Set<Integer> existingKillNumbers = new HashSet<>();

            for (LootStorageData.KillRecord kill : localBoss.getKills())
            {
                existingTimestamps.add(kill.getTimestamp());
                existingKillNumbers.add(kill.getKillNumber());
            }

            // Add ONLY new kills from server that we don't have.
            //
            // Dedup is by kill timestamp (±1s), which is the canonical key the
            // server itself uses (it derives kill_time from the upload timestamp
            // and dedups on kill_time ±1s). The /loot/history endpoint does NOT
            // return a per-kill number, so every server kill arrives with
            // killNumber == 0. Using 0 as a dedup key is actively harmful: merged
            // server kills are stored locally with killNumber 0, so after the
            // first merge `existingKillNumbers` contains 0 and EVERY subsequent
            // server kill (also 0) would be wrongly skipped — including genuinely
            // new ones. Only treat the kill number as a dedup key when it is a
            // real positive value.
            for (LootStorageData.KillRecord serverKill : serverBoss.getKills())
            {
                boolean existsByTimestamp = false;
                for (long existingTs : existingTimestamps)
                {
                    if (Math.abs(existingTs - serverKill.getTimestamp()) <= 1000)
                    {
                        existsByTimestamp = true;
                        break;
                    }
                }

                boolean existsByKillNumber = serverKill.getKillNumber() > 0
                        && existingKillNumbers.contains(serverKill.getKillNumber());

                // Skip if exists by either method
                if (existsByTimestamp || existsByKillNumber)
                {
                    continue;
                }

                // This is a NEW kill - add it
                serverKill.setSyncedToServer(true);
                localBoss.getKills().add(serverKill);
                killsAdded++;
                bossKillsAdded++;

                log.debug("Added missing kill #{} from server: {} at timestamp {}",
                        serverKill.getKillNumber(), npcName, serverKill.getTimestamp());

                // Update aggregated drops
                for (LootStorageData.DropRecord drop : serverKill.getDrops())
                {
                    LootStorageData.AggregatedDrop aggDrop = localBoss.getAggregatedDrops()
                            .computeIfAbsent(drop.getItemId(), k -> {
                                LootStorageData.AggregatedDrop newAgg = new LootStorageData.AggregatedDrop();
                                newAgg.setItemId(drop.getItemId());
                                newAgg.setItemName(drop.getItemName());
                                newAgg.setTotalQuantity(0);
                                newAgg.setDropCount(0);
                                newAgg.setTotalValue(0);
                                newAgg.setGePrice(drop.getGePrice());
                                newAgg.setHighAlch(drop.getHighAlch());
                                return newAgg;
                            });

                    aggDrop.setTotalQuantity(aggDrop.getTotalQuantity() + drop.getQuantity());
                    aggDrop.setDropCount(aggDrop.getDropCount() + 1);
                    aggDrop.setTotalValue(aggDrop.getTotalValue() + drop.getTotalValue());

                    if (aggDrop.getGePrice() <= 0 && drop.getGePrice() > 0)   aggDrop.setGePrice(drop.getGePrice());
                    if (aggDrop.getHighAlch() <= 0 && drop.getHighAlch() > 0) aggDrop.setHighAlch(drop.getHighAlch());
                    dropsAdded++;
                }
            }

            // Update kill count and prestige from server (only if we merged data
            // FOR THIS BOSS — see bossKillsAdded note above).
            if (bossKillsAdded > 0)
            {
                int originalKillCount = localBoss.getKillCount();
                int originalPrestige = localBoss.getPrestige();
                long originalValue = localBoss.getTotalLootValue();

                localBoss.setKillCount(serverBoss.getKillCount()); // Server has more, use that
                localBoss.setPrestige(Math.max(localBoss.getPrestige(), serverBoss.getPrestige()));

                // Recalculate total value from ALL kills in memory
                long recalculatedValue = 0;
                for (LootStorageData.KillRecord kill : localBoss.getKills())
                {
                    for (LootStorageData.DropRecord drop : kill.getDrops())
                    {
                        recalculatedValue += drop.getTotalValue();
                    }
                }
                localBoss.setTotalLootValue(recalculatedValue);

                log.debug("Updated {} stats - KC: {} -> {}, Prestige: {} -> {}, Value: {} -> {}",
                        npcName,
                        originalKillCount, localBoss.getKillCount(),
                        originalPrestige, localBoss.getPrestige(),
                        originalValue, localBoss.getTotalLootValue());
            }
        }

        if (killsAdded > 0 || dropsAdded > 0)
        {
            currentData.setLastSyncTimestamp(System.currentTimeMillis());
            saveData();
            log.debug("Merge complete: Added {} kills, {} drops from server ({} bosses skipped - client data equal/newer)",
                    killsAdded, dropsAdded, bossesSkipped);
        }
        else
        {
            log.debug("Merge complete: No new data from server ({} bosses skipped - client data equal/newer)",
                    bossesSkipped);
        }
    }

    /**
     * Get current data
     */
    public synchronized LootStorageData getCurrentData()
    {
        if (currentData == null)
        {
            return loadData();
        }
        return currentData;
    }

    /**
     * Flushes any pending save and stops the background save executor.
     * Called from {@link LootTrackerManager#shutdown()} on plugin shutDown so the
     * daemon thread doesn't linger and a queued save can't fire post-shutdown.
     */
    public synchronized void shutdown()
    {
        if (pendingSave != null && !pendingSave.isDone())
        {
            pendingSave.cancel(false);
            pendingSave = null;
        }
        saveData();
        saveExecutor.shutdown();
    }

    /**
     * Clear all data for current user
     */
    public synchronized void clearData()
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty()) return;

        currentData = new LootStorageData();
        currentData.setUsername(username);
        saveData();

        log.debug("Cleared all loot data for {}", username);
    }

    /**
     * Get storage file for username
     */
    private File getStorageFile(String username)
    {
        String sanitized = username.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        String filename = STORAGE_FILE_PREFIX + sanitized + STORAGE_FILE_SUFFIX;
        return new File(RuneLite.RUNELITE_DIR, filename);
    }
}