package com.runealytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge-case coverage for the shared session state machine: the sync gate
 * (canSync/tryStartSync/endSync), defensive copying of the visible-players
 * list, and the reset contract.
 */
public class RuneAlyticsStateTest
{
    private final RuneAlyticsState state = new RuneAlyticsState();

    @Test
    public void defaults_areRegularAndNormalWithEmptyPlayers()
    {
        assertEquals("regular", state.getCurrentGameMode());
        assertEquals("normal", state.getCurrentAccountSubtype());
        assertTrue(state.getVisibleMapPlayers().isEmpty());
        assertFalse(state.isLoggedIn());
        assertFalse(state.isVerified());
        assertFalse(state.isSyncInProgress());
    }

    @Test
    public void canSync_requiresLoggedInVerifiedAndNotInProgress()
    {
        assertFalse(state.canSync());

        state.setLoggedIn(true);
        assertFalse(state.canSync());

        state.setVerified(true);
        assertTrue(state.canSync());

        state.setSyncInProgress(true);
        assertFalse(state.canSync());
    }

    @Test
    public void tryStartSync_claimsSlotOnlyOnce()
    {
        state.setLoggedIn(true);
        state.setVerified(true);

        assertTrue(state.tryStartSync());
        assertTrue(state.isSyncInProgress());
        assertTrue(state.getLastSyncTime() > 0);

        // Second concurrent attempt must fail while the first holds the slot.
        assertFalse(state.tryStartSync());

        state.endSync();
        assertFalse(state.isSyncInProgress());
        assertTrue(state.tryStartSync());
    }

    @Test
    public void tryStartSync_failsWhenNotLoggedInOrNotVerified()
    {
        state.setVerified(true);
        assertFalse(state.tryStartSync());

        state.setVerified(false);
        state.setLoggedIn(true);
        assertFalse(state.tryStartSync());
    }

    @Test
    public void setVisibleMapPlayers_nullOrEmptyBecomesEmptyList()
    {
        state.setVisibleMapPlayers(null);
        assertTrue(state.getVisibleMapPlayers().isEmpty());

        state.setVisibleMapPlayers(Collections.emptyList());
        assertTrue(state.getVisibleMapPlayers().isEmpty());
    }

    @Test
    public void setVisibleMapPlayers_copiesDefensivelyAndIsUnmodifiable()
    {
        List<MapPlayer> source = new ArrayList<>(Arrays.asList(
                new MapPlayer("Zezima", 3200, 3200, 0, 301)));
        state.setVisibleMapPlayers(source);

        // Mutating the source after the call must not affect stored state.
        source.clear();
        assertEquals(1, state.getVisibleMapPlayers().size());

        try
        {
            state.getVisibleMapPlayers().add(new MapPlayer());
            fail("stored list should be unmodifiable");
        }
        catch (UnsupportedOperationException expected)
        {
            // expected
        }
    }

    @Test
    public void reset_restoresDefaultsButLeavesPrestige()
    {
        state.setLoggedIn(true);
        state.setVerified(true);
        state.setVerifiedUsername("Zezima");
        state.setVerificationCode("ABC123");
        state.setSyncInProgress(true);
        state.setLastSyncTime(999L);
        state.setCurrentGameMode("ironman");
        state.setCurrentAccountSubtype("hardcore_ironman");
        state.setCurrentLocation(new PlayerLocationSnapshot());
        state.setVisibleMapPlayers(Arrays.asList(new MapPlayer()));
        state.setPrestige(5);

        state.reset();

        assertFalse(state.isLoggedIn());
        assertFalse(state.isVerified());
        assertNull(state.getVerifiedUsername());
        assertNull(state.getVerificationCode());
        assertFalse(state.isSyncInProgress());
        assertEquals(0L, state.getLastSyncTime());
        assertEquals("regular", state.getCurrentGameMode());
        assertEquals("normal", state.getCurrentAccountSubtype());
        assertNull(state.getCurrentLocation());
        assertTrue(state.getVisibleMapPlayers().isEmpty());

        // reset() intentionally does not clear prestige.
        assertEquals(5, state.getPrestige());
    }

    @Test
    public void endSync_isIdempotentWithoutPriorStart()
    {
        state.endSync();
        assertFalse(state.isSyncInProgress());
        assertNotNull(state.getVisibleMapPlayers());
    }
}
