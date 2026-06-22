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
import net.runelite.client.game.ItemManager;
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
    private final ItemManager              itemManager;

    private MatchmakingSession       session;
    private MatchmakingUpdateListener listener;

    private int     tickCounter;
    private boolean requestInFlight;
    private boolean acceptInFlight;
    private boolean beginInFlight;

    /** Tick number when the next accept/begin retry is allowed (back-off). */
    private int     acceptCooldownUntilTick;
    private int     beginCooldownUntilTick;
    private boolean reportInFlight;
    private boolean itemsReportInFlight;
    private boolean resultReported;
    private boolean itemsReported;

    /** True once we have told the server combat started, so we only report once. */
    private boolean combatReported;
    private boolean combatInFlight;
    private WorldPoint lastRallyPoint;
    private String     lastHintPlayerName;

    /**
     * Current enriched inventory snapshot — updated on every game tick and on
     * every {@link ItemContainerChanged} event so the server always receives
     * up-to-date item data for risk/gear validation.
     *
     * <p>Uses {@code ge_per}/{@code total} values so the server can compute
     * the player's total risk without its own price lookup.</p>
     */
    private JsonArray currentInventorySnapshot;

    /**
     * Current enriched equipment snapshot — includes {@code slot}, {@code id},
     * {@code qty}, {@code ge_per}, {@code total}.  The {@code slot} field lets
     * the server identify the weapon slot (3) for gear-rule validation.
     */
    private JsonArray currentGearSnapshot;

    /**
     * Ordinal of the local player's active overhead prayer (HeadIcon.ordinal())
     * or {@code -1} if no overhead is active.  Sent to the server so it can
     * enforce "No Overheads" rules in real-time without any rule logic in the
     * plugin.
     */
    private int currentOverheadIconOrdinal = -1;

    /**
     * Whether the local player currently has a skull icon (is skulled).
     * Sent to the server so it can apply the correct OSRS keep-on-death rules
     * for the informational risk-value display.
     */
    private boolean currentIsSkulled;

    /**
     * Whether the local player currently has the Protect Item prayer active.
     * Combined with skull status server-side to determine how many items are
     * kept on death (0/1/3/4).
     */
    private boolean currentProtectItem;

    /**
     * Set to {@code true} on {@link ItemContainerChanged} while in a Fighting
     * match, so {@link #reportItemsIfNeeded()} sends a fresh snapshot to
     * {@code /report-items} on the next tick.
     */
    private boolean gearChangedDuringFight;

    @Inject
    public MatchmakingManager(
            Client                   client,
            RuneAlyticsState         runeAlyticsState,
            MatchmakingApiClient     apiClient,
            ScheduledExecutorService executorService,
            ItemManager              itemManager
    )
    {
        this.client           = client;
        this.runeAlyticsState = runeAlyticsState;
        this.apiClient        = apiClient;
        this.executorService  = executorService;
        this.itemManager      = itemManager;
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

    public void loadMatch(String matchCode)
    {
        if (requestInFlight) return;

        reset();

        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            notifyListener(new MatchmakingUpdate(null,
                    "Missing verification or RSN. Please re-verify your account.",
                    "", false, false));
            return;
        }

        requestInFlight = true;

        // Snapshot captured before kicking off the load so the server gets
        // an immediate validation result even on the first response.
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

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                session = result.getSession();
                updateResultStatus();
            }

            requestInFlight = false;
        });
    }

    /**
     * Must be called on the <em>client thread</em> every game tick so that
     * all {@link ItemContainer} reads happen safely.
     */
    public void onGameTick()
    {
        // Refresh entire player state (inventory, gear, overhead, skull) on the
        // client thread so every outbound call carries current data.
        // Safe even when there is no active match.
        refreshPlayerState();

        // Always update the hint arrow regardless of in-flight state so the
        // arrow is never stale while waiting for a network response to return.
        if (session != null)
        {
            updateHintArrow();
        }

        if (session == null || requestInFlight)
        {
            return;
        }

        // Once the match is over (Completed/Canceled) there is nothing left to
        // coordinate, so stop ALL server traffic — no polling, accepting,
        // ready-up, combat or item reports.  The final result already lives in
        // `session` and is rendered; continuing to talk to the server would be
        // pointless noise.  (The poll that *discovered* completion set the
        // terminal status, so we naturally stop on the very next tick.)
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

        // Player state is rebuilt on each tick anyway; calling it here ensures
        // the next poll fired off-thread has the latest inventory/gear/status.
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
     * match Ready → Fighting.  Both players merely accepting/readying no longer
     * starts the fight — only an actual hit does.
     *
     * <p>Two directions are accepted, each strictly scoped to the opponent so
     * an NPC or an unrelated player can never trigger the fight:</p>
     * <ul>
     *   <li><b>We hit the opponent</b> — {@code target == opponent} and the
     *       hitsplat is ours ({@link Hitsplat#isMine()}).</li>
     *   <li><b>The opponent hits us</b> — {@code target == localPlayer} and the
     *       opponent is currently interacting with us, so a third party's
     *       splat landing on us is ignored.</li>
     * </ul>
     *
     * <p>Only fires while the match is "Ready" and reports at most once
     * (retried by a later hit if the report fails).</p>
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

        log.info("[engage] combat detected ({}) — reporting Ready → Fighting (match={})",
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

        final JsonArray inv      = currentInventorySnapshot;
        final JsonArray gear     = currentGearSnapshot;
        final int       overhead = currentOverheadIconOrdinal;
        final boolean   skulled  = currentIsSkulled;
        final boolean   protect  = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.engageCombat(
                        verificationCode, session.getMatchCode(), rsn, token, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                log.warn("[engage] IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess())
            {
                combatReported = true;
                if (result.getSession() != null)
                {
                    log.info("[engage] success — status now {}", result.getSession().getStatus());
                    session = result.getSession();
                    updateResultStatus();
                }
            }
            else
            {
                String body = result.getRawResponse();
                log.warn("[engage] failed — msg='{}' body={}",
                        result.getMessage(),
                        body != null && body.length() > 200 ? body.substring(0, 200) : body);
            }

            combatInFlight = false;
        });
    }

    public void reset()
    {
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
        // Keep snapshots — they reflect the player's current state and are
        // valid across match resets (e.g. New Match).
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

        // RuneLite's Actor.getName() returns names with a non-breaking space
        // (U+00A0); OSRS profiles often store regular space or underscore.
        // Normalize ALL three to plain spaces before comparison so the death
        // is correctly attributed to the right participant.
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
            log.warn("[death] skipping — local auth token is null/empty");
            return;
        }
        if (verificationCode == null || verificationCode.isEmpty())
        {
            log.warn("[death] skipping — verification code is null/empty");
            return;
        }
        if (rsn == null || rsn.isEmpty())
        {
            log.warn("[death] skipping — local RSN is null/empty");
            return;
        }

        final String reportedDeath = deathName; // already normalized above

        log.info("[death] reporting death of '{}' (match={} status={})",
                reportedDeath, session.getMatchCode(), session.getStatus());

        reportInFlight = true;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportMatch(verificationCode, session.getMatchCode(), rsn, token, reportedDeath);
            }
            catch (IOException ex)
            {
                log.error("[death] reportMatch IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                log.info("[death] match reported — new status: {}", result.getSession().getStatus());
                session = result.getSession();
                updateResultStatus();
            }
            else if (result.isTokenRefresh())
            {
                refreshToken();
            }
            else
            {
                log.warn("[death] reportMatch failed — success={} msg='{}' body={}",
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
     * from the live {@link ItemContainer}s.  Must be called on the client thread.
     *
     * <p>The inventory includes {@code ge_per}/{@code total} for risk validation.
     * The equipment includes {@code slot} (weapon = 3) and {@code ge_per}/{@code total}
     * for gear-rule validation, both computed via {@link ItemValueResolver}.</p>
     */
    private void refreshPlayerState()
    {
        ItemContainer inv   = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);

        // Lean snapshots — {id, qty} / {slot, id, qty}.  The website prices
        // every item itself, so the plugin no longer computes GE values here.
        currentInventorySnapshot = RuneAlyticsItemJson.fromContainer(inv);
        currentGearSnapshot      = RuneAlyticsItemJson.fromEquipment(equip);

        // Overhead prayer, skull, and Protect Item status — captured here
        // (client thread) so every outbound API call can include them for
        // server-side gear-rule validation and the risk-value display.
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

        // Honour back-off after a previous failure so we don't slam the server
        // with one request per tick.  The flag is cleared after the response,
        // and the natural short-circuit (isLocalJoined()) prevents spam after
        // success — but we still need this for repeated 4xx/5xx scenarios.
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
        log.debug("[accept] sending — status={} rsn={}", session.getStatus(), rsn);

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.acceptMatch(
                        verificationCode, session.getMatchCode(), rsn, token, inventory, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                log.warn("[accept] IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                log.info("[accept] success — status now {}", result.getSession().getStatus());
                session = result.getSession();
                updateResultStatus();
            }
            else
            {
                // ALWAYS schedule a retry on failure so a transient error
                // (422, 5xx, network drop) doesn't permanently latch the
                // match in "Pending".  Without this back-off + clear, the
                // first 422 would freeze the state machine forever.
                String body = result.getRawResponse();
                log.warn("[accept] failed — success={} tokenRefresh={} msg='{}' body={}",
                        result.isSuccess(), result.isTokenRefresh(), result.getMessage(),
                        body.length() > 200 ? body.substring(0, 200) : body);
                acceptCooldownUntilTick = tickCounter + RETRY_BACKOFF_TICKS;
            }

            // ALWAYS clear the flag — the natural !isLocalJoined() guard
            // (updated by the next poll) handles the success-no-retry case.
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

        // We can only ready-up after BOTH players have joined and the server
        // has unlocked the match (status=Ready, rally generated).  Bailing
        // earlier just spams 4xx responses.
        if (!session.isLocalJoined()) return;
        if (!statusIs("Ready"))
        {
            // Still Pending → opponent hasn't accepted yet; wait for the next
            // poll to flip status to Ready.
            return;
        }

        if (tickCounter < beginCooldownUntilTick) return;

        // Original behaviour required the player to be physically at the rally
        // point OR already engaged with the opponent.  That gating was too
        // restrictive — if neither check ever returned true (rally tolerance
        // off, opponent not rendered yet, etc.) the status would stay Ready
        // forever.  Now: once status=Ready and the local player has joined,
        // we always send /begin-match.  The proximity check is still used as
        // a hint but never blocks the call.
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

        final JsonArray inv      = currentInventorySnapshot;
        final JsonArray gear     = currentGearSnapshot;
        final int       overhead = currentOverheadIconOrdinal;
        final boolean   skulled  = currentIsSkulled;
        final boolean   protect  = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.beginMatch(
                        verificationCode, session.getMatchCode(), rsn, token, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                log.warn("[begin-match] IO error: {}", ex.getMessage());
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                log.info("[begin-match] success — status now {}", result.getSession().getStatus());
                session = result.getSession();
                updateResultStatus();
            }
            else
            {
                // Back-off so a transient failure doesn't latch the state.
                String body = result.getRawResponse();
                log.warn("[begin-match] failed — msg='{}' body={}",
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

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportItems(
                        verificationCode, session.getMatchCode(), rsn,
                        token, inventoryItems, gearItems, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

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

        final JsonArray inv      = currentInventorySnapshot;
        final JsonArray gear     = currentGearSnapshot;
        final int       overhead = currentOverheadIconOrdinal;
        final boolean   skulled  = currentIsSkulled;
        final boolean   protect  = currentProtectItem;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, session.getMatchCode(), rsn, inv, gear, overhead, skulled, protect);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

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
     * Null-safe status check: a server response can carry a session with a null
     * status, and calling {@code getStatus().equalsIgnoreCase(...)} on it would
     * NPE on the client thread (onGameTick). Centralised here so every caller is
     * protected.
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

    public WorldPoint getMinimapTarget()
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
        // Use normalized comparison: RuneLite's Actor.getName() returns NBSP
        // (U+00A0) while server-side RSNs use regular spaces or underscores.
        // Without this normalization the opponent lookup silently returns null
        // and the hint arrow / engagement check never fire.
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
