package com.runealytics;

import com.google.gson.JsonArray;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

@Singleton
public class MatchmakingManager
{
    private static final Logger log = LoggerFactory.getLogger(MatchmakingManager.class);

    private static final int POLL_INTERVAL_TICKS = 2;

    /** Ticks to wait after a failed accept/begin call before retrying. */
    private static final int RETRY_BACKOFF_TICKS = 5;
    private static final int RALLY_DISTANCE      = 15;

    private final Client                   client;
    private final RuneAlyticsState         runeAlyticsState;
    private final MatchmakingApiClient     apiClient;
    private final ScheduledExecutorService executorService;

    // Written from the client thread (game events) and the background executor
    // (OkHttp callbacks); volatile for cross-thread visibility.
    private volatile MatchmakingSession       session;
    private volatile MatchmakingUpdateListener listener;

    /**
     * Monotonic match generation, incremented on every {@link #reset()} and
     * {@link #loadMatch(String)}. In-flight executor tasks check it via
     * {@link #isStale(int)} and drop results belonging to an old match.
     */
    private volatile int matchGeneration;

    private volatile int     tickCounter;
    private volatile boolean requestInFlight;
    private volatile boolean acceptInFlight;
    private volatile boolean beginInFlight;

    /** Tick number when the next accept/begin retry is allowed (back-off). */
    private volatile int     acceptCooldownUntilTick;
    private volatile int     beginCooldownUntilTick;
    private volatile boolean reportInFlight;
    private volatile boolean itemsReportInFlight;
    private volatile boolean resultReported;
    private volatile boolean itemsReported;

    /** True once combat has been reported to the server. */
    private volatile boolean combatReported;
    private volatile boolean combatInFlight;
    private volatile WorldPoint lastRallyPoint;
    private volatile String     lastHintPlayerName;

    /**
     * Minimap target (opponent or rally tile), recomputed once per game tick on
     * the client thread and read by the overlay each frame. Volatile because the
     * client thread writes it and the render thread reads it.
     */
    private volatile WorldPoint cachedMinimapTarget;

    /**
     * Current inventory snapshot, updated on every game tick and on every
     * {@link ItemContainerChanged} event so outbound calls carry up-to-date
     * item data for risk/gear validation.
     */
    private volatile JsonArray currentInventorySnapshot;

    /**
     * Current equipment snapshot. The {@code slot} field lets the server
     * identify the weapon slot (3) for gear-rule validation.
     */
    private volatile JsonArray currentGearSnapshot;

    /**
     * Ordinal of the local player's active overhead prayer (HeadIcon.ordinal()),
     * or {@code -1} if none. Sent to the server for "No Overheads" rule checks.
     */
    private volatile int currentOverheadIconOrdinal = -1;

    /**
     * Whether the local player currently has a skull icon. Sent to the server
     * for the keep-on-death risk-value calculation.
     */
    private volatile boolean currentIsSkulled;

    /**
     * Whether the local player currently has the Protect Item prayer active.
     * Combined with skull status server-side to determine how many items are
     * kept on death (0/1/3/4).
     */
    private volatile boolean currentProtectItem;

    /**
     * Set to {@code true} on {@link ItemContainerChanged} while in a Fighting
     * match, so {@link #reportItemsIfNeeded()} sends a fresh snapshot to
     * {@code /report-items} on the next tick.
     */
    private volatile boolean gearChangedDuringFight;

    @Inject
    public MatchmakingManager(
            Client                   client,
            RuneAlyticsState         runeAlyticsState,
            MatchmakingApiClient     apiClient,
            ScheduledExecutorService executorService
    )
    {
        this.client           = client;
        this.runeAlyticsState = runeAlyticsState;
        this.apiClient        = apiClient;
        this.executorService  = executorService;
    }

    /**
     * Returns {@code true} if {@code generation} no longer matches the current
     * {@link #matchGeneration}, i.e. the match was reset or replaced. In-flight
     * executor callbacks check this before mutating {@link #session} or notifying
     * the UI.
     */
    private boolean isStale(int generation)
    {
        return generation != matchGeneration;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    public void setListener(MatchmakingUpdateListener listener)
    {
        this.listener = listener;
    }

    public boolean hasActiveMatch()
    {
        return session != null;
    }

    public MatchmakingSession getSession()
    {
        return session;
    }

    /**
     * Kicks off loading the given match code.
     *
     * @return {@code true} if a load was actually started; {@code false} if the
     *         call was a no-op (a request is already in flight) or failed
     *         pre-flight validation. When {@code false} is returned because of
     *         missing credentials, the listener is notified with an error so the
     *         UI can recover; callers should still clear any "loading" state.
     */
    public boolean loadMatch(String matchCode)
    {
        if (requestInFlight) return false;

        reset();

        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            notifyListener(new MatchmakingUpdate(null,
                    "Missing verification or RSN. Please re-verify your account.",
                    "", false, false));
            return false;
        }

        requestInFlight = true;

        // Snapshot captured before the load so the server can validate on the
        // first response.
        final int       gen      = matchGeneration;
        final JsonArray inv      = currentInventorySnapshot;
        final JsonArray gear     = currentGearSnapshot;
        final int       overhead = currentOverheadIconOrdinal;
        final boolean   skulled  = currentIsSkulled;
        final boolean   protect  = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            // Match was reset/replaced while this load was in flight — drop it.
            if (isStale(gen)) return;

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                session = result.getSession();
                updateResultStatus();
            }

            requestInFlight = false;
        });

        return true;
    }

    /**
     * Must be called on the <em>client thread</em> every game tick so that
     * all {@link ItemContainer} reads happen safely.
     */
    public void onGameTick()
    {
        // Refresh player state (inventory, gear, overhead, skull) on the client
        // thread so every outbound call carries current data. Safe with no
        // active match.
        refreshPlayerState();

        // Update the hint arrow every tick so it never goes stale during a
        // network call.
        if (session != null)
        {
            updateHintArrow();
        }

        // Recompute and cache the minimap target once per tick; the overlay
        // reads the cached value each frame.
        cachedMinimapTarget = computeMinimapTarget();

        if (session == null || requestInFlight)
        {
            return;
        }

        // Once the match is Completed/Canceled, stop all server traffic.
        if (isMatchCompletedOrCanceled())
        {
            return;
        }

        tickCounter++;

        // ── Accept: done on first tick where snapshot is ready ────────────────
        if (!session.isLocalJoined())
        {
            attemptAcceptMatchIfNeeded();
        }

        // ── Poll every 2 ticks — includes latest gear for continuous validation
        if (tickCounter % POLL_INTERVAL_TICKS == 0)
        {
            pollMatch();
        }

        attemptBeginMatchIfNeeded();
        reportItemsIfNeeded();
    }

    /**
     * Called from the plugin's {@code onItemContainerChanged} handler.
     * Updates the local snapshot immediately so the next poll carries
     * the freshest data.  Also flags that gear changed during a fight,
     * which triggers a dedicated {@code /report-items} call.
     */
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int containerId = event.getContainerId();
        if (containerId != InventoryID.INVENTORY.getId()
                && containerId != InventoryID.EQUIPMENT.getId())
        {
            return;
        }

        // Rebuild player state so the next off-thread poll has the latest
        // inventory/gear/status.
        refreshPlayerState();

        // Mark gear as changed during a fight so /report-items fires again
        if (statusIs("Fighting"))
        {
            gearChangedDuringFight = true;
            // Reset itemsReported so the next reportItemsIfNeeded() call fires
            itemsReported = false;
        }
    }

    /**
     * Called from the plugin's {@code onHitsplatApplied} handler for every
     * hitsplat in the world.  Detects the first real exchange of blows between
     * the two match participants and reports it so the server transitions the
     * match from Ready to Fighting.
     *
     * <p>Two directions are accepted, each scoped to the opponent so an NPC or
     * an unrelated player cannot trigger the fight:</p>
     * <ul>
     *   <li><b>Local player hits the opponent</b> — {@code target == opponent}
     *       and the hitsplat is ours ({@link Hitsplat#isMine()}).</li>
     *   <li><b>Opponent hits the local player</b> — {@code target == localPlayer}
     *       and the opponent is interacting with the local player.</li>
     * </ul>
     *
     * <p>Only fires while the match is "Ready" and reports at most once;
     * a later hit retries if the report fails.</p>
     */
    public void onCombatHitsplat(Actor target, Hitsplat hitsplat)
    {
        if (session == null || target == null || hitsplat == null) return;
        if (combatReported || combatInFlight) return;

        String status = session.getStatus();
        if (status == null || !status.equalsIgnoreCase("Ready")) return;

        Player local    = client.getLocalPlayer();
        Player opponent = findPlayerByName(session.getOpponentRsn());
        if (local == null || opponent == null) return;

        boolean weHitOpponent = target == opponent && hitsplat.isMine();
        boolean opponentHitUs = target == local && opponent.getInteracting() == local;

        if (!weHitOpponent && !opponentHitUs) return;

        log.debug("[engage] combat detected ({}) — reporting Ready → Fighting (match={})",
                weHitOpponent ? "we hit opponent" : "opponent hit us", session.getMatchCode());

        reportCombatEngaged();
    }

    /**
     * Sends {@code /engage-combat} so the server flips the match to "Fighting".
     * Idempotent server-side, so it is harmless if both clients report the same
     * engagement.  {@link #combatReported} only latches on success so a
     * transient failure is retried by the next qualifying hit.
     */
    private void reportCombatEngaged()
    {
        String token            = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (token == null || token.isEmpty()
                || verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty())
        {
            return;
        }

        combatInFlight = true;

        final int       gen       = matchGeneration;
        final String    matchCode = session.getMatchCode();
        final JsonArray inv       = currentInventorySnapshot;
        final JsonArray gear      = currentGearSnapshot;
        final int       overhead  = currentOverheadIconOrdinal;
        final boolean   skulled   = currentIsSkulled;
        final boolean   protect   = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.engageCombat(
                        verificationCode, matchCode, rsn, token, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                log.debug("[engage] IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            handleResult(result);

            if (result.isSuccess())
            {
                combatReported = true;
                if (result.getSession() != null)
                {
                    log.debug("[engage] success — status now {}", result.getSession().getStatus());
                    session = result.getSession();
                    updateResultStatus();
                }
            }
            else
            {
                String body = result.getRawResponse();
                log.debug("[engage] failed — msg='{}' body={}",
                        result.getMessage(),
                        body.length() > 200 ? body.substring(0, 200) : body);
            }

            combatInFlight = false;
        });
    }

    public void reset()
    {
        // Bump the generation so in-flight executor tasks for the old match
        // drop their results.
        matchGeneration++;
        session                  = null;
        tickCounter              = 0;
        requestInFlight          = false;
        acceptInFlight           = false;
        beginInFlight            = false;
        reportInFlight           = false;
        itemsReportInFlight      = false;
        resultReported           = false;
        itemsReported            = false;
        combatReported           = false;
        combatInFlight           = false;
        gearChangedDuringFight   = false;
        acceptCooldownUntilTick  = 0;
        beginCooldownUntilTick   = 0;
        cachedMinimapTarget      = null;
        // Keep snapshots — they remain valid across match resets.
        clearHintArrow();
    }

    public void onActorDeath(Player player)
    {
        if (session == null)
        {
            log.debug("[death] ignored — no active match session");
            return;
        }
        if (reportInFlight)
        {
            log.debug("[death] ignored — reportInFlight=true (duplicate/race)");
            return;
        }
        if (resultReported)
        {
            log.debug("[death] ignored — resultReported=true (already completed)");
            return;
        }
        if (player == null || player.getName() == null)
        {
            log.debug("[death] ignored — null player/name");
            return;
        }

        // RuneLite's Actor.getName() uses a non-breaking space (U+00A0) while
        // OSRS profiles store a regular space or underscore. Normalize before
        // comparison so the death is attributed to the right participant.
        String deathName = normalizeRsn(player.getName());
        String p1Name    = normalizeRsn(session.getPlayer1Username());
        String p2Name    = normalizeRsn(session.getPlayer2Username());

        if (!deathName.equalsIgnoreCase(p1Name) && !deathName.equalsIgnoreCase(p2Name))
        {
            log.debug("[death] ignored — '{}' is not a match participant (p1='{}' p2='{}')",
                    deathName, p1Name, p2Name);
            return;
        }

        String token            = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (token == null || token.isEmpty())
        {
            log.debug("[death] skipping — local auth token is null/empty");
            return;
        }
        if (verificationCode == null || verificationCode.isEmpty())
        {
            log.debug("[death] skipping — verification code is null/empty");
            return;
        }
        if (rsn == null || rsn.isEmpty())
        {
            log.debug("[death] skipping — local RSN is null/empty");
            return;
        }

        final String reportedDeath = deathName; // already normalized above

        log.debug("[death] reporting death of '{}' (match={} status={})",
                reportedDeath, session.getMatchCode(), session.getStatus());

        reportInFlight = true;

        final int    gen       = matchGeneration;
        final String matchCode = session.getMatchCode();

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportMatch(verificationCode, matchCode, rsn, token, reportedDeath);
            }
            catch (IOException ex)
            {
                log.debug("[death] reportMatch IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                log.debug("[death] match reported — new status: {}", result.getSession().getStatus());
                session = result.getSession();
                updateResultStatus();
            }
            else if (!result.isTokenRefresh())
            {
                // Token-refresh is already handled centrally by handleResult().
                log.debug("[death] reportMatch failed — success={} msg='{}' body={}",
                        result.isSuccess(), result.getMessage(),
                        result.getRawResponse().length() > 200
                                ? result.getRawResponse().substring(0, 200)
                                : result.getRawResponse());
            }

            reportInFlight = false;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private: snapshot management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds {@link #currentInventorySnapshot} and {@link #currentGearSnapshot}
     * from the live {@link ItemContainer}s. Must be called on the client thread.
     */
    private void refreshPlayerState()
    {
        ItemContainer inv   = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);

        // Lean snapshots — {id, qty} / {slot, id, qty}; the website prices each
        // item.
        currentInventorySnapshot = RuneAlyticsItemJson.fromContainer(inv);
        currentGearSnapshot      = RuneAlyticsItemJson.fromEquipment(equip);

        // Overhead prayer, skull, and Protect Item status, captured on the
        // client thread for inclusion in outbound API calls.
        Player local = client.getLocalPlayer();
        if (local != null)
        {
            HeadIcon overhead          = local.getOverheadIcon();
            currentOverheadIconOrdinal = (overhead != null) ? overhead.ordinal() : -1;
            currentIsSkulled           = local.getSkullIcon() >= 0;
            currentProtectItem         = client.isPrayerActive(Prayer.PROTECT_ITEM);
        }
        else
        {
            currentOverheadIconOrdinal = -1;
            currentIsSkulled           = false;
            currentProtectItem         = false;
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Private: polling + state changes
    // ─────────────────────────────────────────────────────────────────────────

    private void pollMatch()
    {
        if (requestInFlight) return;

        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty())
        {
            return;
        }

        requestInFlight = true;

        final int gen       = matchGeneration;
        String    matchCode = session.getMatchCode();
        JsonArray inv       = currentInventorySnapshot;
        JsonArray gear      = currentGearSnapshot;
        int       overhead  = currentOverheadIconOrdinal;
        boolean   skulled   = currentIsSkulled;
        boolean   protect   = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            // Ignore a straggling poll that lands after the result is reported;
            // its older snapshot would overwrite the terminal status.
            if (resultReported)
            {
                requestInFlight = false;
                return;
            }

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                session = result.getSession();
                updateResultStatus();
            }

            requestInFlight = false;
        });
    }

    private void attemptAcceptMatchIfNeeded()
    {
        if (session == null || session.isLocalJoined() || acceptInFlight) return;

        // Honour the back-off after a previous failure to avoid one request per
        // tick.
        if (tickCounter < acceptCooldownUntilTick) return;

        String token            = session.getLocalToken();
        if (token == null || token.isEmpty()) return;

        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty())
        {
            return;
        }

        final JsonArray inventory = currentInventorySnapshot != null ? currentInventorySnapshot : new JsonArray();
        final JsonArray gear      = currentGearSnapshot      != null ? currentGearSnapshot      : new JsonArray();
        final int       overhead  = currentOverheadIconOrdinal;
        final boolean   skulled   = currentIsSkulled;
        final boolean   protect   = currentProtectItem;

        acceptInFlight = true;
        final int    gen       = matchGeneration;
        final String matchCode = session.getMatchCode();
        log.debug("[accept] sending — status={} rsn={}", session.getStatus(), rsn);

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.acceptMatch(
                        verificationCode, matchCode, rsn, token, inventory, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                log.debug("[accept] IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                log.debug("[accept] success — status now {}", result.getSession().getStatus());
                session = result.getSession();
                updateResultStatus();
            }
            else
            {
                // Schedule a retry on failure so a transient error doesn't latch
                // the match in "Pending".
                String body = result.getRawResponse();
                log.debug("[accept] failed — success={} tokenRefresh={} msg='{}' body={}",
                        result.isSuccess(), result.isTokenRefresh(), result.getMessage(),
                        body.length() > 200 ? body.substring(0, 200) : body);
                acceptCooldownUntilTick = tickCounter + RETRY_BACKOFF_TICKS;
            }

            // Clear the flag; the !isLocalJoined() guard handles the success
            // case.
            acceptInFlight = false;
        });
    }

    private void attemptBeginMatchIfNeeded()
    {
        if (session == null || beginInFlight || session.isLocalReadyToFight()) return;

        if (statusIs("Fighting") || statusIs("Completed") || statusIs("Canceled"))
        {
            return;
        }

        // Ready-up is only valid after both players have joined and status is
        // Ready (rally generated).
        if (!session.isLocalJoined()) return;
        if (!statusIs("Ready"))
        {
            // Still Pending — opponent hasn't accepted yet; wait for the next
            // poll.
            return;
        }

        if (tickCounter < beginCooldownUntilTick) return;

        // Once status=Ready and the local player has joined, always send
        // /begin-match. The proximity check is only a hint and never blocks the
        // call.
        Player localPlayer = client.getLocalPlayer();
        Player opponent    = (localPlayer != null)
                ? findPlayerByName(session.getOpponentRsn()) : null;
        boolean rallyOrEngaged = false;
        if (localPlayer != null && opponent != null)
        {
            if (session.getRally() != null)
            {
                WorldPoint rallyPoint = new WorldPoint(
                        session.getRally().getX(),
                        session.getRally().getY(),
                        session.getRally().getPlane());
                rallyOrEngaged = isWithinRally(localPlayer, opponent, rallyPoint);
            }
            if (!rallyOrEngaged) rallyOrEngaged = isPlayerEngaged(localPlayer, opponent);
        }

        String token            = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (token == null || token.isEmpty()
                || verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty())
        {
            return;
        }

        beginInFlight = true;
        log.debug("[begin-match] sending — rallyOrEngaged={} status={}",
                rallyOrEngaged, session.getStatus());

        final int       gen       = matchGeneration;
        final String    matchCode = session.getMatchCode();
        final JsonArray inv       = currentInventorySnapshot;
        final JsonArray gear      = currentGearSnapshot;
        final int       overhead  = currentOverheadIconOrdinal;
        final boolean   skulled   = currentIsSkulled;
        final boolean   protect   = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.beginMatch(
                        verificationCode, matchCode, rsn, token, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                log.debug("[begin-match] IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                log.debug("[begin-match] success — status now {}", result.getSession().getStatus());
                session = result.getSession();
                updateResultStatus();
            }
            else
            {
                // Back-off so a transient failure doesn't latch the state.
                String body = result.getRawResponse();
                log.debug("[begin-match] failed — msg='{}' body={}",
                        result.getMessage(),
                        body.length() > 200 ? body.substring(0, 200) : body);
                beginCooldownUntilTick = tickCounter + RETRY_BACKOFF_TICKS;
            }

            beginInFlight = false;
        });
    }

    /**
     * Sends the current gear snapshot to {@code /report-items}.
     *
     * <p>Fires when the match transitions to "Fighting" AND whenever the
     * player's inventory or equipment changes mid-fight (detected via
     * {@link #onItemContainerChanged}).</p>
     */
    private void reportItemsIfNeeded()
    {
        if (session == null || itemsReportInFlight || itemsReported) return;

        if (!statusIs("Fighting")) return;

        String token            = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (token == null || token.isEmpty()
                || verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty())
        {
            return;
        }

        final JsonArray inventoryItems = currentInventorySnapshot != null
                ? currentInventorySnapshot : new JsonArray();
        final JsonArray gearItems      = currentGearSnapshot != null
                ? currentGearSnapshot : new JsonArray();
        final int     overhead = currentOverheadIconOrdinal;
        final boolean skulled  = currentIsSkulled;
        final boolean protect  = currentProtectItem;

        itemsReportInFlight    = true;
        gearChangedDuringFight = false;
        final int    gen       = matchGeneration;
        final String matchCode = session.getMatchCode();

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportItems(
                        verificationCode, matchCode, rsn,
                        token, inventoryItems, gearItems, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            handleResult(result);  // handles token refresh internally

            if (result.isSuccess())
            {
                itemsReported = true;
                if (result.getSession() != null)
                {
                    session = result.getSession();
                    updateResultStatus();
                }
            }

            itemsReportInFlight = false;
        });
    }

    private void refreshToken()
    {
        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty() || session == null)
        {
            return;
        }

        final int       gen       = matchGeneration;
        final String    matchCode = session.getMatchCode();
        final JsonArray inv       = currentInventorySnapshot;
        final JsonArray gear      = currentGearSnapshot;
        final int       overhead  = currentOverheadIconOrdinal;
        final boolean   skulled   = currentIsSkulled;
        final boolean   protect   = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            if (isStale(gen)) return;

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                session = result.getSession();
                updateResultStatus();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private: state + UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Null-safe status check; a server response can carry a session with a null
     * status.
     */
    private boolean statusIs(String expected)
    {
        return session != null
                && session.getStatus() != null
                && session.getStatus().equalsIgnoreCase(expected);
    }

    private void updateResultStatus()
    {
        if (session == null) return;
        if (statusIs("Completed") || statusIs("Canceled"))
        {
            resultReported = true;
        }
    }

    /**
     * Manages the in-world hint arrow.  Behaviour:
     *
     * <ul>
     *   <li>No session or match Completed/Canceled → arrow cleared.</li>
     *   <li>Opponent is rendered (Pending, Ready, or Fighting) → arrow tracks
     *       the opponent player so the local player can see exactly where the
     *       other RuneLite client is in the world.</li>
     *   <li>Opponent is NOT rendered (still travelling, in a different region)
     *       and the server has issued a rally point → arrow falls back to the
     *       rally tile so the player has somewhere to head toward.</li>
     *   <li>Neither available → arrow cleared.</li>
     * </ul>
     *
     * The cached {@code lastHintPlayerName} / {@code lastRallyPoint} avoids
     * spamming {@code setHintArrow} every game tick.
     */
    private void updateHintArrow()
    {
        if (session == null)              { clearHintArrow(); return; }
        if (isMatchCompletedOrCanceled()) { clearHintArrow(); return; }

        // ── 1. Always prefer the opponent in Pending, Ready, and Fighting ────
        Player opponent = findPlayerByName(session.getOpponentRsn());
        if (opponent != null)
        {
            String name = normalizeRsn(opponent.getName());
            if (!name.equalsIgnoreCase(lastHintPlayerName))
            {
                client.setHintArrow(opponent);
                lastHintPlayerName = name;
                lastRallyPoint     = null;
            }
            return;
        }

        // ── 2. Opponent not in render distance — fall back to the rally tile ─
        MatchmakingRally rally = session.getRally();
        if (rally == null) { clearHintArrow(); return; }

        WorldPoint rallyPoint = new WorldPoint(rally.getX(), rally.getY(), rally.getPlane());
        if (!rallyPoint.equals(lastRallyPoint) || lastHintPlayerName != null)
        {
            client.setHintArrow(rallyPoint);
            lastRallyPoint     = rallyPoint;
            lastHintPlayerName = null;
        }
    }

    private void clearHintArrow()
    {
        if (lastRallyPoint != null || lastHintPlayerName != null)
        {
            client.clearHintArrow();
            lastRallyPoint     = null;
            lastHintPlayerName = null;
        }
    }

    /**
     * Returns the cached minimap target (opponent location while Fighting, else
     * the rally tile). Cheap — the value is recomputed once per game tick in
     * {@link #onGameTick()} rather than per render frame.
     */
    public WorldPoint getMinimapTarget()
    {
        return cachedMinimapTarget;
    }

    private WorldPoint computeMinimapTarget()
    {
        if (session == null || isMatchCompletedOrCanceled()) return null;

        if (isMatchFighting())
        {
            Player opponent = findPlayerByName(session.getOpponentRsn());
            if (opponent != null) return opponent.getWorldLocation();
        }

        MatchmakingRally rally = session.getRally();
        if (rally == null) return null;
        return new WorldPoint(rally.getX(), rally.getY(), rally.getPlane());
    }

    private boolean isMatchFighting()
    {
        return statusIs("Fighting");
    }

    private boolean isMatchCompletedOrCanceled()
    {
        return statusIs("Completed") || statusIs("Canceled");
    }

    private boolean isWithinRally(Player localPlayer, Player opponent, WorldPoint rallyPoint)
    {
        if (localPlayer == null || opponent == null || rallyPoint == null) return false;
        WorldPoint lp = localPlayer.getWorldLocation();
        WorldPoint op = opponent.getWorldLocation();
        if (lp.getPlane() != rallyPoint.getPlane() || op.getPlane() != rallyPoint.getPlane()) return false;
        return lp.distanceTo(rallyPoint) <= RALLY_DISTANCE
                && op.distanceTo(rallyPoint) <= RALLY_DISTANCE;
    }

    private boolean isPlayerEngaged(Player localPlayer, Player opponent)
    {
        return localPlayer.getInteracting() == opponent
                || opponent.getInteracting() == localPlayer;
    }

    private Player findPlayerByName(String name)
    {
        String target = normalizeRsn(name);
        if (target.isEmpty()) return null;
        // Normalized comparison: RuneLite's Actor.getName() uses NBSP (U+00A0)
        // while server-side RSNs use regular spaces or underscores.
        for (Player player : client.getPlayers())
        {
            if (player == null) continue;
            if (normalizeRsn(player.getName()).equalsIgnoreCase(target)) return player;
        }
        return null;
    }

    private void handleResult(MatchmakingApiResult result)
    {
        MatchmakingUpdate update = new MatchmakingUpdate(
                result.getSession(),
                result.getMessage(),
                result.getRawResponse(),
                result.isSuccess(),
                result.isTokenRefresh()
        );

        notifyListener(update);

        if (result.isTokenRefresh())
        {
            log.debug("Matchmaking token refresh requested by server");
            refreshToken();
        }
    }

    private void notifyListener(MatchmakingUpdate update)
    {
        if (listener == null) return;
        SwingUtilities.invokeLater(() -> listener.onMatchmakingUpdate(update));
    }

    private String resolveVerificationCode()
    {
        String code = runeAlyticsState.getVerificationCode();
        return (code == null || code.isEmpty()) ? null : code;
    }

    /**
     * Normalize OSRS RSN strings so that RuneLite's Actor.getName() form
     * (which uses U+00A0 non-breaking space) compares equal to profile
     * storage forms that use regular space or underscore.
     */
    private static String normalizeRsn(String name)
    {
        if (name == null) return "";
        return name.replace('\u00A0', ' ').replace('_', ' ').trim();
    }

    private String resolveLocalRsn()
    {
        if (runeAlyticsState.getVerifiedUsername() != null
                && !runeAlyticsState.getVerifiedUsername().isEmpty())
        {
            return runeAlyticsState.getVerifiedUsername();
        }
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
        {
            return client.getLocalPlayer().getName();
        }
        return null;
    }
}
