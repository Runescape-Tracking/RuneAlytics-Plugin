package com.runealytics.progress;

import com.google.gson.Gson;
import com.runealytics.CurrentPlayerIdentityService;
import com.runealytics.RuneAlyticsState;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Captures quest, achievement-diary and combat-achievement progress and uploads it
 * through the plugin's existing website authentication.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>Every {@code on*} method is called from a RuneLite event handler on the
 *       <b>client thread</b>. They only update plain fields; the scan itself (which
 *       runs client scripts) happens in {@link #onGameTick()}, never inside
 *       {@code VarbitChanged}.</li>
 *   <li>Disk and network work runs on RuneLite's shared {@link ScheduledExecutorService}.
 *       The client thread hands over immutable diff results only.</li>
 * </ul>
 *
 * <h2>Account isolation</h2>
 * <ul>
 *   <li>Data is keyed by {@code client.getAccountHash()} + RuneLite's
 *       {@link RuneScapeProfileType} (so Leagues/Deadman never mix with the main
 *       account); the display name is never the storage key.</li>
 *   <li>A generation counter is bumped on logout, account change and shutdown;
 *       asynchronous loads re-check it before touching tracker state.</li>
 *   <li>Upload credentials are bound to an account's outbox only when the logged-in
 *       name matches the verified RuneAlytics account, and are captured with each
 *       batch, so account A's data is only ever sent with account A's token.</li>
 * </ul>
 */
@Singleton
public class ProgressTracker
{
    private static final Logger log = LoggerFactory.getLogger(ProgressTracker.class);

    /** Sent as {@code client.plugin_progress_version}; bump when capture semantics change. */
    static final String CLIENT_VERSION = "progress-1";
    static final String INSTALLATION_ID_KEY = "progressInstallationId";

    /** Ticks with no var changes before a deferred rescan (≈1.2 s). */
    static final int QUIET_TICKS = 2;
    /** Minimum ticks between change-driven rescans (≈6 s). */
    static final int MIN_SCAN_GAP_TICKS = 10;
    /** A rescan is forced this many ticks after the first unprocessed change, even if vars keep changing (≈30 s). */
    static final int MAX_DEFER_TICKS = 50;
    /** Full re-read while logged in, to recover anything a missed event would hide. */
    static final long RECONCILE_INTERVAL_MS = 30 * 60_000L;
    /** How often an unchanged baseline's "last observed" time is refreshed on disk. */
    static final long BASELINE_TOUCH_MS = 5 * 60_000L;
    static final long DELTA_DEBOUNCE_MS = 10_000L;
    static final long SNAPSHOT_DEBOUNCE_MS = 2_000L;
    static final int MAX_BATCHES_PER_FLUSH = 5;

    private final Client client;
    private final ClientThread clientThread;
    private final ScheduledExecutorService executor;
    private final Gson gson;
    private final RuneAlyticsState state;
    private final CurrentPlayerIdentityService identity;
    private final OkHttpClient okHttpClient;
    private final Random random = new Random();

    // ── Set by start() ───────────────────────────────────────────────────────
    private volatile ProgressStore store;
    private volatile ProgressApiClient api;
    private volatile Supplier<String> worldModeSupplier = () -> "unknown";
    private volatile BooleanSupplier captureEnabled = () -> true;
    private volatile BooleanSupplier uploadEnabled = () -> false;
    private volatile String installationId;
    private volatile Map<String, ProgressOutbox> outboxes = new ConcurrentHashMap<>();
    private volatile boolean running;

    // ── Client-thread state ──────────────────────────────────────────────────
    private final ProgressReadinessGate gate = new ProgressReadinessGate();
    private final ProgressReader.Timing timing = new ProgressReader.Timing();
    private volatile long generation;
    private long tick;
    private String currentKey;
    private String currentProfileType;
    private boolean currentSkipped;
    private ProgressOutbox currentOutbox;
    private ProgressBaseline baseline;
    private boolean baselineLoaded;
    private boolean needsGate = true;
    private boolean catchUpPending = true;
    private boolean snapshotRequested;
    private boolean dirty;
    private long firstDirtyTick;
    private long lastVarTick;
    private long lastScanTick = Long.MIN_VALUE / 2;
    private long lastScanMs;
    private long lastBaselinePushMs;
    private String catalogVersion;
    private volatile String sessionId = UUID.randomUUID().toString();

    // Instrumentation (client thread).
    private int scans;
    private long scanNanosTotal;
    private long scanNanosMax;

    // ── Flush scheduling (executor) ──────────────────────────────────────────
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile boolean uploadsOffLogged;
    private ScheduledFuture<?> flushFuture;
    private long flushAtMs;

    @Inject
    public ProgressTracker(Client client, ClientThread clientThread, ScheduledExecutorService executor, Gson gson,
                           RuneAlyticsState state, CurrentPlayerIdentityService identity, OkHttpClient okHttpClient)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.executor = executor;
        this.gson = gson;
        this.state = state;
        this.identity = identity;
        this.okHttpClient = okHttpClient;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * @param store          local storage, or {@code null} to keep progress in memory only
     * @param apiUrl         website API base URL (the plugin's {@code apiUrl} config)
     * @param installationId stable random id for this RuneLite install (not account-specific)
     * @param worldMode      current world mode label; called on the client thread
     * @param capture        whether capture is enabled (the plugin config toggle)
     * @param upload         whether uploads are allowed (config toggle + server feature flag)
     */
    public synchronized void start(ProgressStore store, Supplier<String> apiUrl, String installationId,
                                   Supplier<String> worldMode, BooleanSupplier capture, BooleanSupplier upload)
    {
        if (running) return; // idempotent: a repeated start must not duplicate schedules
        this.store = store;
        this.api = new ProgressApiClient(okHttpClient, apiUrl);
        this.installationId = installationId;
        this.worldModeSupplier = worldMode;
        this.captureEnabled = capture;
        this.uploadEnabled = upload;
        this.outboxes = new ConcurrentHashMap<>();
        this.catalogVersion = ProgressReader.catalogVersion();
        this.running = true;
        clientThread.invokeLater(this::resetClientState);
        log.debug("[Progress] started (catalog {})", catalogVersion);
    }

    public synchronized void stop()
    {
        if (!running) return;
        running = false;
        generation++;
        if (flushFuture != null)
        {
            flushFuture.cancel(false);
            flushFuture = null;
        }
        final Map<String, ProgressOutbox> toPersist = outboxes;
        executor.execute(() ->
        {
            for (ProgressOutbox box : toPersist.values()) persist(box);
            toPersist.clear();
        });
        clientThread.invokeLater(() ->
        {
            gate.suspend();
            currentKey = null;
            currentOutbox = null;
            baseline = null;
        });
        log.debug("[Progress] stopped");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CLIENT-THREAD EVENTS
    // ═════════════════════════════════════════════════════════════════════════

    /** GameState LOGGED_IN. Also fires after every region load, so only (re)arms the gate when needed. */
    public void onLoggedIn()
    {
        if (!running) return;
        if (needsGate || !gate.isActive())
        {
            needsGate = false;
            gate.begin(tick);
        }
        resolveAccount();
    }

    /** World hop or connection loss: same account, but variables are re-sent. */
    public void onHopping()
    {
        if (!running) return;
        gate.suspend();
        needsGate = true;
        dirty = false;
    }

    /** LOGIN_SCREEN: forget the account on this thread; flush what was captured. */
    public void onLogout()
    {
        if (!running) return;
        generation++;
        gate.suspend();
        needsGate = true;
        currentKey = null;
        currentProfileType = null;
        currentSkipped = false;
        currentOutbox = null;
        baseline = null;
        baselineLoaded = false;
        dirty = false;
        // Outboxes keep their own binding, so captured changes still go out with
        // the token of the account they belong to.
        scheduleFlush(1_000L);
    }

    /** Account hash or world changed (RuneLite AccountHashChanged / WorldChanged). */
    public void onIdentityMaybeChanged()
    {
        if (!running || !gate.isActive()) return;
        resolveAccount();
    }

    /** VarbitChanged: invalidation only. No reads, no scripts. */
    public void onVarChanged()
    {
        if (!running || !gate.isActive()) return;
        gate.onVarChanged(tick);
        if (gate.isReady())
        {
            if (!dirty)
            {
                dirty = true;
                firstDirtyTick = tick;
            }
            lastVarTick = tick;
        }
    }

    /** Verification state changed (verified, rejected or switched). */
    public void onVerificationChanged()
    {
        if (!running) return;
        bindIfVerified();
        scheduleFlush(SNAPSHOT_DEBOUNCE_MS);
    }

    /** Upload gate (config or server feature flag) may have changed. */
    public void onUploadSettingsChanged()
    {
        uploadsOffLogged = false;
        if (running) scheduleFlush(SNAPSHOT_DEBOUNCE_MS);
    }

    /** Manual resync. Safe from any thread. */
    public void requestResync()
    {
        if (!running) return;
        clientThread.invokeLater(() ->
        {
            snapshotRequested = true;
            dirty = true;
        });
    }

    public void onGameTick()
    {
        if (!running) return;
        tick++;
        if (!captureEnabled.getAsBoolean()) return;
        if (!gate.isActive()) return;

        if (currentKey == null && !currentSkipped) resolveAccount();
        if (currentSkipped) return;
        if (tick % 5 == 0) bindIfVerified();

        ProgressReadinessGate.Status status = gate.evaluate(tick, identityKnown(), baselineLoaded);
        switch (status)
        {
            case ATTEMPT:
                scan(true, false);
                break;
            case TIMED_OUT:
                scan(true, true);
                break;
            case READY:
                if (shouldRescan(System.currentTimeMillis())) scan(false, false);
                break;
            default:
                break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INTERNALS (client thread)
    // ═════════════════════════════════════════════════════════════════════════

    private void resetClientState()
    {
        generation++;
        currentKey = null;
        currentOutbox = null;
        baseline = null;
        baselineLoaded = false;
        needsGate = true;
        catchUpPending = true;
        dirty = false;
        // Plugin enabled while already logged in: arm the gate immediately.
        if (client.getGameState() == net.runelite.api.GameState.LOGGED_IN)
        {
            onLoggedIn();
        }
    }

    private boolean identityKnown()
    {
        Player p = client.getLocalPlayer();
        return p != null && p.getName() != null && client.getAccountHash() != -1 && currentKey != null;
    }

    private void resolveAccount()
    {
        long hash = client.getAccountHash();
        if (hash == -1) return; // not known yet; retried on the next tick

        RuneScapeProfileType type = RuneScapeProfileType.getCurrent(client);
        String key = accountKey(hash, type);
        if (key.equals(currentKey)) return;

        generation++;
        final long gen = generation;
        currentKey = key;
        currentProfileType = type.name();
        currentOutbox = null;
        baseline = null;
        baselineLoaded = false;
        catchUpPending = true;
        snapshotRequested = true;
        dirty = false;
        sessionId = UUID.randomUUID().toString();

        if (type == RuneScapeProfileType.BETA)
        {
            // Beta / no-save worlds: progress there is not real account progress.
            currentSkipped = true;
            log.debug("[Progress] beta world — progress capture skipped");
            return;
        }
        currentSkipped = false;

        final Map<String, ProgressOutbox> boxes = outboxes;
        final String profile = type.name();
        executor.execute(() ->
        {
            ProgressOutbox box = boxes.computeIfAbsent(key, k -> load(k, profile));
            clientThread.invokeLater(() ->
            {
                if (gen != generation || !key.equals(currentKey)) return; // stale load
                currentOutbox = box;
                baseline = box.getBaseline();
                baselineLoaded = true;
                bindIfVerified();
            });
        });
    }

    /** Storage key: account hash + profile type. Local only; never uploaded. */
    static String accountKey(long accountHash, RuneScapeProfileType type)
    {
        return "progress-" + Long.toHexString(accountHash) + "-" + type.name().toLowerCase();
    }

    private void bindIfVerified()
    {
        ProgressOutbox box = currentOutbox;
        if (box == null) return;

        String verified = CurrentPlayerIdentityService.normalizeUsername(state.getVerifiedUsername());
        String current = identity.getCurrentNormalizedUsername();
        String token = state.getVerificationCode();

        if (state.isVerified() && verified != null && verified.equals(current) && token != null && !token.isEmpty())
        {
            box.bind(verified, token);
        }
        else if (current != null)
        {
            // Not linked, linked to a different account, or verification still
            // pending/failed: never upload this account's data.
            box.unbind();
        }
    }

    private boolean shouldRescan(long nowMs)
    {
        if (snapshotRequested) return true;
        if (dirty)
        {
            boolean quiet = tick - lastVarTick >= QUIET_TICKS;
            boolean gapOk = tick - lastScanTick >= MIN_SCAN_GAP_TICKS;
            if ((quiet && gapOk) || tick - firstDirtyTick >= MAX_DEFER_TICKS) return true;
        }
        return nowMs - lastScanMs >= RECONCILE_INTERVAL_MS;
    }

    private void scan(boolean initial, boolean timedOut)
    {
        final ProgressOutbox box = currentOutbox;
        if (box == null || baseline == null) return;

        long nowMs = System.currentTimeMillis();
        ProgressObservation obs;
        try
        {
            obs = ProgressReader.read(client, nowMs, catalogVersion, timing);
        }
        catch (RuntimeException e)
        {
            log.debug("[Progress] scan failed: {}", e.getMessage());
            lastScanTick = tick;
            return;
        }
        recordTiming();

        if (initial && !timedOut && !allComplete(obs))
        {
            // Variables may still be loading; try again next tick until the gate times out.
            return;
        }

        ProgressDiffer.Result diff = ProgressDiffer.diff(baseline, obs, catchUpPending, () -> UUID.randomUUID().toString());
        baseline = diff.getBaseline();
        if (initial)
        {
            gate.markReady();
            logReadySummary(obs, timedOut, diff);
        }
        if (obs.fullyUsable()) catchUpPending = false;

        lastScanTick = tick;
        lastScanMs = nowMs;
        dirty = false;

        final boolean snapshot = snapshotRequested;
        snapshotRequested = false;
        final boolean touch = nowMs - lastBaselinePushMs >= BASELINE_TOUCH_MS;
        if (!diff.hasChanges() && !snapshot && !touch) return;
        lastBaselinePushMs = nowMs;

        for (ProgressTransition t : diff.getTransitions())
        {
            log.debug("[Progress] CHANGE ({}): {}", t.getDetection(), describe(t));
        }

        final String worldMode = safeWorldMode();
        executor.execute(() ->
        {
            box.apply(diff, worldMode);
            if (snapshot) box.requestSnapshot();
            scheduleFlush(snapshot ? SNAPSHOT_DEBOUNCE_MS : DELTA_DEBOUNCE_MS);
        });
    }

    /** One line after the first usable scan, so capture is visible in the log even while uploads are off. */
    private void logReadySummary(ProgressObservation obs, boolean timedOut, ProgressDiffer.Result diff)
    {
        int done = 0;
        int started = 0;
        int notStarted = 0;
        for (QuestRecord q : obs.getQuests().values())
        {
            if (q.getStatus() == QuestStatus.COMPLETED) done++;
            else if (q.getStatus() == QuestStatus.IN_PROGRESS) started++;
            else if (q.getStatus() == QuestStatus.NOT_STARTED) notStarted++;
        }
        int diaryComplete = 0;
        int diaryRewards = 0;
        for (DiaryRecord d : obs.getDiaries().values())
        {
            if (d.getCompleteVar() != 0) diaryComplete++;
            if (d.getRewardVar() != 0) diaryRewards++;
        }
        CombatRecord ca = obs.getCombat();
        log.debug("[Progress] ready{} — quests ({}): {} completed, {} in progress, {} not started (QP {}); "
                        + "diaries ({}): {}/{} tiers complete, {} rewards claimed; combat achievements ({}): {} task bits, "
                        + "{} counted by game, {} points; {} change(s) vs last session; uploads {}",
                timedOut ? " (after timeout)" : "",
                obs.getQuestCoverage(), done, started, notStarted, obs.getQuestPoints(),
                obs.getDiaryCoverage(), diaryComplete, obs.getDiaries().size(), diaryRewards,
                obs.getCombatCoverage(), ca == null ? 0 : ca.completedBitCount(),
                ca == null ? 0 : ca.tierCompletedSum(), ca == null ? 0 : ca.getPoints(),
                diff.getTransitions().size(), uploadEnabled.getAsBoolean() ? "ON" : "OFF");
    }

    private static boolean allComplete(ProgressObservation obs)
    {
        return ProgressCoverage.COMPLETE.equals(obs.getQuestCoverage())
                && ProgressCoverage.COMPLETE.equals(obs.getDiaryCoverage())
                && ProgressCoverage.COMPLETE.equals(obs.getCombatCoverage());
    }

    private String safeWorldMode()
    {
        try
        {
            return worldModeSupplier.get();
        }
        catch (RuntimeException e)
        {
            return "unknown";
        }
    }

    private void recordTiming()
    {
        scans++;
        scanNanosTotal += timing.totalNanos;
        scanNanosMax = Math.max(scanNanosMax, timing.totalNanos);
        if (timing.totalNanos > 5_000_000L || scans % 100 == 0)
        {
            log.debug("[Progress] scan #{} took {} µs (quests {} µs); avg {} µs, max {} µs",
                    scans, timing.totalNanos / 1_000, timing.questNanos / 1_000,
                    scanNanosTotal / scans / 1_000, scanNanosMax / 1_000);
        }
    }

    /** Human-readable one-liner for a detected change. */
    static String describe(ProgressTransition t)
    {
        switch (t.getCategory())
        {
            case ProgressTransition.CATEGORY_QUEST:
                return "quest '" + t.getName() + "' (id " + t.getKey() + "): " + t.getFrom() + " -> " + t.getTo();
            case ProgressTransition.CATEGORY_DIARY:
                return "diary " + t.getName() + " " + diaryField(t.getField()) + ": " + t.getFrom() + " -> " + t.getTo();
            case ProgressTransition.CATEGORY_CA_TASK:
                return "combat achievement task completed (varp:bit " + t.getKey() + ")";
            case ProgressTransition.CATEGORY_CA_TIER:
                return "combat achievement tier " + t.getKey() + " " + t.getField() + ": " + t.getFrom() + " -> " + t.getTo();
            default:
                return t.getCategory() + " " + t.getKey() + " " + t.getField() + ": " + t.getFrom() + " -> " + t.getTo();
        }
    }

    private static String diaryField(String field)
    {
        switch (field)
        {
            case "complete":   return "tasks complete flag";
            case "reward":     return "reward claimed flag";
            case "task_count": return "tasks done";
            default:           return field;
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    //  STORAGE + UPLOAD (executor)
    // ═════════════════════════════════════════════════════════════════════════

    private ProgressOutbox load(String key, String profileType)
    {
        ProgressStore s = store;
        if (s == null) return new ProgressOutbox(key, profileType);
        try
        {
            return ProgressOutbox.restore(key, profileType, s.read(key + ".json"), gson);
        }
        catch (Exception e)
        {
            log.debug("[Progress] could not read local progress file: {}", e.getMessage());
            return new ProgressOutbox(key, profileType);
        }
    }

    private void persist(ProgressOutbox box)
    {
        ProgressStore s = store;
        if (s == null) return;
        String json = box.serializeIfDirty(gson);
        if (json == null) return;
        try
        {
            s.write(box.getAccountKey() + ".json", json);
        }
        catch (Exception e)
        {
            box.markDirty();
            log.debug("[Progress] could not write local progress file: {}", e.getMessage());
        }
    }

    private synchronized void scheduleFlush(long delayMs)
    {
        if (!running) return;
        long target = System.currentTimeMillis() + delayMs;
        if (flushFuture != null && !flushFuture.isDone())
        {
            if (flushAtMs <= target) return;
            flushFuture.cancel(false);
        }
        flushAtMs = target;
        flushFuture = executor.schedule(this::flush, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void flush()
    {
        synchronized (this)
        {
            flushFuture = null;
        }
        if (!flushing.compareAndSet(false, true)) return;
        long nextDue = Long.MAX_VALUE;
        try
        {
            final boolean upload = running && uploadEnabled.getAsBoolean();
            for (ProgressOutbox box : outboxes.values())
            {
                if (!upload && box.hasWork() && !uploadsOffLogged)
                {
                    uploadsOffLogged = true;
                    log.debug("[Progress] changes saved locally; uploads are OFF (needs the server 'achievements' "
                            + "feature flag and 'Enable Achievement Sync'). They will be sent once both are on.");
                }
                else if (upload && box.hasWork() && !box.isBound())
                {
                    log.debug("[Progress] changes queued for {}; not uploading because that account is not the "
                            + "verified RuneAlytics account (or verification is pending)", box.getProfileType());
                }
                if (upload)
                {
                    sendDue(box);
                    if (box.isBound() && box.hasWork())
                    {
                        nextDue = Math.min(nextDue, box.nextDueAtMs(System.currentTimeMillis()));
                    }
                }
                persist(box);
            }
        }
        catch (RuntimeException e)
        {
            log.debug("[Progress] flush failed: {}", e.getMessage());
        }
        finally
        {
            flushing.set(false);
        }
        if (nextDue != Long.MAX_VALUE)
        {
            scheduleFlush(Math.max(1_000L, nextDue - System.currentTimeMillis()));
        }
    }

    private void sendDue(ProgressOutbox box)
    {
        ProgressApiClient client = api;
        if (client == null) return;
        for (int i = 0; i < MAX_BATCHES_PER_FLUSH; i++)
        {
            long now = System.currentTimeMillis();
            ProgressOutbox.Batch batch = box.prepare(now, sessionId, installationId, CLIENT_VERSION, gson,
                    () -> UUID.randomUUID().toString());
            if (batch == null) return;
            ProgressOutbox.Outcome outcome = client.send(batch);
            int lost = box.onResult(batch.getBatchId(), outcome, System.currentTimeMillis(), random);
            if (lost > 0)
            {
                log.debug("[Progress] batch seq={} rejected by server; {} event(s) dropped, full snapshot queued",
                        batch.getSequence(), lost);
            }
            int dropped = box.takeDroppedTransitionCount();
            if (dropped > 0) log.debug("[Progress] {} event(s) dropped by local limits", dropped);
            if (outcome != ProgressOutbox.Outcome.ACK) return;
        }
    }
}
