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
            log.debug("No verified username, cannot load loot data");
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
            log.debug("Failed to load loot data for {}", username, e);
            currentData = new LootStorageData();
            currentData.setUsername(username);
            return currentData;
        }
    }


    /**
     * Saves current loot data to disk.
     *
     * <p>Only the in-memory JSON serialisation happens under the lock — that's
     * CPU-only and stays fast even for a large history. The disk write (temp
     * file + atomic rename) runs afterwards with no lock held, so a kill event
     * on the client thread ({@link #addKill}) is never blocked waiting on disk
     * I/O from a background save. Holding a monitor across a blocking file
     * write is exactly what caused the client to stall during AOE kill bursts
     * (several {@link #addKill} calls landing back-to-back on the client
     * thread while a save was mid-write). The write is still atomic (temp
     * file + rename) so a crash mid-write leaves the previous file intact.</p>
     */
    public void saveData()
    {
        String username;
        String json;
        int bossCount;

        synchronized (this)
        {
            if (currentData == null)
            {
                log.debug("No data to save");
                return;
            }

            username = state.getVerifiedUsername();
            if (username == null || username.isEmpty())
            {
                log.debug("No verified username, cannot save loot data");
                return;
            }

            json      = gson.toJson(currentData);
            bossCount = currentData.getBossKills().size();
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

            // Write to a temp file, then atomically swap it into place so a
            // crash mid-write leaves the previous good file intact.
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp.toPath()))
            {
                writer.write(json);
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
                // plain replace.
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("Saved loot data for {} - {} bosses", username, bossCount);
        }
        catch (Exception e)
        {
            log.debug("Failed to save loot data for {}", username, e);
        }
    }

    /**
     * Debounced save: coalesces rapid mutations into a single disk write 500ms
     * later, off the calling thread.
     */
    public synchronized void scheduleSave()
    {
        // Recreate the executor if it was shut down, since this @Singleton is
        // reused across a disable→enable cycle.
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
        killRecord.setDrops(new java.util.ArrayList<>(drops));
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

            // gePrice/highAlch are only seeded when the aggregate entry is
            // created; refresh them here in case the first drop had a 0 value.
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
     * <p>Used for Ring of Wealth auto-collected coins that bypass
     * {@code ItemSpawned}.</p>
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
     * Merges server data into the in-memory copy. Server kills for a boss are
     * only added when the server has more kills than the client. Call only
     * during manual sync operations.
     */
    public synchronized void mergeServerData(Map<String, LootStorageData.BossKillData> serverData)
    {
        log.debug("mergeServerData() called during manual sync");

        // Merge into the in-memory copy; load from disk only if nothing is
        // loaded yet this session.
        if (currentData == null)
        {
            currentData = loadData();
        }

        if (currentData == null)
        {
            log.debug("Failed to load client data - aborting merge");
            return;
        }

        int killsAdded = 0;
        int dropsAdded = 0;
        int bossesSkipped = 0;
        boolean killCountOnlyUpdated = false;

        for (Map.Entry<String, LootStorageData.BossKillData> entry : serverData.entrySet())
        {
            String npcName = entry.getKey();
            LootStorageData.BossKillData serverBoss = entry.getValue();

            // Check if boss exists in client data
            LootStorageData.BossKillData localBoss = currentData.getBossKills().get(npcName);

            // If boss exists locally
            if (localBoss != null)
            {
                // Client has equal or more kills; keep client data.
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
                // Boss doesn't exist locally yet. Only worth creating a row at
                // all if the server actually reports kills/loot for it — don't
                // create a 0-KC, no-drop placeholder that would just show as an
                // empty container on the panel.
                boolean serverHasRealData = serverBoss.getKillCount() > 0
                        || (serverBoss.getKills() != null && !serverBoss.getKills().isEmpty())
                        || (serverBoss.getAggregatedDrops() != null
                                && serverBoss.getAggregatedDrops().values().stream()
                                        .anyMatch(d -> d.getTotalQuantity() > 0));
                if (!serverHasRealData)
                {
                    log.debug("❌ SKIPPING SERVER DATA: {} - no kills/loot reported, not creating placeholder",
                            npcName);
                    bossesSkipped++;
                    continue;
                }

                log.debug("➕ NEW BOSS from server: {} with {} kills", npcName, serverBoss.getKillCount());
                localBoss = new LootStorageData.BossKillData();
                localBoss.setNpcName(npcName);
                localBoss.setNpcId(serverBoss.getNpcId());
                localBoss.setKillCount(0);
                localBoss.setPrestige(0);
                localBoss.setTotalLootValue(0);
                currentData.getBossKills().put(npcName, localBoss);
            }

            // Per-boss counter, separate from the running cross-boss killsAdded total.
            int bossKillsAdded = 0;

            // Build set of existing kill timestamps and kill numbers (client data)
            Set<Long> existingTimestamps = new HashSet<>();
            Set<Integer> existingKillNumbers = new HashSet<>();

            for (LootStorageData.KillRecord kill : localBoss.getKills())
            {
                existingTimestamps.add(kill.getTimestamp());
                existingKillNumbers.add(kill.getKillNumber());
            }

            // Add only server kills not already present. Dedup is by kill
            // timestamp (±1s), the key the server uses. The kill number is only
            // used as a dedup key when it is a real positive value, since
            // history kills arrive with killNumber 0.
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

            // Update kill count and prestige from server when kills were merged
            // for this boss, OR when the server simply reports a higher
            // aggregate kill count than we have locally (max-wins, same rule
            // applied to item quantities elsewhere).
            if (bossKillsAdded > 0 || serverBoss.getKillCount() > localBoss.getKillCount())
            {
                if (bossKillsAdded == 0) killCountOnlyUpdated = true;
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

        if (killsAdded > 0 || dropsAdded > 0 || killCountOnlyUpdated)
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
     * Persists the current account's data to disk immediately (cancelling any
     * pending debounced save). Call on logout, while
     * {@link RuneAlyticsState#getVerifiedUsername()} still refers to the account
     * whose data is in memory, so nothing is lost before {@link #dropCache()}.
     */
    public synchronized void flushNow()
    {
        if (pendingSave != null && !pendingSave.isDone())
        {
            pendingSave.cancel(false);
            pendingSave = null;
        }
        saveData();
    }

    /**
     * Drops the in-memory copy so the next {@link #getCurrentData()} reloads the
     * file for whichever account is logged in now. Prevents one account's loot
     * from being read/written under another account after a profile switch.
     *
     * <p>Does NOT write to disk — callers must {@link #flushNow()} first if the
     * cached data still needs persisting.</p>
     */
    public synchronized void dropCache()
    {
        currentData = null;
    }

    /**
     * Flushes any pending save and stops the background save executor.
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
