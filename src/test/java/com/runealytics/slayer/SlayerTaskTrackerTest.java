package com.runealytics.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlayerTaskTrackerTest
{
    private final List<SlayerTaskEvent> sent = new ArrayList<>();
    private SlayerTaskTracker tracker;
    private long now;

    @Before
    public void setUp()
    {
        tracker = new SlayerTaskTracker(sent::add);
        now = 1_731_772_800_000L;
    }

    private static SlayerTaskTracker.Task task(String name, String location, int remaining)
    {
        return new SlayerTaskTracker.Task(name.hashCode() & 0xff, name, location, null, remaining);
    }

    private static SlayerTaskTracker.Task none()
    {
        return new SlayerTaskTracker.Task(0, null, null, null, 0);
    }

    private void observe(SlayerTaskTracker.Task t)
    {
        tracker.observe(t, now);
    }

    private void advance(long ms)
    {
        now += ms;
        tracker.tick(now);
    }

    private List<String> types()
    {
        List<String> out = new ArrayList<>();
        for (SlayerTaskEvent e : sent) out.add(e.type);
        return out;
    }

    private SlayerTaskEvent last()
    {
        return sent.get(sent.size() - 1);
    }

    /** A new task from none, held briefly for the master's dialogue. */
    private void assign(String name, String location, int amount, String master)
    {
        if (master != null)
        {
            tracker.onSignal(SlayerChatParser.parseDialog("Your new task is to kill " + amount + " " + name + "."), master, now);
        }
        observe(task(name, location, amount));
        advance(SlayerTaskTracker.ASSIGN_HOLD_MS);
    }

    @Test
    public void assignProgressExtendComplete()
    {
        assign("Hellhounds", null, 125, "Duradel");
        assertEquals(List.of("assigned"), types());
        SlayerTaskEvent a = last();
        assertEquals("Hellhounds", a.creatureName);
        assertEquals("Duradel", a.master);
        assertEquals(Integer.valueOf(125), a.amount);
        assertEquals(Integer.valueOf(125), a.remaining);

        advance(SlayerTaskTracker.PROGRESS_INTERVAL_MS);
        observe(task("Hellhounds", null, 124));
        assertEquals("progress", last().type);
        assertEquals(Integer.valueOf(124), last().remaining);

        observe(task("Hellhounds", null, 164));         // extension: 40 more
        assertEquals("extended", last().type);
        assertEquals(Integer.valueOf(40), last().amount);
        assertEquals(Integer.valueOf(164), last().remaining);

        observe(task("Hellhounds", null, 1));
        observe(none());
        tracker.onSignal(SlayerChatParser.parseChat("You have completed your task! You killed 164 Hellhounds."), null, now);
        assertEquals(List.of("assigned", "progress", "extended", "completed"), types());
        assertEquals(Integer.valueOf(0), last().remaining);
    }

    @Test
    public void assignThenCancel()
    {
        assign("Black demons", null, 150, null);
        observe(task("Black demons", null, 148));       // within 30 s: held
        tracker.onSignal(SlayerChatParser.parseChat("Your task has been cancelled."), null, now);
        observe(none());
        assertEquals(List.of("assigned", "progress", "cancelled"), types());
        assertEquals("latest count goes out before the task ends", Integer.valueOf(148), sent.get(1).remaining);
        assertEquals(Integer.valueOf(148), last().remaining);
    }

    @Test
    public void taskEndWithoutChatUsesTheLastCount()
    {
        assign("Nechryael", null, 10, null);
        observe(task("Nechryael", null, 2));
        observe(none());
        advance(SlayerTaskTracker.END_WAIT_MS);
        assertEquals("completed", last().type);

        sent.clear();
        assign("Gargoyles", null, 100, null);
        observe(none());                                 // dropped from 100 with no chat line: cancelled
        advance(SlayerTaskTracker.END_WAIT_MS);
        assertEquals("cancelled", last().type);
    }

    @Test
    public void newAssignmentOverAnUnfinishedTask()
    {
        assign("Kalphite", null, 120, null);
        observe(task("Kalphite", null, 90));             // held by the throttle
        observe(task("Cave crawlers", null, 40));        // Turael reset / new task
        advance(SlayerTaskTracker.ASSIGN_HOLD_MS);
        assertEquals(List.of("assigned", "progress", "assigned"), types());
        assertEquals(Integer.valueOf(90), sent.get(1).remaining);
        assertEquals("Cave crawlers", last().creatureName);
    }

    @Test
    public void konarLocationAndBraceletDoNotLookLikeNewTasks()
    {
        assign("Hydras", "Karuulm Slayer Dungeon", 45, "Konar quo Maten");
        assertEquals("Karuulm Slayer Dungeon", last().location);
        tracker.onSignal(SlayerChatParser.parseChat("Your bracelet of slaughter prevents your slayer count from decreasing."), null, now);
        observe(task("Hydras", "Karuulm Slayer Dungeon", 45));
        advance(SlayerTaskTracker.PROGRESS_INTERVAL_MS);
        assertEquals(List.of("assigned"), types());
    }

    @Test
    public void progressIsThrottledButTheLatestCountAlwaysGoesOut()
    {
        assign("Dust devils", null, 200, null);
        advance(SlayerTaskTracker.PROGRESS_INTERVAL_MS);
        for (int left = 199; left >= 180; left--)
        {
            observe(task("Dust devils", null, left));
            advance(1_000);
        }
        long progress = types().stream().filter("progress"::equals).count();
        assertEquals("one now, the rest wait for the 30 s window", 1, progress);

        advance(SlayerTaskTracker.PROGRESS_INTERVAL_MS);
        assertEquals(Integer.valueOf(180), last().remaining);
        assertEquals(2, types().stream().filter("progress"::equals).count());

        observe(task("Dust devils", null, 170));
        tracker.flush(now);                               // logout
        assertEquals(Integer.valueOf(170), last().remaining);
    }

    @Test
    public void loginCatchesTheServerUp()
    {
        tracker.restore(task("Hellhounds", null, 80));
        tracker.onLogin(task("Hellhounds", null, 60), now);
        assertEquals(List.of("progress"), types());
        assertEquals(Integer.valueOf(60), last().remaining);

        SlayerTaskTracker fresh = new SlayerTaskTracker(sent::add);   // installed mid-task
        SlayerTaskTracker.Task t = task("Wyrms", "Karuulm Slayer Dungeon", 33);
        t.master = "Konar quo Maten";
        fresh.onLogin(t, now);
        assertEquals("assigned", last().type);
        assertEquals(Integer.valueOf(33), last().amount);
        assertEquals("Konar quo Maten", last().master);
    }

    @Test
    public void eventJsonMatchesTheContract()
    {
        assign("Hydras", "Karuulm Slayer Dungeon", 45, "Konar quo Maten");
        JsonObject json = new Gson().toJsonTree(last()).getAsJsonObject();
        assertEquals(List.of("event_uuid", "type", "occurred_at", "creature_id", "creature_name", "master", "location",
                "amount", "remaining", "schema_version"), new ArrayList<>(json.keySet()));
        assertEquals("assigned", json.get("type").getAsString());
        assertEquals("epoch milliseconds", now - SlayerTaskTracker.ASSIGN_HOLD_MS, json.get("occurred_at").getAsLong());
        assertEquals(1, json.get("schema_version").getAsInt());
        assertTrue(json.get("event_uuid").getAsString().length() <= 64);

        observe(task("Hydras", "Karuulm Slayer Dungeon", 44));
        advance(SlayerTaskTracker.PROGRESS_INTERVAL_MS);
        JsonObject progress = new Gson().toJsonTree(last()).getAsJsonObject();
        assertFalse("unset fields are left out", progress.has("amount"));
        assertNotEquals(json.get("event_uuid"), progress.get("event_uuid"));
        assertNull(SlayerTaskReader.master(3));
        assertEquals("Krystilia", SlayerTaskReader.master(7));
    }

    @Test
    public void taskCommandSendsTheCountNowDespiteTheThrottle()
    {
        assign("Hydras", "Karuulm Slayer Dungeon", 45, null);
        observe(task("Hydras", "Karuulm Slayer Dungeon", 40));          // held: within 30 s
        assertEquals(List.of("assigned"), types());

        tracker.confirm(SlayerChatParser.parseTaskCommand("Slayer Task: Hydras (Karuulm Slayer Dungeon): 40 left"), now);
        assertEquals(List.of("assigned", "progress"), types());
        assertEquals(Integer.valueOf(40), last().remaining);

        tracker.confirm(SlayerChatParser.parseTaskCommand("Slayer Task: Abyssal demons: 10 left"), now);
        assertEquals("a reply for a different task is ignored", 2, sent.size());
        tracker.confirm(SlayerChatParser.parseTaskCommand("Slayer Task: Hydras (Karuulm Slayer Dungeon): 99 left"), now);
        assertEquals("the varps' lower count wins", Integer.valueOf(40), last().remaining);
    }
}
