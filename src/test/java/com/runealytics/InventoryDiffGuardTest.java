package com.runealytics;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the bank/GE/shop/trade suppression window that keeps withdrawals and
 * trades out of the inventory-diff loot paths.
 */
public class InventoryDiffGuardTest
{
    private AtomicLong clock;
    private InventoryDiffGuard guard;

    @Before
    public void setUp()
    {
        clock = new AtomicLong(1_000_000L);
        guard = new InventoryDiffGuard(clock::get);
    }

    @Test
    public void noInterfaceOpen_noSuppression()
    {
        assertFalse(guard.shouldSuppressInventoryDiff());
    }

    @Test
    public void bankOpen_suppresses()
    {
        guard.onWidgetLoaded(InventoryDiffGuard.GROUP_BANK);
        assertTrue(guard.shouldSuppressInventoryDiff());
    }

    @Test
    public void untrackedWidget_isIgnored()
    {
        // 155 = Barrows reward widget group — a reward interface, not a bank.
        final int barrowsRewardGroup = 155;
        guard.onWidgetLoaded(barrowsRewardGroup);
        assertFalse("reward interfaces must not suppress loot",
                guard.shouldSuppressInventoryDiff());
        // Closing a widget that was never tracked-open must not start a cooldown.
        guard.onWidgetClosed(barrowsRewardGroup);
        assertFalse(guard.shouldSuppressInventoryDiff());
    }

    @Test
    public void closeStartsCooldown_thenReleases()
    {
        guard.onWidgetLoaded(InventoryDiffGuard.GROUP_BANK);
        guard.onWidgetClosed(InventoryDiffGuard.GROUP_BANK);

        // Immediately after close: trailing container events still suppressed.
        assertTrue(guard.shouldSuppressInventoryDiff());

        clock.addAndGet(InventoryDiffGuard.CLOSE_COOLDOWN_MS - 1);
        assertTrue(guard.shouldSuppressInventoryDiff());

        clock.addAndGet(2);
        assertFalse("suppression must end after the cooldown",
                guard.shouldSuppressInventoryDiff());
    }

    @Test
    public void overlappingInterfaces_suppressUntilLastCloses()
    {
        guard.onWidgetLoaded(InventoryDiffGuard.GROUP_BANK);
        guard.onWidgetLoaded(InventoryDiffGuard.GROUP_GRAND_EXCHANGE);

        guard.onWidgetClosed(InventoryDiffGuard.GROUP_BANK);
        clock.addAndGet(InventoryDiffGuard.CLOSE_COOLDOWN_MS + 1_000);
        assertTrue("GE still open — must stay suppressed",
                guard.shouldSuppressInventoryDiff());

        guard.onWidgetClosed(InventoryDiffGuard.GROUP_GRAND_EXCHANGE);
        clock.addAndGet(InventoryDiffGuard.CLOSE_COOLDOWN_MS + 1);
        assertFalse(guard.shouldSuppressInventoryDiff());
    }

    @Test
    public void allTrackedInterfaceTypesSuppress()
    {
        int[] groups = {
                InventoryDiffGuard.GROUP_BANK,
                InventoryDiffGuard.GROUP_BANK_PIN,
                InventoryDiffGuard.GROUP_DEPOSIT_BOX,
                InventoryDiffGuard.GROUP_GRAND_EXCHANGE,
                InventoryDiffGuard.GROUP_GE_COLLECTION,
                InventoryDiffGuard.GROUP_SHOP,
                InventoryDiffGuard.GROUP_TRADE,
                InventoryDiffGuard.GROUP_TRADE_CONFIRM,
                InventoryDiffGuard.GROUP_SEED_VAULT,
                InventoryDiffGuard.GROUP_GROUP_STORAGE,
        };
        for (int group : groups)
        {
            guard.reset();
            guard.onWidgetLoaded(group);
            assertTrue("group " + group + " must suppress", guard.shouldSuppressInventoryDiff());
        }
    }

    @Test
    public void reset_clearsOpenStateAndCooldown()
    {
        guard.onWidgetLoaded(InventoryDiffGuard.GROUP_BANK);
        guard.onWidgetClosed(InventoryDiffGuard.GROUP_BANK);
        guard.reset();
        assertFalse(guard.shouldSuppressInventoryDiff());
    }
}
