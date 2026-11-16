package com.runealytics.slayer;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlayerEventOutboxTest
{
    private static SlayerTaskEvent event()
    {
        return new SlayerTaskEvent(SlayerTaskEvent.PROGRESS, 1L,
                new SlayerTaskTracker.Task(1, "Hellhounds", null, null, 5), null, 5);
    }

    @Test
    public void batchesOfFiftyAndRetriesKeepTheSameIds()
    {
        SlayerEventOutbox box = new SlayerEventOutbox();
        for (int i = 0; i < 60; i++) box.add(event());

        List<SlayerTaskEvent> first = box.take(0);
        assertEquals(50, first.size());
        assertNull("one batch in flight at a time", box.take(0));

        box.failed(1_000, new Random(1));
        assertNull("backing off", box.take(2_000));
        List<SlayerTaskEvent> retry = box.take(1_000 + SlayerEventOutbox.BACKOFF_MAX_MS);
        assertEquals("same events, same UUIDs", first.get(0).getEventUuid(), retry.get(0).getEventUuid());

        Set<String> acked = new HashSet<>();
        for (SlayerTaskEvent e : retry) acked.add(e.getEventUuid());
        box.acknowledged(acked);
        assertEquals(10, box.take(Long.MAX_VALUE).size());
    }

    @Test
    public void unansweredEventsStayQueued()
    {
        SlayerEventOutbox box = new SlayerEventOutbox();
        box.add(event());
        box.add(event());
        List<SlayerTaskEvent> batch = box.take(0);
        box.acknowledged(Set.of(batch.get(0).getEventUuid()));
        assertEquals(batch.get(1).getEventUuid(), box.take(0).get(0).getEventUuid());

        box.dropInflight();
        assertTrue(box.isEmpty());
    }
}
