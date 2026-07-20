package com.runealytics;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Suppresses inventory-diff loot attribution while the player is moving items
 * through a non-loot interface: bank, deposit box, Grand Exchange, shop,
 * trade, seed vault or group storage.
 *
 * <h2>Problem</h2>
 * <p>The skilling, pickpocket, impling-jar and crate-reward paths all work by
 * diffing inventory snapshots. A bank withdrawal (or trade/shop/GE collect)
 * that lands inside one of those attribution windows is indistinguishable
 * from loot at the container level — e.g. withdrawing feathers within 1.5 s of
 * a Fishing XP drop recorded the feathers as Fishing loot. Equipment moves
 * were already excluded ({@code excludeEquipmentMovement}); bank/trade/shop
 * moves were not.</p>
 *
 * <h2>Model</h2>
 * <p>Mirrors {@link DeathRecoveryGuard}: the plugin feeds widget open/close
 * events in, and every inventory-diff site asks
 * {@link #shouldSuppressInventoryDiff()} before attributing gains. Suppression
 * lasts while any tracked interface is open plus a short
 * {@value #CLOSE_COOLDOWN_MS} ms cooldown after the last one closes, covering
 * trailing {@code ItemContainerChanged} events that arrive a tick or two
 * later.</p>
 *
 * <p>Confirmed-attribution paths ({@code NpcLootReceived},
 * {@code PlayerLootReceived}, reward-container/widget reads) are NOT gated by
 * this guard — RuneLite has already tied those items to a kill or chest, and
 * loot must never be lost because a bank happened to be open.</p>
 *
 * <p>Pure Java (widget group ids are plain ints supplied by the plugin), so
 * the logic is fully unit-testable without a client.</p>
 */
public class InventoryDiffGuard
{
    // ── Tracked widget group IDs (stable OSRS interface ids) ────────────────
    static final int GROUP_BANK           = 12;
    static final int GROUP_BANK_PIN       = 213;
    static final int GROUP_DEPOSIT_BOX    = 192;
    static final int GROUP_GRAND_EXCHANGE = 465;
    static final int GROUP_GE_COLLECTION  = 402;
    static final int GROUP_SHOP           = 300;
    static final int GROUP_TRADE          = 335;
    static final int GROUP_TRADE_CONFIRM  = 334;
    static final int GROUP_SEED_VAULT     = 631;
    static final int GROUP_GROUP_STORAGE  = 724;

    private static final Set<Integer> TRACKED_GROUPS = buildTrackedGroups();

    private static Set<Integer> buildTrackedGroups()
    {
        Set<Integer> s = new HashSet<>();
        s.add(GROUP_BANK);
        s.add(GROUP_BANK_PIN);
        s.add(GROUP_DEPOSIT_BOX);
        s.add(GROUP_GRAND_EXCHANGE);
        s.add(GROUP_GE_COLLECTION);
        s.add(GROUP_SHOP);
        s.add(GROUP_TRADE);
        s.add(GROUP_TRADE_CONFIRM);
        s.add(GROUP_SEED_VAULT);
        s.add(GROUP_GROUP_STORAGE);
        return s;
    }

    /**
     * How long after the last tracked interface closes that inventory diffs
     * remain suppressed (~3 game ticks), absorbing trailing container events.
     */
    static final long CLOSE_COOLDOWN_MS = 1_800L;

    private final LongSupplier clock;

    /** Tracked interfaces currently open (client thread only). */
    private final Set<Integer> openGroups = new HashSet<>();

    /** When the last tracked interface closed (epoch ms), or 0. */
    private volatile long lastCloseAt = 0L;

    /** True while any tracked interface is open — volatile mirror of openGroups. */
    private volatile boolean anyOpen = false;

    public InventoryDiffGuard()
    {
        this(System::currentTimeMillis);
    }

    /** Test seam: deterministic clock. */
    InventoryDiffGuard(LongSupplier clock)
    {
        this.clock = clock;
    }

    /** Feed from {@code WidgetLoaded}: marks a tracked interface as open. */
    public synchronized void onWidgetLoaded(int groupId)
    {
        if (TRACKED_GROUPS.contains(groupId))
        {
            openGroups.add(groupId);
            anyOpen = true;
        }
    }

    /** Feed from {@code WidgetClosed}: starts the close cooldown. */
    public synchronized void onWidgetClosed(int groupId)
    {
        if (openGroups.remove(groupId))
        {
            lastCloseAt = clock.getAsLong();
            anyOpen = !openGroups.isEmpty();
        }
    }

    /**
     * @return {@code true} while a bank/GE/shop/trade/vault interface is open,
     *         or within {@value #CLOSE_COOLDOWN_MS} ms of the last one closing
     *         — inventory-diff loot attribution must be skipped.
     */
    public boolean shouldSuppressInventoryDiff()
    {
        if (anyOpen) return true;
        long closedAt = lastCloseAt;
        return closedAt != 0L && clock.getAsLong() - closedAt < CLOSE_COOLDOWN_MS;
    }

    /** Clears all state (logout / account switch / plugin restart). */
    public synchronized void reset()
    {
        openGroups.clear();
        anyOpen = false;
        lastCloseAt = 0L;
    }
}
