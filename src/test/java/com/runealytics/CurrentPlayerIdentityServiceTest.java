package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.Player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for {@link CurrentPlayerIdentityService}: username
 * normalization (must match the PHP server) and the account-mismatch guard.
 */
public class CurrentPlayerIdentityServiceTest
{
    private final Client client = mock(Client.class);
    private final RuneAlyticsState state = new RuneAlyticsState();
    private final CurrentPlayerIdentityService service =
            new CurrentPlayerIdentityService(client, state);

    private void loggedInAs(String displayName)
    {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(displayName);
        when(client.getLocalPlayer()).thenReturn(player);
    }

    // ── normalizeUsername ──────────────────────────────────────────────────

    @Test
    public void normalize_null_returnsNull()
    {
        assertNull(CurrentPlayerIdentityService.normalizeUsername(null));
    }

    @Test
    public void normalize_whitespaceOnly_returnsNull()
    {
        assertNull(CurrentPlayerIdentityService.normalizeUsername("   "));
    }

    @Test
    public void normalize_nonBreakingSpaces_areTreatedAsSpaces()
    {
        assertEquals("zezima", CurrentPlayerIdentityService.normalizeUsername("\u00A0Zezima\u00A0"));
    }

    @Test
    public void normalize_collapsesInternalWhitespace()
    {
        assertEquals("iron man", CurrentPlayerIdentityService.normalizeUsername("Iron  Man"));
    }

    @Test
    public void normalize_lowercasesEverything()
    {
        assertEquals("zezima", CurrentPlayerIdentityService.normalizeUsername("ZEZIMA"));
    }

    // ── canSync / identity ─────────────────────────────────────────────────

    @Test
    public void canSync_noLocalPlayer_isBlocked()
    {
        when(client.getLocalPlayer()).thenReturn(null);
        assertFalse(service.canSync());
        assertNull(service.getAccountKey());
    }

    @Test
    public void canSync_emptyPlayerName_isBlocked()
    {
        loggedInAs("");
        assertFalse(service.canSync());
    }

    @Test
    public void canSync_noLinkedAccount_isAllowed()
    {
        loggedInAs("Zezima");
        state.setVerifiedUsername(null);
        assertTrue(service.canSync());
        assertEquals("zezima", service.getAccountKey());
    }

    @Test
    public void canSync_matchingLinkedAccount_isAllowed()
    {
        loggedInAs("Zezima");
        state.setVerifiedUsername("zezima");
        assertTrue(service.canSync());
    }

    @Test
    public void canSync_mismatchedLinkedAccount_isBlocked()
    {
        loggedInAs("IronMain");
        state.setVerifiedUsername("Chris123");
        assertFalse(service.canSync());
    }

    // ── getMismatchMessage ─────────────────────────────────────────────────

    @Test
    public void mismatchMessage_notLoggedIn_promptsLogin()
    {
        when(client.getLocalPlayer()).thenReturn(null);
        assertTrue(service.getMismatchMessage().toLowerCase().contains("log into"));
    }

    @Test
    public void mismatchMessage_conflict_namesBothAccounts()
    {
        loggedInAs("IronMain");
        state.setVerifiedUsername("Chris123");

        String msg = service.getMismatchMessage();
        assertTrue(msg.contains("chris123"));
        assertTrue(msg.contains("ironmain"));
    }

    @Test
    public void mismatchMessage_whenAllowed_isNull()
    {
        loggedInAs("Zezima");
        state.setVerifiedUsername("zezima");
        assertNull(service.getMismatchMessage());
    }

    // ── isLinkedAccount ────────────────────────────────────────────────────

    @Test
    public void isLinkedAccount_null_isFalse()
    {
        assertFalse(service.isLinkedAccount(null));
    }

    @Test
    public void isLinkedAccount_noLinkedUser_isTrue()
    {
        state.setVerifiedUsername(null);
        assertTrue(service.isLinkedAccount("anyone"));
    }

    @Test
    public void isLinkedAccount_matches_isTrue()
    {
        state.setVerifiedUsername("zezima");
        assertTrue(service.isLinkedAccount("zezima"));
    }

    @Test
    public void isLinkedAccount_differs_isFalse()
    {
        state.setVerifiedUsername("zezima");
        assertFalse(service.isLinkedAccount("someone_else"));
    }
}
