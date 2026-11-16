package com.runealytics.slayer;

import com.google.gson.Gson;
import com.runealytics.RuneAlyticsState;
import com.runealytics.RunealyticsConfig;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Only the player's own !task is picked up. */
public class SlayerTaskCommandTest
{
    private SlayerTaskSync sync;

    @Before
    public void setUp() throws Exception
    {
        Client client = mock(Client.class);
        Player me = mock(Player.class);
        when(me.getName()).thenReturn("Rune Alytics");
        when(client.getLocalPlayer()).thenReturn(me);
        RunealyticsConfig config = mock(RunealyticsConfig.class);
        when(config.shareSlayerTasks()).thenReturn(true);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
        sync = new SlayerTaskSync(client, config, mock(RuneAlyticsState.class), null, new Gson(), executor);
        sync.start(null);
        sync.onGameTick();      // loads (nothing saved)
        sync.onGameTick();      // account in place
    }

    private Object pending()
    {
        return sync.isWatchingTaskCommand() ? Boolean.TRUE : null;
    }

    private static ChatMessage chat(ChatMessageType type, String name, String text)
    {
        return new ChatMessage(mock(MessageNode.class), type, name, text, null, 0);
    }

    @Test
    public void someoneElsesTaskCommandIsIgnored() throws Exception
    {
        sync.onChatMessage(chat(ChatMessageType.PUBLICCHAT, "Zezima", "!task"));
        assertNull(pending());
        sync.onChatMessage(chat(ChatMessageType.PRIVATECHAT, "Rune Alytics", "!task"));
        assertNull("an incoming private message is never ours", pending());
    }

    @Test
    public void typedTaskLinesAreIgnored() throws Exception
    {
        sync.onChatMessage(chat(ChatMessageType.PUBLICCHAT, "Rune Alytics", "Slayer Task: Hydras: 1 left"));
        assertNull(pending());
    }

    @Test
    public void ownTaskCommandIsWatched() throws Exception
    {
        sync.onChatMessage(chat(ChatMessageType.PUBLICCHAT, "<img=2>Rune Alytics", "!task"));
        assertNotNull(pending());
    }
}
