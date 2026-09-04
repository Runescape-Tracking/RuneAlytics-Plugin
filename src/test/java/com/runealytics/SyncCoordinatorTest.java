package com.runealytics;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the single-flight + coalesce contract used to keep overlapping
 * loot syncs from dropping a user-initiated request.
 */
public class SyncCoordinatorTest
{
    private SyncCoordinator coordinator;

    @Before
    public void setUp()
    {
        coordinator = new SyncCoordinator();
    }

    private static SyncRequest req(SyncRequest.Priority priority, boolean full, String reason)
    {
        return new SyncRequest(priority, full, reason);
    }

    @Test
    public void tryStartSync_firstCallerWins()
    {
        assertTrue(coordinator.tryStartSync(req(SyncRequest.Priority.AUTO, false, "auto")));
        assertTrue(coordinator.isSyncRunning());
        assertFalse(coordinator.tryStartSync(req(SyncRequest.Priority.LOGIN, true, "login")));
        assertEquals(SyncRequest.Priority.LOGIN, coordinator.getPendingRequest().getPriority());
    }

    @Test
    public void endSync_returnsCoalescedPendingAndClearsSlot()
    {
        coordinator.tryStartSync(req(SyncRequest.Priority.AUTO, false, "auto"));
        coordinator.tryStartSync(req(SyncRequest.Priority.LOGIN, false, "login"));
        coordinator.tryStartSync(req(SyncRequest.Priority.MANUAL, true, "manual"));

        SyncRequest pending = coordinator.endSync();
        assertFalse(coordinator.isSyncRunning());
        assertNull(coordinator.getPendingRequest());
        assertEquals(SyncRequest.Priority.MANUAL, pending.getPriority());
        assertTrue(pending.isFullReconcile());
        assertTrue(pending.getReason().contains("login"));
        assertTrue(pending.getReason().contains("manual"));
    }

    @Test
    public void endSync_withNoPending_returnsNull()
    {
        coordinator.tryStartSync(req(SyncRequest.Priority.AUTO, false, "auto"));
        assertNull(coordinator.endSync());
        assertTrue(coordinator.tryStartSync(req(SyncRequest.Priority.AUTO, false, "next")));
    }

    @Test
    public void mergeWith_takesHigherPriorityAndOrsFullReconcile()
    {
        SyncRequest merged = req(SyncRequest.Priority.AUTO, false, "auto")
                .mergeWith(req(SyncRequest.Priority.LOGOUT, true, "logout"));

        assertEquals(SyncRequest.Priority.LOGOUT, merged.getPriority());
        assertTrue(merged.isFullReconcile());
        assertEquals("auto + logout", merged.getReason());
    }

    @Test
    public void mergeWith_null_returnsThis()
    {
        SyncRequest original = req(SyncRequest.Priority.MANUAL, true, "click");
        assertEquals(original, original.mergeWith(null));
    }
}
