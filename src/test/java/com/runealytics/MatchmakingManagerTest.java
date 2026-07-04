package com.runealytics;

import com.google.gson.JsonArray;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural coverage for the matchmaking state machine: credential
 * pre-flight, executor result handling, server-driven token refresh, death
 * attribution guards, reset, and per-tick minimap targeting. The executor is
 * stubbed to run submitted tasks inline so the async paths are deterministic,
 * and listener notifications (posted via {@code invokeLater}) are asserted
 * after flushing the EDT.
 */
public class MatchmakingManagerTest
{
    private Client client;
    private RuneAlyticsState state;
    private MatchmakingApiClient apiClient;
    private ScheduledExecutorService executor;
    private MatchmakingManager mgr;

    private final List<MatchmakingUpdate> updates = new ArrayList<>();

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        state = mock(RuneAlyticsState.class);
        apiClient = mock(MatchmakingApiClient.class);
        executor = mock(ScheduledExecutorService.class);

        // Run every submitted task inline so async result handling is deterministic.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).submit(any(Runnable.class));

        mgr = new MatchmakingManager(client, state, apiClient, executor);
        mgr.setListener(updates::add);
    }

    private static void flushEdt() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static MatchmakingSession session(String matchCode, String status, String token,
                                              boolean localJoined, MatchmakingRally rally)
    {
        // Local player is "Me" (player1); opponent is "Foe" (player2).
        return new MatchmakingSession(
                matchCode, "Me", "Me", "Foe",
                localJoined, true, false, false,
                301, "Edge", status, "any",
                rally, null, token, "later");
    }

    private void haveCredentials()
    {
        when(state.getVerificationCode()).thenReturn("VC");
        when(state.getVerifiedUsername()).thenReturn("Me");
    }

    private void stubGetMatchReturns(MatchmakingApiResult result) throws IOException
    {
        when(apiClient.getMatch(anyString(), anyString(), anyString(),
                nullable(JsonArray.class), nullable(JsonArray.class),
                anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(result);
    }

    /** Installs an active session via a successful loadMatch, then clears mock call counts. */
    private void installActiveSession(MatchmakingSession s) throws Exception
    {
        haveCredentials();
        stubGetMatchReturns(new MatchmakingApiResult(s, "", "", true, false));
        assertTrue(mgr.loadMatch(s.getMatchCode()));
        flushEdt();
        assertSame(s, mgr.getSession());
        clearInvocations(apiClient);
        updates.clear();
    }

    // ── loadMatch pre-flight ──────────────────────────────────────────────────

    @Test
    public void loadMatch_missingVerification_notifiesErrorAndDoesNotCallServer() throws Exception
    {
        when(state.getVerificationCode()).thenReturn(null);
        when(state.getVerifiedUsername()).thenReturn("Me");

        assertFalse(mgr.loadMatch("ABCD"));
        flushEdt();

        verify(apiClient, never()).getMatch(anyString(), anyString(), anyString(),
                any(), any(), anyInt(), anyBoolean(), anyBoolean());
        assertEquals(1, updates.size());
        assertFalse(updates.get(0).isSuccess());
        assertTrue(updates.get(0).getMessage().contains("Missing verification"));
        assertFalse(mgr.hasActiveMatch());
    }

    @Test
    public void loadMatch_missingRsn_notifiesErrorAndReturnsFalse() throws Exception
    {
        when(state.getVerificationCode()).thenReturn("VC");
        when(state.getVerifiedUsername()).thenReturn("");
        when(client.getLocalPlayer()).thenReturn(null);

        assertFalse(mgr.loadMatch("ABCD"));
        flushEdt();

        verify(apiClient, never()).getMatch(anyString(), anyString(), anyString(),
                any(), any(), anyInt(), anyBoolean(), anyBoolean());
        assertEquals(1, updates.size());
        assertFalse(updates.get(0).isSuccess());
    }

    // ── loadMatch result handling ─────────────────────────────────────────────

    @Test
    public void loadMatch_success_setsSessionAndCallsServerWithResolvedIdentity() throws Exception
    {
        haveCredentials();
        MatchmakingSession s = session("ABCD", "Pending", "T", false, null);
        stubGetMatchReturns(new MatchmakingApiResult(s, "", "", true, false));

        assertTrue(mgr.loadMatch("ABCD"));
        flushEdt();

        assertTrue(mgr.hasActiveMatch());
        assertSame(s, mgr.getSession());
        verify(apiClient, times(1)).getMatch(eq("VC"), eq("ABCD"), eq("Me"),
                nullable(JsonArray.class), nullable(JsonArray.class),
                eq(-1), eq(false), eq(false));
    }

    @Test
    public void loadMatch_ioError_leavesSessionNullAndNotifiesFailure() throws Exception
    {
        haveCredentials();
        when(apiClient.getMatch(anyString(), anyString(), anyString(),
                nullable(JsonArray.class), nullable(JsonArray.class),
                anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new IOException("boom"));

        assertTrue(mgr.loadMatch("ABCD"));
        flushEdt();

        assertNull(mgr.getSession());
        assertFalse(mgr.hasActiveMatch());
        assertEquals(1, updates.size());
        assertFalse(updates.get(0).isSuccess());
        assertEquals("boom", updates.get(0).getMessage());
    }

    @Test
    public void tokenRefreshResult_triggersRefreshFetchAndAdoptsRefreshedSession() throws Exception
    {
        // A token refresh only fires once a session is already active, because
        // refreshToken() bails while session is null.
        installActiveSession(session("ABCD", "Fighting", "T", true, null));

        MatchmakingSession refreshed = session("ABCD", "Fighting", "T2", true, null);
        when(apiClient.reportMatch(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MatchmakingApiResult(null, "refresh", "", false, true));
        stubGetMatchReturns(new MatchmakingApiResult(refreshed, "", "", true, false));

        mgr.onActorDeath(playerNamed("Foe"));
        flushEdt();

        // The refresh path issues exactly one follow-up getMatch and adopts its session.
        verify(apiClient, times(1)).getMatch(anyString(), anyString(), anyString(),
                nullable(JsonArray.class), nullable(JsonArray.class),
                anyInt(), anyBoolean(), anyBoolean());
        assertSame(refreshed, mgr.getSession());
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    public void reset_clearsActiveSession() throws Exception
    {
        installActiveSession(session("ABCD", "Pending", "T", false, null));

        mgr.reset();

        assertNull(mgr.getSession());
        assertFalse(mgr.hasActiveMatch());
    }

    // ── onActorDeath guards + reporting ────────────────────────────────────────

    @Test
    public void onActorDeath_noSession_doesNothing() throws Exception
    {
        mgr.onActorDeath(playerNamed("Foe"));
        flushEdt();
        verify(apiClient, never()).reportMatch(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    public void onActorDeath_nonParticipant_isIgnored() throws Exception
    {
        installActiveSession(session("ABCD", "Fighting", "T", true, null));

        mgr.onActorDeath(playerNamed("Stranger"));
        flushEdt();

        verify(apiClient, never()).reportMatch(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    public void onActorDeath_nullName_isIgnored() throws Exception
    {
        installActiveSession(session("ABCD", "Fighting", "T", true, null));

        mgr.onActorDeath(playerNamed(null));
        flushEdt();

        verify(apiClient, never()).reportMatch(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    public void onActorDeath_missingToken_isIgnored() throws Exception
    {
        installActiveSession(session("ABCD", "Fighting", null, true, null));

        mgr.onActorDeath(playerNamed("Foe"));
        flushEdt();

        verify(apiClient, never()).reportMatch(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    public void onActorDeath_participant_reportsNormalizedDeath() throws Exception
    {
        installActiveSession(session("ABCD", "Fighting", "T", true, null));
        when(apiClient.reportMatch(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MatchmakingApiResult(null, "", "", true, false));

        // RuneLite delivers names with a non-breaking space; it must be normalized.
        mgr.onActorDeath(playerNamed("Foe"));
        flushEdt();

        verify(apiClient, times(1)).reportMatch("VC", "ABCD", "Me", "T", "Foe");
    }

    // ── minimap targeting ──────────────────────────────────────────────────────

    @Test
    public void onGameTick_fighting_targetsOpponentWorldLocation() throws Exception
    {
        installActiveSession(session("M", "Fighting", null, true, null));

        Player opponent = playerNamed("Foe");
        WorldPoint opponentLoc = new WorldPoint(3200, 3200, 0);
        when(opponent.getWorldLocation()).thenReturn(opponentLoc);
        when(client.getPlayers()).thenReturn(Collections.singletonList(opponent));

        mgr.onGameTick();

        assertEquals(opponentLoc, mgr.getMinimapTarget());
    }

    @Test
    public void onGameTick_fighting_noOpponentRendered_fallsBackToRally() throws Exception
    {
        installActiveSession(session("M", "Fighting", null, true,
                new MatchmakingRally(1500, 1600, 0)));
        when(client.getPlayers()).thenReturn(Collections.emptyList());

        mgr.onGameTick();

        assertEquals(new WorldPoint(1500, 1600, 0), mgr.getMinimapTarget());
    }

    @Test
    public void onGameTick_completed_clearsMinimapTarget() throws Exception
    {
        installActiveSession(session("M", "Completed", null, true,
                new MatchmakingRally(1500, 1600, 0)));
        when(client.getPlayers()).thenReturn(Collections.emptyList());

        mgr.onGameTick();

        assertNull(mgr.getMinimapTarget());
    }

    private static Player playerNamed(String name)
    {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        return p;
    }
}
