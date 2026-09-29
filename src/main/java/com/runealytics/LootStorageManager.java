package com.runealytics;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
@Singleton
public class LootStorageManager
{
    private static final String STORAGE_FILE_PREFIX = "loot-";
    private static final String STORAGE_FILE_SUFFIX = ".json";
    /** Prefix used by releases that wrote loot files straight into {@code ~/.runelite}. */
    static final String LEGACY_FILE_PREFIX = "runealytics-loot-";
    private final Gson gson;
    private final RuneAlyticsState state;
    private LootStorageData currentData;

    /**
     * Plugin data directory ({@code ~/.runelite/plugin-data/runealytics}),
     * supplied by the plugin on startup. While null, loads return empty data
     * and saves are skipped.
     */
    private volatile Filepath dataDirectory;

    /**
     * Directory older releases wrote {@code runealytics-loot-*.json} into.
     * Set only by {@link #migrateLegacyLootFiles}. While a legacy file for the
     * current account is still here, saves refuse to create a new file, so a
     * failed move cannot be sealed by an empty overwrite.
     */
    private volatile File legacyDirectory;

    /** True when {@link #currentData} was read from the legacy file, not the plugin directory. */
    private volatile boolean loadedFromLegacy;

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
     * Sets the plugin data directory used for all loot files. Does not scan
     * {@code ~/.runelite}; call {@link #migrateLegacyLootFiles} for that.
     */
    public void setDataDirectory(Filepath directory)
    {
        this.dataDirectory = directory;
    }

    /**
     * Moves loot files left in {@code legacyDir} by older releases into the
     * plugin data directory. {@code legacyDir} is remembered so a later save
     * can retry the move instead of creating a second file beside it.
     */
    public void migrateLegacyLootFiles(File legacyDir)
    {
        this.legacyDirectory = legacyDir;
        Filepath directory = this.dataDirectory;
        if (directory != null && legacyDir != null)
        {
            migrateLegacyFiles(legacyDir, directory);
        }
    }

    /**
     * Moves {@code runealytics-loot-*.json} files from the legacy location
     * (the root of {@code .runelite}) into the plugin data directory. A file is
     * only migrated when the destination does not already exist, so it never
     * overwrites newer data. Leftover {@code .tmp} files from interrupted
     * legacy saves are removed.
     */
    static void migrateLegacyFiles(File legacyDir, Filepath directory)
    {
        File[] legacy = legacyDir == null ? null : legacyDir.listFiles(f ->
                f.isFile() && f.getName().startsWith(LEGACY_FILE_PREFIX)
                        && (f.getName().endsWith(STORAGE_FILE_SUFFIX)
                            || f.getName().endsWith(STORAGE_FILE_SUFFIX + ".tmp")));
        if (legacy == null || legacy.length == 0) return;

        for (File old : legacy)
        {
            try
            {
                String name = old.getName();
                if (name.endsWith(".tmp"))
                {
                    Files.deleteIfExists(old.toPath());
                    continue;
                }

                String sanitized = name.substring(LEGACY_FILE_PREFIX.length(),
                        name.length() - STORAGE_FILE_SUFFIX.length());
                Filepath target = directory.joinSegment(
                        STORAGE_FILE_PREFIX + sanitized + STORAGE_FILE_SUFFIX);
                if (target.exists())
                {
                    log.debug("Skipping legacy loot file {}: {} already exists", name, target.getFileName());
                    continue;
                }

                // Copy via a temp file so a crash mid-migration can't leave a
                // truncated target that would block a retry on next start.
                directory.createDirectories();
                Filepath tmp = directory.joinSegment(target.getFileName() + ".tmp");
                tmp.write(Files.readAllBytes(old.toPath()));
                try
                {
                    tmp.moveTo(target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                catch (AtomicMoveNotSupportedException atomicEx)
                {
                    tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.delete(old.toPath());
                log.debug("Migrated legacy loot file {} to plugin data directory", name);
            }
            catch (Exception e)
            {
                log.debug("Failed to migrate legacy loot file {}", old.getName(), e);
            }
        }
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

        Filepath file = getStorageFile(username);
        if (file != null && file.exists())
        {
            loadedFromLegacy = false;
            try (Reader reader = file.openBufferedReader())
            {
                return readLoaded(reader, username);
            }
            catch (Exception e)
            {
                log.debug("Failed to load loot data for {}", username, e);
            }
        }
        else
        {
            // Filepath cannot see the old ~/.runelite root, so a failed move
            // still has to be read from the legacy file directly.
            File legacy = legacyFileFor(username);
            if (legacy != null && legacy.isFile())
            {
                try (Reader reader = Files.newBufferedReader(legacy.toPath()))
                {
                    log.debug("Loaded legacy loot file {}", legacy.getName());
                    loadedFromLegacy = true;
                    return readLoaded(reader, username);
                }
                catch (Exception e)
                {
                    log.debug("Failed to load legacy loot data for {}", username, e);
                }
            }
            else
            {
                log.debug("No existing loot data file for {}", username);
            }
        }

        currentData = new LootStorageData();
        currentData.setUsername(username);
        loadedFromLegacy = false;
        return currentData;
    }

    private LootStorageData readLoaded(Reader reader, String username)
    {
        currentData = gson.fromJson(reader, LootStorageData.class);
        if (currentData == null)
        {
            currentData = new LootStorageData();
            currentData.setUsername(username);
            loadedFromLegacy = false;
        }
        int bossCount = 0;
        int killCount = 0;
        if (currentData.getBossKills() != null)
        {
            bossCount = currentData.getBossKills().size();
            killCount = currentData.getBossKills().values().stream()
                    .mapToInt(LootStorageData.BossKillData::getKillCount)
                    .sum();
        }
        log.debug("Loaded loot data for {} - {} bosses, {} total kills",
                username, bossCount, killCount);
        return currentData;
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
        boolean fromLegacy;

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
            bossCount = currentData.getBossKills() == null ? 0 : currentData.getBossKills().size();
            fromLegacy = loadedFromLegacy;
        }

        Filepath file = getStorageFile(username);
        if (file == null)
        {
            log.debug("No plugin data directory, cannot save loot data");
            return;
        }

        // A new file must not appear while the legacy file is still in place.
        // Otherwise the next startup skips migration ("destination exists")
        // and the old history is orphaned.
        if (!file.exists() && !legacyClearedForWrite(username, fromLegacy))
        {
            log.debug("Legacy loot file for {} is still outside the plugin directory; not creating a new file", username);
            return;
        }

        try
        {
            file.getParent().createDirectories();

            // Write to a temp file, then atomically swap it into place so a
            // crash mid-write leaves the previous good file intact.
            Filepath tmp = file.getParent().joinSegment(file.getFileName() + ".tmp");
            try (Writer writer = tmp.openBufferedWriter())
            {
                writer.write(json);
            }

            try
            {
                tmp.moveTo(file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException atomicEx)
            {
                // Some filesystems don't support atomic moves — fall back to a
                // plain replace.
                tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
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
    public void addKill(String npcName, int npcId, int combatLevel, int killNumber, int world,
                        int prestige, List<LootStorageData.DropRecord> drops,
                        PlayerLocationSnapshot location)
    {
        // Snapshot first so a caller mutating the list cannot desync the
        // pre-computed totals from the stored kill, or throw CME mid-add.
        List<LootStorageData.DropRecord> snapshot =
                drops == null ? new ArrayList<>() : new ArrayList<>(drops);

        // Pre-compute aggregated stats outside lock to reduce contention
        long killValue = 0;
        Map<Integer, LootStorageData.AggregatedDrop> precomputedAggs = new HashMap<>();

        for (LootStorageData.DropRecord drop : snapshot)
        {
            killValue += drop.getTotalValue();

            precomputedAggs.computeIfAbsent(drop.getItemId(), k -> {
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
        }

        synchronized (this)
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

            // Create kill record with the snapshot (caller can't mutate stored kills)
            LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
            killRecord.setTimestamp(System.currentTimeMillis());
            killRecord.setKillNumber(killNumber);
            killRecord.setWorld(world);
            killRecord.setCombatLevel(combatLevel);
            killRecord.setDrops(snapshot);
            killRecord.setSyncedToServer(false);
            killRecord.setLocation(location);

            // Add kill to list
            bossData.getKills().add(killRecord);

            // Update aggregated stats with pre-computed values
            bossData.setKillCount(killNumber);
            bossData.setPrestige(prestige);

            for (LootStorageData.DropRecord drop : snapshot)
            {
                LootStorageData.AggregatedDrop aggDrop = bossData.getAggregatedDrops()
                        .computeIfAbsent(drop.getItemId(), k -> precomputedAggs.get(drop.getItemId()));

                aggDrop.setTotalQuantity(aggDrop.getTotalQuantity() + drop.getQuantity());
                aggDrop.setDropCount(aggDrop.getDropCount() + 1);
                aggDrop.setTotalValue(aggDrop.getTotalValue() + drop.getTotalValue());

                if (aggDrop.getGePrice() <= 0 && drop.getGePrice() > 0)  aggDrop.setGePrice(drop.getGePrice());
                if (aggDrop.getHighAlch() <= 0 && drop.getHighAlch() > 0) aggDrop.setHighAlch(drop.getHighAlch());
                if (drop.isPet()) aggDrop.setPet(true);
            }

            bossData.setTotalLootValue(bossData.getTotalLootValue() + killValue);

            currentData.setRevision(currentData.getRevision() + 1);

            scheduleSave();
        }

        log.debug("Added kill #{} for {} - {} drops, {} gp",
                killNumber, npcName, snapshot.size(), killValue);
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
            if (drop.isPet()) agg.setPet(true);
        }

        scheduleSave();
        log.debug("Appended {} drop(s) to last '{}' kill", drops.size(), npcName);
    }

    /**
     * Records the last authoritative in-game kill count for a boss.
     * Raise-only: a lower value never overwrites a higher one (stale or
     * out-of-order KC messages must not regress the stored floor).
     */
    public synchronized void recordLastGameKc(String npcName, int gameKC)
    {
        if (npcName == null || gameKC <= 0) return;
        if (currentData == null) currentData = loadData();
        if (currentData == null) return;

        if (currentData.getLastGameKcByBoss() == null)
        {
            // Files written by pre-2.0.6 versions deserialize without the map.
            currentData.setLastGameKcByBoss(new HashMap<>());
        }

        Integer existing = currentData.getLastGameKcByBoss().get(npcName);
        if (existing != null && existing >= gameKC) return;

        currentData.getLastGameKcByBoss().put(npcName, gameKC);
        scheduleSave();
    }

    /**
     * Relabels the most recent kill record for {@code npcName} with the
     * authoritative game kill count that arrived in chat just after the kill
     * was recorded. Rename-only — never adds or removes a kill.
     *
     * @return {@code true} if the record was relabeled; {@code false} when
     *         there is no eligible record (none exists, the last kill already
     *         carries an equal/higher number, or it already synced to the
     *         server — relabeling a synced kill would re-upload it as a
     *         duplicate)
     */
    public synchronized boolean relabelLastKill(String npcName, int killNumber)
    {
        if (currentData == null || npcName == null || killNumber <= 0) return false;

        LootStorageData.BossKillData bossData = currentData.getBossKills().get(npcName);
        if (bossData == null || bossData.getKills().isEmpty()) return false;

        LootStorageData.KillRecord lastKill =
                bossData.getKills().get(bossData.getKills().size() - 1);

        if (lastKill.getKillNumber() >= killNumber) return false;
        if (lastKill.isSyncedToServer()) return false;

        lastKill.setKillNumber(killNumber);
        if (killNumber > bossData.getKillCount())
        {
            bossData.setKillCount(killNumber);
        }

        if (currentData.getLastGameKcByBoss() == null)
        {
            currentData.setLastGameKcByBoss(new HashMap<>());
        }
        Integer existing = currentData.getLastGameKcByBoss().get(npcName);
        if (existing == null || existing < killNumber)
        {
            currentData.getLastGameKcByBoss().put(npcName, killNumber);
        }

        scheduleSave();
        log.debug("Relabeled last '{}' kill to game KC {}", npcName, killNumber);
        return true;
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
        mergeServerData(serverData, true);
    }

    /**
     * Merges server data into the in-memory copy. Server kills for a boss are
     * only added when the server has more kills than the client.
     *
     * @param manualSync when {@code true}, verbose merge details are logged;
     *                   when {@code false}, only essential debug info is logged
     */
    public synchronized void mergeServerData(Map<String, LootStorageData.BossKillData> serverData, boolean manualSync)
    {
        if (manualSync)
            log.debug("mergeServerData() called during manual sync");

        // Merge into the in-memory copy; load from disk only if nothing is
        // loaded yet this session.
        if (currentData == null)
        {
            currentData = loadData();
        }

        if (currentData == null)
        {
            if (manualSync)
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
                    if (manualSync)
                        log.debug("❌ SKIPPING SERVER DATA: {} - Client KC {} >= Server KC {}",
                                npcName, localBoss.getKillCount(), serverBoss.getKillCount());
                    bossesSkipped++;
                    continue; // Skip this boss entirely - client has fresher data
                }

                // Server has MORE kills - merge the new ones
                if (manualSync)
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
                    if (manualSync)
                        log.debug("❌ SKIPPING SERVER DATA: {} - no kills/loot reported, not creating placeholder",
                                npcName);
                    bossesSkipped++;
                    continue;
                }

                if (manualSync)
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

                // Filter out deleted drops before adding the kill. This prevents
                // deleted items from reappearing when server data is merged back.
                Set<Integer> deletedForBoss = currentData.getDeletedDropsByBoss().get(npcName);
                if (deletedForBoss != null && !deletedForBoss.isEmpty())
                {
                    serverKill.getDrops().removeIf(drop -> deletedForBoss.contains(drop.getItemId()));
                }

                localBoss.getKills().add(serverKill);
                killsAdded++;
                bossKillsAdded++;

                if (manualSync)
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
                    if (drop.isPet()) aggDrop.setPet(true);
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

                if (manualSync)
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
            if (manualSync)
                log.debug("Merge complete: Added {} kills, {} drops from server ({} bosses skipped - client data equal/newer)",
                        killsAdded, dropsAdded, bossesSkipped);
        }
        else
        {
            if (manualSync)
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
        loadedFromLegacy = false;
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
     * Get storage file for username, or null if no data directory is set
     */
    Filepath getStorageFile(String username)
    {
        Filepath directory = dataDirectory;
        if (directory == null) return null;

        return directory.joinSegment(STORAGE_FILE_PREFIX + sanitizeUsername(username) + STORAGE_FILE_SUFFIX);
    }

    private static String sanitizeUsername(String username)
    {
        return username.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    /** Legacy {@code ~/.runelite/runealytics-loot-<user>.json}, or null when unset. */
    private File legacyFileFor(String username)
    {
        File legacyDir = legacyDirectory;
        if (legacyDir == null || username == null || username.isEmpty()) return null;
        return new File(legacyDir, LEGACY_FILE_PREFIX + sanitizeUsername(username) + STORAGE_FILE_SUFFIX);
    }

    /**
     * @return {@code true} when creating the plugin-directory file will not
     *         orphan a legacy file. A move is retried only when memory was
     *         loaded from that legacy file, so a failed read cannot be saved
     *         back over the original.
     */
    private boolean legacyClearedForWrite(String username, boolean fromLegacy)
    {
        File legacy = legacyFileFor(username);
        if (legacy == null || !legacy.isFile()) return true;

        if (fromLegacy)
        {
            Filepath directory = dataDirectory;
            if (directory != null)
            {
                migrateLegacyFiles(legacy.getParentFile(), directory);
            }

            Filepath file = getStorageFile(username);
            if (file != null && file.exists()) return true;
        }

        return false;
    }
}
