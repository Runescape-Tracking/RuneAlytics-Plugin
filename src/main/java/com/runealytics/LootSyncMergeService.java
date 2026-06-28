package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the three-source absolute loot merge and submits the result
 * to the RuneAlytics website via the sync-absolute endpoint.
 *
 * <h2>Merge algorithm (max-absolute)</h2>
 * <p>For each player account, source (boss/NPC), and item:</p>
 * <ol>
 *   <li>Fetch the website's saved absolute totals
 *       ({@code GET /runelite/loot/snapshot}).</li>
 *   <li>Read RuneLite's own default Loot Tracker totals straight from its
 *       {@code profiles2/*.properties} save file via
 *       {@link DefaultRuneLiteLootTrackerReader#readForAccount(String)},
 *       scoped to the currently logged-in OSRS username. This is read fresh
 *       on every sync — RuneLite's file is the source of truth, never a
 *       plugin-side cache or temp file.</li>
 *   <li>For each (source, item) tuple, the final quantity is
 *       {@code max(website, runelite_default)}.</li>
 *   <li>Update the plugin local cache (display only) to the merged totals.</li>
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
    /** Max time to wait for the client thread to resolve RuneLite item names. */
    private static final long NAME_RESOLVE_TIMEOUT_MS = 5_000;

    private final CurrentPlayerIdentityService  identity;
    private final LootStorageManager            storageManager;
    private final LootTrackerApiClient          apiClient;
    private final DefaultRuneLiteLootTrackerReader rlReader;
    private final ItemManager                   itemManager;
    private final ClientThread                  clientThread;

    @Inject
    public LootSyncMergeService(
            CurrentPlayerIdentityService identity,
            LootStorageManager storageManager,
            LootTrackerApiClient apiClient,
            DefaultRuneLiteLootTrackerReader rlReader,
            ItemManager itemManager,
            ClientThread clientThread)
    {
        this.identity       = identity;
        this.storageManager = storageManager;
        this.apiClient      = apiClient;
        this.rlReader       = rlReader;
        this.itemManager    = itemManager;
        this.clientThread   = clientThread;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Performs the full website + RuneLite-tracker merge for the currently
     * logged-in account.
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

        log.debug("[merge] Starting merge for account '{}'", accountKey);

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

        // ── 3. Read RuneLite default tracker (best-effort, account-filtered) ──
        // Read directly from RuneLite's own profiles2/*.properties save file
        // on every sync — never from a plugin-side cache or temp file. This is
        // the canonical source of truth for what RuneLite has tracked.
        boolean rlAvailable = rlReader.canImportHistorical();
        Map<String, Map<Integer, Long>> rlTotals = Collections.emptyMap();
        boolean rlSkippedDueToAccount = false;

        if (rlAvailable)
        {
            rlTotals = rlReader.readForAccount(accountKey);
            rlSkippedDueToAccount = rlTotals.isEmpty();
            log.debug("[merge] RuneLite tracker: {} sources (account-filtered)", rlTotals.size());
        }
        else
        {
            log.info("[merge] RuneLite Loot Tracker historical data cannot be safely matched to "
                    + "this account. Using website data only.");
        }

        // Local cache, kept only so the panel has something to render between
        // syncs; it is never read as a merge input.
        LootStorageData localData = storageManager.getCurrentData();

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
                    if (qty <= 0) continue; // never surface a zero-quantity drop
                    int    itemId  = websiteSnapshot.itemIdsByKey.getOrDefault(itemKey, 0);
                    String name    = websiteSnapshot.itemNamesByKey.getOrDefault(itemKey, itemKey);
                    ctx.mergeItem(srcData.sourceKey, srcData.sourceName, null,
                            itemId, name, qty, "website");
                }
            }
        }

        // RuneLite default tracker leg.
        // RuneLite stores only item IDs, so names are resolved via ItemManager —
        // which MUST run on the client thread. Resolve every needed name up front
        // in a single client-thread hop rather than per-item inside this loop.
        Set<Integer> rlItemIds = new HashSet<>();
        for (Map<Integer, Long> items : rlTotals.values()) rlItemIds.addAll(items.keySet());
        Map<Integer, String> rlItemNames = resolveItemNames(rlItemIds);

        for (Map.Entry<String, Map<Integer, Long>> srcEntry : rlTotals.entrySet())
        {
            String sourceName = srcEntry.getKey();
            String sourceKey  = normalizeBossKey(sourceName);
            for (Map.Entry<Integer, Long> itemEntry : srcEntry.getValue().entrySet())
            {
                int  itemId = itemEntry.getKey();
                long qty    = itemEntry.getValue();
                String itemName = rlItemNames.getOrDefault(itemId, "Item " + itemId);
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
                !rlAvailable || rlSkippedDueToAccount,
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
            // Skip sources with no actual loot — don't create an empty
            // placeholder row (0 KC, no drops) on the panel.
            boolean hasLoot = src.items.stream().anyMatch(i -> i.quantity > 0);
            if (!hasLoot) continue;

            LootStorageData.BossKillData bossData =
                    localData.getBossKills().computeIfAbsent(src.sourceName, k -> {
                        LootStorageData.BossKillData bd = new LootStorageData.BossKillData();
                        bd.setNpcName(k);
                        return bd;
                    });

            for (MergedItem item : src.items)
            {
                if (item.quantity <= 0) continue;

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

    /**
     * Resolves display names for the given item IDs via {@link ItemManager}.
     *
     * <p>{@code ItemManager.getItemComposition} asserts it is called on the
     * client thread, so the whole batch is resolved inside a single
     * {@link ClientThread} hop and we block (with a timeout) for the result.
     * This method is only ever called from the background sync executor — never
     * the client thread — so blocking here cannot deadlock the game loop. Item
     * IDs that can't be resolved fall back to {@code "Item <id>"}.</p>
     */
    private Map<Integer, String> resolveItemNames(Set<Integer> itemIds)
    {
        Map<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();
        if (itemIds == null || itemIds.isEmpty()) return names;

        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() ->
        {
            try     { for (int id : itemIds) names.put(id, safeItemName(id)); }
            finally { latch.countDown(); }
        });

        try
        {
            if (!latch.await(NAME_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                log.warn("[merge] Timed out resolving {} RuneLite item name(s); using fallbacks",
                        itemIds.size());
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        // Backfill any IDs the client thread didn't resolve in time.
        for (int id : itemIds) names.putIfAbsent(id, "Item " + id);
        return names;
    }

    /** Resolves a single item name; MUST be called on the client thread. */
    private String safeItemName(int itemId)
    {
        try
        {
            net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp != null && comp.getName() != null ? comp.getName() : "Item " + itemId;
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

                    String reason = "max_of_website_runelite (winner: " + mi.winnerSource + ")";
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
