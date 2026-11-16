package com.runealytics.slayer;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class SlayerTaskSyncTest
{
    private final Gson gson = new Gson();

    private static SlayerTaskEvent event()
    {
        return new SlayerTaskEvent(SlayerTaskEvent.ASSIGNED, 1_731_772_800_000L,
                new SlayerTaskTracker.Task(42, "Hydras", "Karuulm Slayer Dungeon", "Konar quo Maten", 45), 45, 45);
    }

    @Test
    public void savedStateSurvivesARestartWithTheSameIds()
    {
        SlayerTaskSync.Saved saved = new SlayerTaskSync.Saved();
        saved.task = new SlayerTaskTracker.Task(42, "Hydras", "Karuulm Slayer Dungeon", "Konar quo Maten", 30);
        saved.events = List.of(event());

        SlayerTaskSync.Saved back = gson.fromJson(gson.toJson(saved), SlayerTaskSync.Saved.class);
        assertEquals(saved.events.get(0).getEventUuid(), back.events.get(0).getEventUuid());
        assertEquals(1_731_772_800_000L, back.events.get(0).occurredAt);
        assertEquals(30, back.task.remaining);
        assertEquals("Konar quo Maten", back.task.master);
        assertEquals("slayer-rune_alytics.json", SlayerTaskSync.fileName("rune alytics"));
    }

    @Test
    public void readsTheServerAcks()
    {
        SlayerTaskSync sync = new SlayerTaskSync(null, null, null, null, gson, mock(java.util.concurrent.ScheduledExecutorService.class));
        SlayerTaskEvent a = event();
        SlayerTaskEvent b = event();
        String reply = "{\"status\":\"ok\",\"acks\":[{\"event_uuid\":\"" + a.getEventUuid() + "\",\"status\":\"accepted\"},"
                + "{\"event_uuid\":\"" + b.getEventUuid() + "\",\"status\":\"rejected\",\"reason\":\"in_future\"}]}";
        assertEquals(Set.of(a.getEventUuid(), b.getEventUuid()), sync.ackedIds(reply, List.of(a, b)));
        assertEquals("no acks in the reply: the whole batch counts as received",
                Set.of(a.getEventUuid()), sync.ackedIds("{\"status\":\"ok\"}", List.of(a)));
    }
}
