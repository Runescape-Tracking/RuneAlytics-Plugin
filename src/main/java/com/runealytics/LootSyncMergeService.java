package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;

/**
 * Orchestrates the three-source absolute loot merge and submits the result
 * to the RuneAlytics website via the sync-absolute endpoint.
 *
 * <h2>Merge algorithm (max-absolute)</h2>
 * <p>For each player account, source (boss/NPC), and item:</p>
 * <ol>
 *   <li>Fetch the website's saved absolute totals
 *       ({@code GET /runelite/loot/snapshot}).</li>
 *   <li>Read RuneAlytics plugin local totals from
 *       {@link LootStorageManager#getCurrentData()}.</li>
 *   <li>Read RuneLite default Loot Tracker totals from
 *       {@link DefaultRuneLiteLootTrackerReader#readForAccount(String)}.
 *       <br><em>If historical data cannot be safely tied to the current account,
 *       this leg is skipped.</em></li>
 *   <li>For each (source, item) tuple, the final quantity is
 *       {@code max(website, plugin_local, runelite_default)}.</li>
 *   <li>Update the plugin local cache to the merged totals.</li>
 *   <li>{@code POST} merged totals to {@code /runelite/loot/sync-absolute}.</li>
 * </ol>
 *
 * <h2>Important: RuneLite default tracker data is absolute</h2>
 * <p>RuneLite's Loot Tracker stores cumulative totals, not incremental new
 * drops.  They must never be treated as new kills to add on top of existing
 * data — only as another source for the max-absolute comparison.</p>
 *
 * <h2>Account guard</h2>
 * <p>Before doing anything, {@link CurrentPlayerIdentityService#canSync()} is
 * checked.  If the in-client account does not match the RuneAlytics linked
 * account, the merge is aborted and a descriptive error is returned.</p>
 */
@Slf4j
@Singleton
public class LootSyncMergeService
{
    private final CurrentPlayerIdentityService  identity;
    private final LootStorageManager            storageManager;
    private final LootTrackerApiClient          apiClient;
    private final DefaultRuneLiteLootTrackerReader rlReader;
    private final ItemManager                   itemManager;

    @Inject
    public LootSyncMergeService(
            CurrentPlayerIdentityService identity,
            LootStorageManager storageManager,
            LootTrackerApiClient apiClient,
            DefaultRuneLiteLootTrackerReader rlReader,
            ItemManager itemManager)
    {
        this.identity       = identity;
        this.storageManager = storageManager;
        this.apiClient      = apiClient;
        this.rlReader       = rlReader;
        this.itemManager    = itemManager;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Performs the full three-source merge for the currently logged-in account.
     *
     * <p>Must be called from a background executor thread, never from the
     * RuneLite client thread (network I/O is involved).</p>
     *
     * @return a {@link MergeResult} describing what happened; check
     *         {@link MergeResult#success} before reading other fields
     */
    public MergeResult performMerge()
    {
        // ── 1. Account guard ─────────────────────────────────────────────────
        if (!identity.canSync())
        {
            String msg = identity.getMismatchMessage();
            log.warn("[merge] Sync blocked: {}", msg);
            return MergeResult.blocked(msg);
        }

        String accountKey = identity.getAccountKey();
        if (accountKey == null)
        {
            return MergeResult.blocked("No RuneScape account detected. Log in before syncing.");
        }

        return performMergeForAccount(accountKey);
    }

    /**
     * Performs the three-source merge for an explicitly supplied
     * {@code accountKey}.
     *
     * <p>Unlike {@link #performMerge()}, this does not re-read the live client
     * identity, so it is safe to call during a logout flush (when the local
     * player is already gone). Callers are responsible for having validated
     * that {@code accountKey} is the account that should be synced (e.g. via
     * {@link CurrentPlayerIdentityService#isLinkedAccount(String)}).</p>
     *
     * @param accountKey normalized RuneScape account name; must be non-null
     */
    public MergeResult performMergeForAccount(String accountKey)
    {
        if (accountKey == null || accountKey.isEmpty())
        {
            return MergeResult.blocked("No RuneScape account detected. Log in before syncing.");
        }

        log.debug("[merge] Starting 3-source merge for account '{}'", accountKey);

        // ── 2. Fetch website snapshot ─────────────────────────────────────────
        LootTrackerApiClient.LootSnapshot websiteSnapshot = null;
        try
        {
            websiteSnapshot = apiClient.fetchLootSnapshot(accountKey);
            log.debug("[merge] Website snapshot: {} sources",
                    websiteSnapshot != null ? websiteSnapshot.sources.size() : "null (fetch failed)");
        }
        catch (IOException e)
        {
            log.warn("[merge] Failed to fetch website snapshot: {}", e.getMessage());
            // Non-fatal: continue with local data only.
        }

        // ── 3. Read plugin local totals ───────────────────────────────────────
        LootStorageData localData = storageManager.getCurrentData();
        log.debug("[merge] Plugin local data: {} sources", localData.getBossKills().size());

        // ── 4. Read RuneLite default tracker (best-effort, account-filtered) ──
        boolean rlHistoricalAvailable = rlReader.canImportHistorical();
        Map<String, Map<Integer, Long>> rlTotals = Collections.emptyMap();
        boolean rlSkippedDueToAccount = false;

        if (rlHistoricalAvailable)
        {
            rlTotals = rlReader.readForAccount(accountKey);
            rlSkippedDueToAccount = rlTotals.isEmpty();
            log.debug("[merge] RuneLite tracker: {} sources (account-filtered)", rlTotals.size());
        }
        else
        {
            log.info("[merge] RuneLite Loot Tracker historical data cannot be safely matched to "
                    + "this account. Using live LootReceived events only.");
        }

        // ── 5. Three-source merge ─────────────────────────────────────────────
        MergeContext ctx = new MergeContext(accountKey);

        // Website leg
        if (websiteSnapshot != null)
        {
            for (LootTrackerApiClient.LootSnapshot.SourceData srcData : websiteSnapshot.sources.values())
            {
                for (Map.Entry<String, Long> e : srcData.itemTotals.entrySet())
                {
                    String itemKey = e.getKey();
                    long   qty     = e.getValue();
                    int    itemId  = websiteSnapshot.itemIdsByKey.getOrDefault(itemKey, 0);
                    String name    = websiteSnapshot.itemNamesByKey.getOrDefault(itemKey, itemKey);
                    ctx.mergeItem(srcData.sourceKey, srcData.sourceName, null,
                            itemId, name, qty, "website");
                }
            }
        }

        // Plugin local leg
        for (Map.Entry<String, LootStorageData.BossKillData> bossEntry : localData.getBossKills().entrySet())
        {
            String                         bossName = bossEntry.getKey();
            LootStorageData.BossKillData   bossData = bossEntry.getValue();

            for (Map.Entry<Integer, LootStorageData.AggregatedDrop> dropEntry
                    : bossData.getAggregatedDrops().entrySet())
            {
                LootStorageData.AggregatedDrop agg = dropEntry.getValue();
                ctx.mergeItem(
                        normalizeBossKey(bossName),
                        bossName,
                        null,
                        agg.getItemId(),
                        agg.getItemName(),
                        agg.getTotalQuantity(),
                        "runealytics_plugin");
            }
        }

        // RuneLite default tracker leg
        for (Map.Entry<String, Map<Integer, Long>> srcEntry : rlTotals.entrySet())
        {
            String sourceName = srcEntry.getKey();
            String sourceKey  = normalizeBossKey(sourceName);
            for (Map.Entry<Integer, Long> itemEntry : srcEntry.getValue().entrySet())
            {
                int  itemId = itemEntry.getKey();
                long qty    = itemEntry.getValue();
                // We only have item IDs from RuneLite, not names; resolve via ItemManager.
                String itemName = resolveItemName(itemId);
                ctx.mergeItem(sourceKey, sourceName, null,
                        itemId, itemName, qty, "runelite_default_loot_tracker");
            }
        }

        List<MergedSource> merged = ctx.build();
        log.debug("[merge] Merge complete: {} sources, {} items total",
                merged.size(),
                merged.stream().mapToInt(s -> s.items.size()).sum());

        // ── 6. Apply merged totals to plugin local storage ────────────────────
        applyMergedToLocalStorage(merged, localData);

        // ── 7. Submit merged totals to website ────────────────────────────────
        boolean uploaded = false;
        String uploadError = null;
        try
        {
            uploaded = apiClient.syncAbsolute(accountKey, merged);
            log.debug("[merge] sync-absolute upload: {}", uploaded ? "OK" : "FAILED");
        }
        catch (IOException e)
        {
            uploadError = e.getMessage();
            log.error("[merge] sync-absolute upload failed: {}", uploadError);
        }

        // ── 8. Build result summary ───────────────────────────────────────────
        int itemsTotal = merged.stream().mapToInt(s -> s.items.size()).sum();
        return MergeResult.success(
                accountKey,
                merged.size(),
                itemsTotal,
                uploaded,
                !rlHistoricalAvailable || rlSkippedDueToAccount,
                uploadError);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Writes the merged item totals back to the plugin local cache so that
     * the UI immediately reflects the highest-known absolute quantities.
     *
     * <p>This updates the {@link LootStorageData.BossKillData#aggregatedDrops}
     * map for each source using max(existing, merged) — it does NOT add
     * fake kill records.</p>
     */
    private void applyMergedToLocalStorage(List<MergedSource> merged, LootStorageData localData)
    {
        for (MergedSource src : merged)
        {
            LootStorageData.BossKillData bossData =
                    localData.getBossKills().computeIfAbsent(src.sourceName, k -> {
                        LootStorageData.BossKillData bd = new LootStorageData.BossKillData();
                        bd.setNpcName(k);
                        return bd;
                    });

            for (MergedItem item : src.items)
            {
                LootStorageData.AggregatedDrop existing =
                        bossData.getAggregatedDrops().get(item.itemId > 0 ? item.itemId : -1);

                if (existing == null)
                {
                    LootStorageData.AggregatedDrop agg = new LootStorageData.AggregatedDrop();
                    agg.setItemId(item.itemId);
                    agg.setItemName(item.itemName);
                    agg.setTotalQuantity((int) Math.min(item.quantity, Integer.MAX_VALUE));
                    bossData.getAggregatedDrops().put(item.itemId > 0 ? item.itemId : -1, agg);
                }
                else
                {
                    int newQty = (int) Math.min(
                            Math.max(existing.getTotalQuantity(), item.quantity), Integer.MAX_VALUE);
                    existing.setTotalQuantity(newQty);
                }
            }
        }

        storageManager.scheduleSave();
        log.debug("[merge] Applied merged totals to local storage");
    }

    /**
     * Normalizes a boss/source name to a stable lowercase key, matching the
     * server-side normalization in {@code RuneLiteLootService::normalizeSourceKey}.
     */
    static String normalizeBossKey(String name)
    {
        if (name == null) return "";
        name = name.replace('\u00A0', ' ').trim().toLowerCase();
        name = name.replaceAll("['\u2019\u2018]", "");
        name = name.replaceAll("\\s+", "_");
        return name;
    }

    private String resolveItemName(int itemId)
    {
        try
        {
            net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp != null ? comp.getName() : "Item " + itemId;
        }
        catch (Exception e)
        {
            return "Item " + itemId;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  MERGE CONTEXT (internal accumulator)
    // ═════════════════════════════════════════════════════════════════════════

    private static class MergeContext
    {
        /** Map: sourceKey → (itemKey → MutableItem) */
        private final Map<String, Map<String, MutableItem>> data = new LinkedHashMap<>();
        private final String accountKey;

        MergeContext(String accountKey)
        {
            this.accountKey = accountKey;
        }

        void mergeItem(String sourceKey, String sourceName, String sourceType,
                int itemId, String itemName, long quantity, String source)
        {
            String itemKey = itemId > 0
                    ? "id_" + itemId
                    : "name_" + (itemName != null ? itemName.toLowerCase().trim() : "");

            Map<String, MutableItem> sourceMap =
                    data.computeIfAbsent(sourceKey, k -> new LinkedHashMap<>());

            MutableItem item = sourceMap.get(itemKey);
            if (item == null)
            {
                item = new MutableItem(itemKey, itemId, itemName, sourceType);
                sourceMap.put(itemKey, item);
            }

            if (quantity > item.quantity)
            {
                item.quantity    = quantity;
                item.winnerSource = source;
            }

            // Prefer itemId / itemName from the richer source.
            if (item.itemId <= 0 && itemId > 0) item.itemId = itemId;
            if ((item.itemName == null || item.itemName.isEmpty()) && itemName != null)
                item.itemName = itemName;

            // Persist the source name (for snapshot sourceName field).
            item.sourceNames.put(source, sourceName);
        }

        List<MergedSource> build()
        {
            List<MergedSource> result = new ArrayList<>();
            for (Map.Entry<String, Map<String, MutableItem>> srcEntry : data.entrySet())
            {
                String sourceKey = srcEntry.getKey();
                List<MergedItem> items = new ArrayList<>();

                String sourceName = null;
                String sourceType = null;

                for (MutableItem mi : srcEntry.getValue().values())
                {
                    if (sourceName == null && !mi.sourceNames.isEmpty())
                        sourceName = mi.sourceNames.values().iterator().next();
                    if (sourceType == null && mi.sourceType != null)
                        sourceType = mi.sourceType;

                    String reason = "max_of_website_plugin_runelite (winner: " + mi.winnerSource + ")";
                    items.add(new MergedItem(mi.itemId,
                            mi.itemName != null ? mi.itemName : "Unknown",
                            mi.quantity,
                            reason));
                }

                result.add(new MergedSource(sourceKey,
                        sourceName != null ? sourceName : sourceKey,
                        sourceType,
                        items));
            }
            return result;
        }
    }

    private static class MutableItem
    {
        final String itemKey;
        int    itemId;
        String itemName;
        String sourceType;
        long   quantity;
        String winnerSource;
        final Map<String, String> sourceNames = new LinkedHashMap<>();

        MutableItem(String itemKey, int itemId, String itemName, String sourceType)
        {
            this.itemKey    = itemKey;
            this.itemId     = itemId;
            this.itemName   = itemName;
            this.sourceType = sourceType;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PUBLIC DATA CLASSES (used by LootTrackerApiClient, panel, plugin)
    // ═════════════════════════════════════════════════════════════════════════

    public static class MergedSource
    {
        public final String           sourceKey;
        public final String           sourceName;
        public final String           sourceType;
        public final List<MergedItem> items;

        public MergedSource(String sourceKey, String sourceName,
                String sourceType, List<MergedItem> items)
        {
            this.sourceKey  = sourceKey;
            this.sourceName = sourceName;
            this.sourceType = sourceType;
            this.items      = items;
        }
    }

    public static class MergedItem
    {
        public final int    itemId;
        public final String itemName;
        public final long   quantity;
        public final String mergeReason;

        public MergedItem(int itemId, String itemName, long quantity, String mergeReason)
        {
            this.itemId      = itemId;
            this.itemName    = itemName;
            this.quantity    = quantity;
            this.mergeReason = mergeReason;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  MERGE RESULT
    // ═════════════════════════════════════════════════════════════════════════

    public static class MergeResult
    {
        /** True if the merge completed (possibly with upload failure). */
        @Getter public final boolean success;
        /** If {@code !success}, the human-readable reason sync was blocked. */
        @Getter public final String  blockedReason;

        @Getter public final String  accountKey;
        @Getter public final int     sourcesCount;
        @Getter public final int     itemsCount;
        @Getter public final boolean uploadedToWebsite;
        /** True if RuneLite tracker history was skipped due to account safety. */
        @Getter public final boolean runeliteHistorySkipped;
        @Getter public final String  uploadError;

        private MergeResult(boolean success, String blockedReason, String accountKey,
                int sourcesCount, int itemsCount, boolean uploadedToWebsite,
                boolean runeliteHistorySkipped, String uploadError)
        {
            this.success                = success;
            this.blockedReason          = blockedReason;
            this.accountKey             = accountKey;
            this.sourcesCount           = sourcesCount;
            this.itemsCount             = itemsCount;
            this.uploadedToWebsite      = uploadedToWebsite;
            this.runeliteHistorySkipped = runeliteHistorySkipped;
            this.uploadError            = uploadError;
        }

        static MergeResult blocked(String reason)
        {
            return new MergeResult(false, reason, null, 0, 0, false, false, null);
        }

        static MergeResult success(String accountKey, int sources, int items,
                boolean uploaded, boolean rlSkipped, String uploadError)
        {
            return new MergeResult(true, null, accountKey, sources, items,
                    uploaded, rlSkipped, uploadError);
        }

        /** Returns a one-line summary suitable for UI display. */
        public String toSummaryLine()
        {
            if (!success) return "Loot sync blocked: " + blockedReason;

            String base = String.format("Loot synced for %s: %d source(s), %d item(s)%s",
                    accountKey, sourcesCount, itemsCount,
                    uploadedToWebsite ? " — uploaded to website" : " (website upload failed)");

            if (runeliteHistorySkipped)
            {
                base += ". RuneLite Loot Tracker history was not imported (cannot verify account ownership).";
            }

            return base;
        }
    }
}
