package com.runealytics.slayer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Turns Slayer task readings (varps, via {@link SlayerTaskReader}) and chat /
 * dialogue signals ({@link SlayerChatParser}) into server events:
 * <ul>
 *   <li><b>assigned</b>: a different task appears, or one appears from none.</li>
 *   <li><b>progress</b>: remaining went down. At most one per {@link #PROGRESS_INTERVAL_MS}
 *       per task; the latest count is always sent before a task ends, on logout and
 *       on login.</li>
 *   <li><b>extended</b>: same task, remaining went up ({@code amount} = kills added).</li>
 *   <li><b>completed</b> / <b>cancelled</b>: the task count dropped to 0. Chat decides
 *       which; without a matching line within {@link #END_WAIT_MS}, a count of 2 or
 *       less (a last kill, even with an expeditious bracelet) means completed.</li>
 * </ul>
 * A new assignment is held for {@link #ASSIGN_HOLD_MS} so the master's dialogue
 * (name, location wording) can be attached. Not thread-safe: client thread only.
 */
public final class SlayerTaskTracker
{
    static final long PROGRESS_INTERVAL_MS = 30_000L;
    static final long END_WAIT_MS = 5_000L;
    static final long ASSIGN_HOLD_MS = 1_800L;
    /** How long a chat line / dialogue stays relevant to a varp change. */
    static final long SIGNAL_WINDOW_MS = 15_000L;

    /** The current task as the tracker knows it (also persisted). */
    public static final class Task
    {
        int creatureId;
        String name;
        String location;
        String master;
        int remaining;
        /** Remaining count last sent to the server. */
        int sentRemaining;
        long lastProgressMs;

        public Task(int creatureId, String name, String location, String master, int remaining)
        {
            this.creatureId = creatureId;
            this.name = name;
            this.location = location;
            this.master = master;
            this.remaining = remaining;
            this.sentRemaining = remaining;
        }

        boolean sameTask(Task o)
        {
            return o != null && creatureId == o.creatureId && Objects.equals(location, o.location)
                    && (name == null || o.name == null || name.equalsIgnoreCase(o.name));
        }
    }

    private final Consumer<SlayerTaskEvent> sink;
    private Task task;
    private Task ending;
    private long endingAtMs;
    private Task assigning;
    private long assigningAtMs;
    private long completedSeenMs = Long.MIN_VALUE / 2;
    private long cancelledSeenMs = Long.MIN_VALUE / 2;
    private SlayerChatParser.Signal dialog;
    private String dialogMaster;
    private long dialogAtMs = Long.MIN_VALUE / 2;

    public SlayerTaskTracker(Consumer<SlayerTaskEvent> sink)
    {
        this.sink = sink;
    }

    /** The task to persist ({@code null} when none). */
    public Task getTask()
    {
        return assigning != null ? assigning : task;
    }

    /** Restores the persisted task for this account (before {@link #onLogin}). */
    public void restore(Task saved)
    {
        task = saved;
        ending = null;
        assigning = null;
    }

    /**
     * First reading after login: catches the server up with one progress event,
     * or an "assigned" when this task was never seen here (installed mid-task).
     */
    public void onLogin(Task now, long nowMs)
    {
        if (now == null || now.remaining <= 0)
        {
            task = null;
            return;
        }
        if (task != null && task.sameTask(now))
        {
            now.master = now.master != null ? now.master : task.master;
            task = now;
            progress(nowMs);
        }
        else
        {
            task = now;
            emit(SlayerTaskEvent.ASSIGNED, nowMs, now, now.remaining, now.remaining);
            now.lastProgressMs = nowMs;
        }
    }

    /** A varp reading ({@code remaining == 0} = no task). */
    public void observe(Task now, long nowMs)
    {
        if (now == null) return;
        Task current = getTask();
        if (now.remaining <= 0)
        {
            if (current == null) return;
            flushAssign();
            ending = task;
            endingAtMs = nowMs;
            task = null;
            if (nowMs - cancelledSeenMs <= SIGNAL_WINDOW_MS || nowMs - completedSeenMs <= SIGNAL_WINDOW_MS) resolveEnd(nowMs);
            return;
        }
        if (current == null || !current.sameTask(now))
        {
            if (ending != null) resolveEnd(nowMs);
            if (current != null)
            {
                flushAssign();
                sendLatest(task, nowMs);      // replaced: last count of the old task first
            }
            if (dialog != null && nowMs - dialogAtMs <= SIGNAL_WINDOW_MS)
            {
                applyDialog(now);
            }
            assigning = now;
            assigningAtMs = nowMs;
            task = null;
            return;
        }
        if (now.remaining > current.remaining)
        {
            flushAssign();
            int added = now.remaining - task.remaining;
            sendLatest(task, nowMs);
            task.remaining = now.remaining;
            task.sentRemaining = now.remaining;
            emit(SlayerTaskEvent.EXTENDED, nowMs, task, added, now.remaining);
        }
        else if (now.remaining < current.remaining)
        {
            current.remaining = now.remaining;
            if (task == current) maybeProgress(nowMs);
            else current.sentRemaining = now.remaining;   // still held: the assignment carries it
        }
    }

    /** A parsed chat / dialogue line; {@code master} is the dialogue speaker, if any. */
    public void onSignal(SlayerChatParser.Signal s, String master, long nowMs)
    {
        if (s == null) return;
        switch (s.kind)
        {
            case COMPLETED:
                completedSeenMs = nowMs;
                if (ending != null) resolveEnd(nowMs);
                break;
            case CANCELLED:
                cancelledSeenMs = nowMs;
                if (ending != null) resolveEnd(nowMs);
                break;
            case ASSIGNED:
                dialog = s;
                dialogMaster = master;
                dialogAtMs = nowMs;
                if (assigning != null) applyDialog(assigning);
                break;
            case CURRENT:
            case REMAINING:
                // Backup for the varps: a confirmed lower count is progress.
                if (task != null && s.amount >= 0 && s.amount < task.remaining)
                {
                    task.remaining = s.amount;
                    maybeProgress(nowMs);
                }
                break;
            default:
                break;
        }
    }

    /** Once per game tick: due progress, held assignment, undecided task end. */
    public void tick(long nowMs)
    {
        if (assigning != null && nowMs - assigningAtMs >= ASSIGN_HOLD_MS) flushAssign();
        if (task != null && task.sentRemaining != task.remaining && nowMs - task.lastProgressMs >= PROGRESS_INTERVAL_MS)
        {
            progress(nowMs);
        }
        if (ending != null && nowMs - endingAtMs >= END_WAIT_MS) resolveEnd(nowMs);
    }

    /** Logout / plugin stop: everything pending goes out now. */
    public void flush(long nowMs)
    {
        flushAssign();
        if (ending != null) resolveEnd(nowMs);
        sendLatest(task, nowMs);
    }

    private void applyDialog(Task t)
    {
        if (dialogMaster != null) t.master = dialogMaster;
    }

    private void flushAssign()
    {
        if (assigning == null) return;
        Task t = assigning;
        assigning = null;
        task = t;
        int amount = dialog != null && assigningAtMs - dialogAtMs <= SIGNAL_WINDOW_MS && dialog.amount >= t.remaining
                ? dialog.amount : t.remaining;
        emit(SlayerTaskEvent.ASSIGNED, assigningAtMs, t, amount, t.remaining);
        t.sentRemaining = t.remaining;
        t.lastProgressMs = assigningAtMs;
        dialog = null;
    }

    private void resolveEnd(long nowMs)
    {
        Task t = ending;
        ending = null;
        if (t == null) return;
        boolean cancelled = nowMs - cancelledSeenMs <= SIGNAL_WINDOW_MS
                || (nowMs - completedSeenMs > SIGNAL_WINDOW_MS && t.remaining > 2);
        if (cancelled)
        {
            sendLatest(t, nowMs);
            emit(SlayerTaskEvent.CANCELLED, nowMs, t, null, t.remaining);
        }
        else
        {
            emit(SlayerTaskEvent.COMPLETED, nowMs, t, null, 0);
        }
        completedSeenMs = cancelledSeenMs = Long.MIN_VALUE / 2;
    }

    private void maybeProgress(long nowMs)
    {
        if (nowMs - task.lastProgressMs >= PROGRESS_INTERVAL_MS) progress(nowMs);
    }

    private void sendLatest(Task t, long nowMs)
    {
        if (t != null && t.sentRemaining != t.remaining)
        {
            emit(SlayerTaskEvent.PROGRESS, nowMs, t, null, t.remaining);
            t.sentRemaining = t.remaining;
            t.lastProgressMs = nowMs;
        }
    }

    private void progress(long nowMs)
    {
        emit(SlayerTaskEvent.PROGRESS, nowMs, task, null, task.remaining);
        task.sentRemaining = task.remaining;
        task.lastProgressMs = nowMs;
    }

    private void emit(String type, long nowMs, Task t, Integer amount, Integer remaining)
    {
        sink.accept(new SlayerTaskEvent(type, nowMs, t, amount, remaining));
    }
}
