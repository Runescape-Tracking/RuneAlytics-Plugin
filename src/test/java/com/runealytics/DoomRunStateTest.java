package com.runealytics;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for Doom of Mokhaiotl run state tracking.
 */
public class DoomRunStateTest
{
    private DoomRunState run;
    private List<ItemStack> sampleItems;

    @Before
    public void setUp()
    {
        run = new DoomRunState("TestAccount", 420);
        sampleItems = new ArrayList<>();
        sampleItems.add(new ItemStack(12345, 1));
        sampleItems.add(new ItemStack(54321, 100));
    }

    @Test
    public void testRunStartsInIdleState()
    {
        assertEquals("New run should be in IDLE state", DoomRunState.State.IDLE, run.getState());
        assertEquals("Highest floor should be 0", 0, run.getHighestFloorCompleted());
    }

    @Test
    public void testProgressionActivation()
    {
        run.startProgression();
        assertEquals("Should be in PROGRESSION_ACTIVE", DoomRunState.State.PROGRESSION_ACTIVE, run.getState());
        assertEquals("Current floor should be 1", 1, run.getCurrentFloor());
    }

    @Test
    public void testFloorCompletion()
    {
        run.startProgression();
        run.completeFloor(1);
        assertEquals("Should track highest floor 1", 1, run.getHighestFloorCompleted());

        run.completeFloor(2);
        assertEquals("Should track highest floor 2", 2, run.getHighestFloorCompleted());

        run.completeFloor(3);
        assertEquals("Should track highest floor 3", 3, run.getHighestFloorCompleted());
    }

    @Test
    public void testRewardClaim()
    {
        run.startProgression();
        run.completeFloor(2);
        run.claimReward(sampleItems);

        assertEquals("Should be in REWARD_CLAIMED", DoomRunState.State.REWARD_CLAIMED, run.getState());
        assertEquals("Should store items", 2, run.getClaimedItems().size());
        assertEquals("Should maintain highest floor", 2, run.getHighestFloorCompleted());
    }

    @Test
    public void testCompletion()
    {
        run.startProgression();
        run.completeFloor(4);
        run.claimReward(sampleItems);
        assertTrue("Claimed reward is ready to record", run.isReadyToRecord());

        run.complete();

        assertEquals("Should be in COMPLETED", DoomRunState.State.COMPLETED, run.getState());
        assertFalse("Completed runs have already been recorded", run.isReadyToRecord());
    }

    @Test
    public void testAbandonment()
    {
        run.startProgression();
        run.abandon("player death");

        assertEquals("Should be ABANDONED", DoomRunState.State.ABANDONED, run.getState());
        assertEquals("Should record reason", "player death", run.getAbandonReason());
    }

    @Test
    public void testStateTransitionInvariant()
    {
        // Cannot complete without being in REWARD_CLAIMED
        run.complete();
        assertNotEquals("Complete should not succeed from IDLE", DoomRunState.State.COMPLETED, run.getState());

        // Cannot claim reward without being in PROGRESSION_ACTIVE
        run.claimReward(sampleItems);
        assertNotEquals("Claim should not succeed from IDLE", DoomRunState.State.REWARD_CLAIMED, run.getState());
    }

    @Test
    public void testProgressionHistory()
    {
        run.startProgression();
        run.completeFloor(1);
        run.completeFloor(2);
        run.completeFloor(3);

        assertEquals("Should record 3 progressions", 3, run.getProgressionHistory().size());
        assertEquals("First progression should be floor 1", 1, run.getProgressionHistory().get(0).getFloor());
        assertEquals("Last progression should be floor 3", 3, run.getProgressionHistory().get(2).getFloor());
    }

    @Test
    public void testMultipleProgressionSameContinues()
    {
        run.startProgression();
        run.completeFloor(1);
        run.completeFloor(1);  // Repeat same floor (should not decrease highest)
        run.completeFloor(2);

        assertEquals("Highest should still be 2", 2, run.getHighestFloorCompleted());
        assertEquals("Should record all progressions", 3, run.getProgressionHistory().size());
    }

    @Test
    public void testReadyToRecord()
    {
        run.startProgression();
        run.completeFloor(2);
        run.claimReward(sampleItems);

        assertTrue("Should be ready after claim with items", run.isReadyToRecord());
    }

    @Test
    public void testNotReadyWithoutItems()
    {
        run.startProgression();
        run.completeFloor(2);
        run.claimReward(new ArrayList<>());  // Empty items

        assertFalse("Should not be ready with empty items", run.isReadyToRecord());
    }

    @Test
    public void testAccountIsolation()
    {
        DoomRunState run2 = new DoomRunState("OtherAccount", 420);

        run.startProgression();
        run.completeFloor(3);
        run2.startProgression();
        run2.completeFloor(1);

        assertEquals("Original run should have floor 3", 3, run.getHighestFloorCompleted());
        assertEquals("Other run should have floor 1", 1, run2.getHighestFloorCompleted());
        assertNotEquals("Runs should have different IDs", run.getRunId(), run2.getRunId());
    }
}
