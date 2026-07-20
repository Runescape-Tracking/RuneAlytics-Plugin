package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-skill training economics for the XP Tracker: GP made (outputs produced),
 * GP spent (supplies consumed) and the resulting profit, kept per session and
 * per calendar day — valued <b>both</b> at Grand Exchange prices and at high
 * alchemy prices, so the detail panel can show a GE profit section and a
 * separate alch section.
 *
 * <p>Fed by {@code RuneAlyticsPlugin}'s skilling inventory diff — the same
 * attribution window that ties item changes to the skill that just gained XP —
 * with item values resolved on the client thread before they reach this class.
 * Owned by {@link RuneAlyticsXpSessionManager}, which scopes it to the active
 * account exactly like the XP session itself.</p>
 *
 * <h2>Account scoping</h2>
 * {@link #setAccount(String)} clears the session state and loads that
 * account's persisted "today" totals; data recorded before an account is set
 * is dropped. Day totals persist through the injected {@link Store}
 * (production: RuneLite's ConfigManager) and reset at local midnight.
 *
 * <h2>Threading</h2>
 * {@link #record} runs on the client thread; snapshots are read from the EDT.
 * All public methods are synchronized and snapshots are deep copies.
 *
 * <p>Pure Java apart from Gson — fully unit-testable without a client.</p>
 */
class SkillEconomyTracker
{
    /** Throttle between persisting the day state to the store. */
    private static final long PERSIST_INTERVAL_MS = 10_000L;

    /** Max distinct supply items kept per skill (session and day alike). */
    private static final int MAX_ITEMS_PER_SKILL = 64;

    // ── Collaborator seams ────────────────────────────────────────────────────

    /** Minimal key/value persistence seam (production: ConfigManager-backed). */
    interface Store
    {
        String get(String key);

        void put(String key, String value);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    /** One item stack with its per-item GE and high-alch values already resolved. */
    static final class ValuedStack
    {
        final int    itemId;
        final String itemName;
        final int    quantity;
        final long   geEach;
        final long   alchEach;

        ValuedStack(int itemId, String itemName, int quantity, long geEach, long alchEach)
        {
            this.itemId   = itemId;
            this.itemName = itemName;
            this.quantity = quantity;
            this.geEach   = geEach;
            this.alchEach = alchEach;
        }
    }

    /** Aggregated flow of one item: quantity consumed plus its GE / alch GP. */
    static final class ItemFlow
    {
        @SerializedName("i") final int    itemId;
        @SerializedName("n") String itemName;
        @SerializedName("q") long   quantity;
        @SerializedName("g") long   geGp;
        @SerializedName("a") long   alchGp;

        ItemFlow(int itemId, String itemName, long quantity, long geGp, long alchGp)
        {
            this.itemId   = itemId;
            this.itemName = itemName;
            this.quantity = quantity;
            this.geGp     = geGp;
            this.alchGp   = alchGp;
        }

        int    getItemId()   { return itemId; }
        String getItemName() { return itemName; }
        long   getQuantity() { return quantity; }
        long   getGeGp()     { return geGp; }
        long   getAlchGp()   { return alchGp; }
    }

    /** Immutable read model for the UI. Supplies are sorted by GE GP descending. */
    static final class Snapshot
    {
        final long sessionOutputGe;
        final long sessionInputGe;
        final long sessionOutputAlch;
        final long sessionInputAlch;
        final long todayOutputGe;
        final long todayInputGe;
        final long todayOutputAlch;
        final long todayInputAlch;
        final List<ItemFlow> sessionSupplies;
        final List<ItemFlow> todaySupplies;

        Snapshot(long sessionOutputGe, long sessionInputGe,
                 long sessionOutputAlch, long sessionInputAlch,
                 long todayOutputGe, long todayInputGe,
                 long todayOutputAlch, long todayInputAlch,
                 List<ItemFlow> sessionSupplies, List<ItemFlow> todaySupplies)
        {
            this.sessionOutputGe   = sessionOutputGe;
            this.sessionInputGe    = sessionInputGe;
            this.sessionOutputAlch = sessionOutputAlch;
            this.sessionInputAlch  = sessionInputAlch;
            this.todayOutputGe     = todayOutputGe;
            this.todayInputGe      = todayInputGe;
            this.todayOutputAlch   = todayOutputAlch;
            this.todayInputAlch    = todayInputAlch;
            this.sessionSupplies   = sessionSupplies;
            this.todaySupplies     = todaySupplies;
        }

        long sessionProfitGe()   { return sessionOutputGe - sessionInputGe; }
        long sessionProfitAlch() { return sessionOutputAlch - sessionInputAlch; }
        long todayProfitGe()     { return todayOutputGe - todayInputGe; }
        long todayProfitAlch()   { return todayOutputAlch - todayInputAlch; }

        boolean hasSessionData()
        {
            return sessionOutputGe != 0 || sessionInputGe != 0
                    || sessionOutputAlch != 0 || sessionInputAlch != 0
                    || !sessionSupplies.isEmpty();
        }

        boolean hasTodayData()
        {
            return todayOutputGe != 0 || todayInputGe != 0
                    || todayOutputAlch != 0 || todayInputAlch != 0
                    || !todaySupplies.isEmpty();
        }

        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0,
                Collections.emptyList(), Collections.emptyList());
    }

    /** Mutable per-skill totals (used for both session and day scopes). */
    private static final class SkillEconomy
    {
        @SerializedName("outGe") long outputGe;
        @SerializedName("inGe")  long inputGe;
        @SerializedName("outHa") long outputAlch;
        @SerializedName("inHa")  long inputAlch;
        @SerializedName("items") Map<Integer, ItemFlow> consumed = new LinkedHashMap<>();
    }

    /** Persisted day payload: date + per-skill totals. */
    private static final class DayState
    {
        @SerializedName("date")   String date;
        @SerializedName("skills") Map<String, SkillEconomy> skills = new HashMap<>();
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Store store;
    private final Gson gson;
    private final Supplier<String> dayKeySupplier;

    private String accountKey;
    private final Map<String, SkillEconomy> session = new HashMap<>();
    private DayState day = new DayState();
    private long lastPersistMs;
    private boolean dayDirty;

    SkillEconomyTracker(Store store, Gson gson)
    {
        this(store, gson, () -> LocalDate.now().toString());
    }

    /** Test seam: deterministic day key. */
    SkillEconomyTracker(Store store, Gson gson, Supplier<String> dayKeySupplier)
    {
        this.store          = store;
        this.gson           = gson;
        this.dayKeySupplier = dayKeySupplier;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Scopes the tracker to an account: clears session totals and loads that
     * account's persisted day totals. No-op when the account is unchanged.
     */
    synchronized void setAccount(String acct)
    {
        if (acct == null || acct.equals(accountKey)) return;

        flushLocked();
        accountKey = acct;
        session.clear();
        day = loadDay(acct);
    }

    /** Clears the session totals (panel Reset button); day totals are kept. */
    synchronized void resetSession()
    {
        session.clear();
    }

    /** Clears one skill's session totals; its day totals are kept. */
    synchronized void resetSkill(String skillKey)
    {
        if (skillKey != null) session.remove(skillKey);
    }

    /** Persists pending day totals immediately (logout / shutdown). */
    synchronized void flush()
    {
        flushLocked();
    }

    // ── Ingest (client thread) ────────────────────────────────────────────────

    /**
     * Records one skilling inventory delta: items produced (entered the
     * inventory) and supplies consumed (left it), both already valued.
     * Ignored until an account has been set.
     */
    synchronized void record(String skillKey, List<ValuedStack> produced,
                             List<ValuedStack> consumed, long nowMs)
    {
        if (accountKey == null || skillKey == null) return;
        boolean hasProduced = produced != null && !produced.isEmpty();
        boolean hasConsumed = consumed != null && !consumed.isEmpty();
        if (!hasProduced && !hasConsumed) return;

        rollDayIfNeeded();

        SkillEconomy sess = session.computeIfAbsent(skillKey, k -> new SkillEconomy());
        SkillEconomy today = day.skills.computeIfAbsent(skillKey, k -> new SkillEconomy());

        if (hasProduced)
        {
            for (ValuedStack v : produced)
            {
                long ge   = v.geEach * v.quantity;
                long alch = v.alchEach * v.quantity;
                sess.outputGe    += ge;
                sess.outputAlch  += alch;
                today.outputGe   += ge;
                today.outputAlch += alch;
            }
        }

        if (hasConsumed)
        {
            for (ValuedStack v : consumed)
            {
                long ge   = v.geEach * v.quantity;
                long alch = v.alchEach * v.quantity;
                sess.inputGe    += ge;
                sess.inputAlch  += alch;
                today.inputGe   += ge;
                today.inputAlch += alch;
                addConsumed(sess.consumed,  v, ge, alch);
                addConsumed(today.consumed, v, ge, alch);
            }
        }

        dayDirty = true;
        if (nowMs - lastPersistMs > PERSIST_INTERVAL_MS)
        {
            lastPersistMs = nowMs;
            flushLocked();
        }
    }

    private static void addConsumed(Map<Integer, ItemFlow> map, ValuedStack v, long ge, long alch)
    {
        ItemFlow flow = map.get(v.itemId);
        if (flow == null)
        {
            if (map.size() >= MAX_ITEMS_PER_SKILL) return; // bounded; drop the long tail
            map.put(v.itemId, new ItemFlow(v.itemId, v.itemName, v.quantity, ge, alch));
            return;
        }
        flow.quantity += v.quantity;
        flow.geGp     += ge;
        flow.alchGp   += alch;
        if (flow.itemName == null || flow.itemName.isEmpty()) flow.itemName = v.itemName;
    }

    // ── Reads (EDT) ───────────────────────────────────────────────────────────

    /** Deep-copied snapshot for one skill; {@link Snapshot#EMPTY} when nothing recorded. */
    synchronized Snapshot snapshot(String skillKey)
    {
        if (skillKey == null) return Snapshot.EMPTY;
        rollDayIfNeeded();

        SkillEconomy sess = session.get(skillKey);
        SkillEconomy today = day.skills.get(skillKey);
        if (sess == null && today == null) return Snapshot.EMPTY;

        return new Snapshot(
                sess != null ? sess.outputGe : 0L,
                sess != null ? sess.inputGe : 0L,
                sess != null ? sess.outputAlch : 0L,
                sess != null ? sess.inputAlch : 0L,
                today != null ? today.outputGe : 0L,
                today != null ? today.inputGe : 0L,
                today != null ? today.outputAlch : 0L,
                today != null ? today.inputAlch : 0L,
                copySorted(sess != null ? sess.consumed : null),
                copySorted(today != null ? today.consumed : null));
    }

    private static List<ItemFlow> copySorted(Map<Integer, ItemFlow> map)
    {
        if (map == null || map.isEmpty()) return Collections.emptyList();
        List<ItemFlow> list = new ArrayList<>(map.size());
        for (ItemFlow f : map.values())
        {
            list.add(new ItemFlow(f.itemId, f.itemName, f.quantity, f.geGp, f.alchGp));
        }
        list.sort(Comparator.comparingLong(ItemFlow::getGeGp).reversed()
                .thenComparing(ItemFlow::getItemId));
        return list;
    }

    // ── Day persistence ───────────────────────────────────────────────────────

    private void rollDayIfNeeded()
    {
        String today = dayKeySupplier.get();
        if (!today.equals(day.date))
        {
            day = new DayState();
            day.date = today;
            dayDirty = true;
        }
    }

    private DayState loadDay(String acct)
    {
        String today = dayKeySupplier.get();
        try
        {
            String json = store.get(dayDataKey(acct));
            if (json != null && !json.isEmpty())
            {
                DayState loaded = gson.fromJson(json, DayState.class);
                if (loaded != null && today.equals(loaded.date))
                {
                    if (loaded.skills == null) loaded.skills = new HashMap<>();
                    return loaded;
                }
            }
        }
        catch (Exception e)
        {
            // Malformed/stale payload — start the day fresh rather than fail.
        }
        DayState fresh = new DayState();
        fresh.date = today;
        return fresh;
    }

    private void flushLocked()
    {
        if (!dayDirty || accountKey == null) return;
        try
        {
            if (day.date == null) day.date = dayKeySupplier.get();
            store.put(dayDataKey(accountKey), gson.toJson(day));
            dayDirty = false;
        }
        catch (Exception e)
        {
            // Persistence must never break tracking; retry on the next flush.
        }
    }

    private static String dayDataKey(String acct)
    {
        return "xpEconDay_" + (acct == null ? "unknown" : acct.replaceAll("[^a-z0-9]", "_"));
    }
}
