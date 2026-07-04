package com.runealytics;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the 30-second XP batching window: filtering, per-skill merge,
 * single-schedule-per-window, non-draining peek, and immediate flush. The
 * scheduled executor is mocked so the flush runnable can be triggered on demand.
 */
public class XpTrackerManagerTest
{
    private RunealyticsApiClient apiClient;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private XpTrackerManager manager;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp()
    {
        apiClient = mock(RunealyticsApiClient.class);
        executor = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> future);
        manager = new XpTrackerManager(apiClient, executor);
    }

    private Runnable captureScheduledFlush()
    {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(captor.capture(), eq(30L), eq(TimeUnit.SECONDS));
        return captor.getValue();
    }

    @Test
    public void onXpGained_ignoresNonPositiveAndOverall()
    {
        manager.onXpGained(Skill.ATTACK, 0);
        manager.onXpGained(Skill.ATTACK, -5);
        manager.onXpGained(Skill.OVERALL, 100);

        assertTrue(manager.peekPendingGains().isEmpty());
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void onXpGained_mergesPerSkillAndSchedulesWindowOnce()
    {
        manager.onXpGained(Skill.ATTACK, 10);
        manager.onXpGained(Skill.ATTACK, 5);
        manager.onXpGained(Skill.WOODCUTTING, 50);

        Map<String, Integer> pending = manager.peekPendingGains();
        assertEquals(Integer.valueOf(15), pending.get("attack"));
        assertEquals(Integer.valueOf(50), pending.get("woodcutting"));

        // Only the first gain opens a window / schedules a flush.
        verify(executor, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void peekPendingGains_doesNotDrainBuffer()
    {
        manager.onXpGained(Skill.FISHING, 42);
        assertEquals(Integer.valueOf(42), manager.peekPendingGains().get("fishing"));
        // A second peek still sees the data.
        assertEquals(Integer.valueOf(42), manager.peekPendingGains().get("fishing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void scheduledFlush_sendsBatchAndResetsWindow()
    {
        manager.onXpGained(Skill.ATTACK, 10);
        manager.onXpGained(Skill.STRENGTH, 20);
        Runnable flush = captureScheduledFlush();

        flush.run();

        ArgumentCaptor<Map<String, Integer>> payload = ArgumentCaptor.forClass(Map.class);
        verify(apiClient).syncXpBatch(payload.capture());
        assertEquals(Integer.valueOf(10), payload.getValue().get("attack"));
        assertEquals(Integer.valueOf(20), payload.getValue().get("strength"));

        // Buffer drained after flush.
        assertTrue(manager.peekPendingGains().isEmpty());

        // Window reset: the next gain schedules a fresh window.
        manager.onXpGained(Skill.MAGIC, 5);
        verify(executor, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void flushImmediate_cancelsPendingTaskAndSends()
    {
        manager.onXpGained(Skill.ATTACK, 7);
        when(future.isDone()).thenReturn(false);

        manager.flushImmediate();

        verify(future).cancel(false);
        verify(apiClient).syncXpBatch(any());
        assertTrue(manager.peekPendingGains().isEmpty());
    }

    @Test
    public void flushImmediate_emptyBuffer_doesNotCallApi()
    {
        manager.flushImmediate();
        verify(apiClient, never()).syncXpBatch(any());
    }
}
