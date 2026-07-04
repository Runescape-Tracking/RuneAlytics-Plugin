package com.runealytics;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for {@link DeathRecoveryGuard}. A deterministic clock is
 * injected via the test constructor so suppression-window boundaries can be
 * asserted exactly.
 */
public class DeathRecoveryGuardTest
{
    private static final long SUPPRESSION_MS = 5 * 60 * 1_000L;
    private static final long IDLE_EXTENSION_MS = 60_000L;
    private static final int DEATHS_OFFICE = 8543;
    private static final int DEATHS_OFFICE_ALT = 8799;

    private final long[] now = {1_000L};
    private final Client client = mock(Client.class);
    private final DeathRecoveryGuard guard = new DeathRecoveryGuard(client, () -> now[0]);

    private ChatMessage gameMessage(String text)
    {
        ChatMessage cm = mock(ChatMessage.class);
        when(cm.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
        when(cm.getMessage()).thenReturn(text);
        return cm;
    }

    private void standAtRegion(int regionId)
    {
        Player local = mock(Player.class);
        WorldPoint wp = mock(WorldPoint.class);
        when(wp.getRegionID()).thenReturn(regionId);
        when(local.getWorldLocation()).thenReturn(wp);
        when(client.getLocalPlayer()).thenReturn(local);
    }

    @Test
    public void freshGuard_doesNotSuppress()
    {
        assertFalse(guard.shouldSuppressLootEvent());
    }

    @Test
    public void deathChatMessage_startsSuppression()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));
        assertTrue(guard.shouldSuppressLootEvent());
    }

    @Test
    public void ironmanDeathMessage_withMarkup_isStrippedAndDetected()
    {
        guard.onChatMessage(gameMessage("<col=ff0000>You have died.</col>"));
        assertTrue(guard.shouldSuppressLootEvent());
    }

    @Test
    public void nonGameChat_isIgnored()
    {
        ChatMessage publicChat = mock(ChatMessage.class);
        when(publicChat.getType()).thenReturn(ChatMessageType.PUBLICCHAT);
        when(publicChat.getMessage()).thenReturn("oh dear, you are dead!");

        guard.onChatMessage(publicChat);
        assertFalse(guard.shouldSuppressLootEvent());
    }

    @Test
    public void justBeforeWindowExpiry_stillSuppresses()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));
        now[0] += SUPPRESSION_MS - 1;
        assertTrue(guard.shouldSuppressLootEvent());
    }

    @Test
    public void atWindowExpiry_outsideRegion_stopsSuppressing()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));
        now[0] += SUPPRESSION_MS;
        assertFalse(guard.shouldSuppressLootEvent());
    }

    @Test
    public void pastWindow_butRecentlyActiveInRegion_keepsSuppressing()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));

        now[0] += SUPPRESSION_MS + 5_000L;
        standAtRegion(DEATHS_OFFICE);
        guard.onGameTick(); // marks a fresh recovery interaction at `now`

        assertTrue(guard.shouldSuppressLootEvent());
    }

    @Test
    public void pastWindow_inRegionButIdleTooLong_stopsSuppressing()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));

        // Enter the region and interact while still inside the base window.
        now[0] += 10_000L;
        standAtRegion(DEATHS_OFFICE);
        guard.onGameTick();

        // Now drift well past the window AND past the idle-extension grace.
        now[0] += SUPPRESSION_MS + IDLE_EXTENSION_MS + 1;
        assertFalse(guard.shouldSuppressLootEvent());
    }

    @Test
    public void enteringDeathsOfficeWithoutDeathEvent_triggersSuppression()
    {
        standAtRegion(DEATHS_OFFICE_ALT);
        guard.onGameTick();
        assertTrue(guard.shouldSuppressLootEvent());
    }

    @Test
    public void localPlayerDeath_triggersSuppression()
    {
        Player local = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(local);
        ActorDeath event = mock(ActorDeath.class);
        when(event.getActor()).thenReturn(local);

        guard.onActorDeath(event);
        assertTrue(guard.shouldSuppressLootEvent());
    }

    @Test
    public void otherPlayerDeath_isIgnored()
    {
        Player local = mock(Player.class);
        Player other = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(local);
        ActorDeath event = mock(ActorDeath.class);
        when(event.getActor()).thenReturn(other);

        guard.onActorDeath(event);
        assertFalse(guard.shouldSuppressLootEvent());
    }

    @Test
    public void reset_clearsAllState()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));
        guard.recordIgnoredItem(4151, "Abyssal whip", 1, "recovery");

        guard.reset();

        assertFalse(guard.shouldSuppressLootEvent());
        assertEquals(0, guard.getSuppressedEventCount());
        assertTrue(guard.getIgnoredItems().isEmpty());
    }

    @Test
    public void endRecoveryMode_clearsSuppression()
    {
        guard.onChatMessage(gameMessage("Oh dear, you are dead!"));
        guard.endRecoveryMode();
        assertFalse(guard.shouldSuppressLootEvent());
    }

    @Test
    public void recordIgnoredItem_countsAndReturnsUnmodifiableSnapshot()
    {
        guard.recordIgnoredItem(4151, "Abyssal whip", 1, "recovery");
        guard.recordIgnoredItem(995, "Coins", 100, "recovery");

        assertEquals(2, guard.getSuppressedEventCount());
        assertEquals(2, guard.getIgnoredItems().size());

        try
        {
            guard.getIgnoredItems().add(null);
            org.junit.Assert.fail("expected snapshot to be unmodifiable");
        }
        catch (UnsupportedOperationException expected)
        {
            // snapshot is immutable, as intended
        }
    }
}
