package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the small matchmaking DTOs, in particular the deliberate
 * difference in null-string handling between {@link MatchmakingApiResult}
 * (normalizes to "") and {@link MatchmakingUpdate} (passes through).
 */
public class MatchmakingDtoTest
{
    @Test
    public void apiResult_normalizesNullStringsToEmpty()
    {
        MatchmakingApiResult result = new MatchmakingApiResult(null, null, null, false, false);
        assertEquals("", result.getMessage());
        assertEquals("", result.getRawResponse());
        assertNull(result.getSession());
        assertFalse(result.isSuccess());
        assertFalse(result.isTokenRefresh());
    }

    @Test
    public void apiResult_retainsProvidedValues()
    {
        MatchmakingApiResult result = new MatchmakingApiResult(null, "ok", "{}", true, true);
        assertEquals("ok", result.getMessage());
        assertEquals("{}", result.getRawResponse());
        assertTrue(result.isSuccess());
        assertTrue(result.isTokenRefresh());
    }

    @Test
    public void update_passesThroughNullStrings()
    {
        MatchmakingUpdate update = new MatchmakingUpdate(null, null, null, true, false);
        assertNull(update.getMessage());
        assertNull(update.getRawResponse());
        assertTrue(update.isSuccess());
        assertFalse(update.isTokenRefresh());
    }

    @Test
    public void rally_exposesCoordinatesIncludingEdgeValues()
    {
        MatchmakingRally rally = new MatchmakingRally(-3, 0, 3);
        assertEquals(-3, rally.getX());
        assertEquals(0, rally.getY());
        assertEquals(3, rally.getPlane());
    }

    @Test
    public void winner_retainsFieldsAndAllowsNullRsn()
    {
        MatchmakingWinner winner = new MatchmakingWinner(null, 126, -50);
        assertNull(winner.getOsrsRsn());
        assertEquals(126, winner.getCombatLevel());
        assertEquals(-50, winner.getElo());

        MatchmakingWinner named = new MatchmakingWinner("Zezima", 3, 1500);
        assertEquals("Zezima", named.getOsrsRsn());
    }

    @Test
    public void apiResult_carriesSessionReference()
    {
        MatchmakingSession session = new MatchmakingSession(
                "CODE", "A", "A", "B", true, true, false, false,
                301, "Edge", "Ready", "any", null, null, "tok", "later");
        MatchmakingApiResult result = new MatchmakingApiResult(session, "m", "r", true, false);
        assertSame(session, result.getSession());
    }
}
