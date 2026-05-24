package com.runealytics;

import com.google.gson.JsonArray;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
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
    private boolean beginInFlight;
    private boolean reportInFlight;
    private boolean itemsReportInFlight;
    private boolean resultReported;
    private boolean itemsReported;
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
        final JsonArray inv  = currentInventorySnapshot;
        final JsonArray gear = currentGearSnapshot;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn, inv, gear);
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
        if (session == null && currentInventorySnapshot == null)
        {
            // No active match yet but still capture snapshots so the very
            // first loadMatch() call already has item data to send.
        }

        // ── Always refresh gear snapshot on the client thread ─────────────────
        refreshSnapshots();

        if (session == null || requestInFlight)
        {
            return;
        }

        tickCounter++;

        updateHintArrow();

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

        // Snapshots are rebuilt on each tick anyway but calling it here
        // ensures the next poll that fires off-thread has the latest data.
        refreshSnapshots();

        // Mark gear as changed during a fight so /report-items fires again
        if (session != null && session.getStatus() != null
                && session.getStatus().equalsIgnoreCase("Fighting"))
        {
            gearChangedDuringFight = true;
            // Reset itemsReported so the next reportItemsIfNeeded() call fires
            itemsReported = false;
        }
    }

    public void reset()
    {
        session                 = null;
        tickCounter             = 0;
        requestInFlight         = false;
        beginInFlight           = false;
        reportInFlight          = false;
        itemsReportInFlight     = false;
        resultReported          = false;
        itemsReported           = false;
        gearChangedDuringFight  = false;
        // Keep snapshots — they reflect the player's current state and are
        // valid across match resets (e.g. New Match).
        clearHintArrow();
    }

    public void onActorDeath(Player player)
    {
        if (session == null || reportInFlight || resultReported) return;
        if (player == null || player.getName() == null) return;

        String deathName = player.getName();
        if (!deathName.equalsIgnoreCase(session.getPlayer1Username())
                && !deathName.equalsIgnoreCase(session.getPlayer2Username()))
        {
            return;
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

        reportInFlight = true;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportMatch(verificationCode, session.getMatchCode(), rsn, token, deathName);
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
            else if (result.isTokenRefresh())
            {
                refreshToken();
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
    private void refreshSnapshots()
    {
        ItemContainer inv  = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);

        currentInventorySnapshot = RuneAlyticsItemJson.fromContainerWithValues(inv,   itemManager);
        currentGearSnapshot      = RuneAlyticsItemJson.fromEquipmentWithValues(equip, itemManager);
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

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn, inv, gear);
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
        if (session == null || session.isLocalJoined()) return;

        String token            = session.getLocalToken();
        if (token == null || token.isEmpty()) return;

        String verificationCode = resolveVerificationCode();
        String rsn              = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty()
                || rsn == null || rsn.isEmpty())
        {
            return;
        }

        // Use current snapshot (always populated by refreshSnapshots above)
        final JsonArray inventory = currentInventorySnapshot != null ? currentInventorySnapshot : new JsonArray();
        final JsonArray gear      = currentGearSnapshot      != null ? currentGearSnapshot      : new JsonArray();

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.acceptMatch(
                        verificationCode, session.getMatchCode(), rsn, token, inventory, gear);
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
            // On token_refresh, the snapshot is re-read on the next game tick
            // before attemptAcceptMatchIfNeeded fires again — no explicit clear needed.
        });
    }

    private void attemptBeginMatchIfNeeded()
    {
        if (session == null || beginInFlight || session.isLocalReadyToFight()) return;

        if (session.getStatus().equalsIgnoreCase("Fighting")
                || session.getStatus().equalsIgnoreCase("Completed")
                || session.getStatus().equalsIgnoreCase("Canceled"))
        {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return;

        String opponentRsn = session.getOpponentRsn();
        Player opponent    = findPlayerByName(opponentRsn);
        if (opponent == null) return;

        boolean shouldBegin = false;
        if (session.getRally() != null)
        {
            WorldPoint rallyPoint = new WorldPoint(
                    session.getRally().getX(),
                    session.getRally().getY(),
                    session.getRally().getPlane());
            shouldBegin = isWithinRally(localPlayer, opponent, rallyPoint);
        }
        if (!shouldBegin) shouldBegin = isPlayerEngaged(localPlayer, opponent);
        if (!shouldBegin) return;

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

        final JsonArray inv  = currentInventorySnapshot;
        final JsonArray gear = currentGearSnapshot;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.beginMatch(
                        verificationCode, session.getMatchCode(), rsn, token, inv, gear);
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

        if (!session.getStatus().equalsIgnoreCase("Fighting")) return;

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

        itemsReportInFlight    = true;
        gearChangedDuringFight = false;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportItems(
                        verificationCode, session.getMatchCode(), rsn,
                        token, inventoryItems, gearItems);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess())
            {
                itemsReported = true;
                if (result.getSession() != null)
                {
                    session = result.getSession();
                    updateResultStatus();
                }
            }
            else if (result.isTokenRefresh())
            {
                refreshToken();
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

        final JsonArray inv  = currentInventorySnapshot;
        final JsonArray gear = currentGearSnapshot;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, session.getMatchCode(), rsn, inv, gear);
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

    private void updateResultStatus()
    {
        if (session == null) return;
        if (session.getStatus().equalsIgnoreCase("Completed")
                || session.getStatus().equalsIgnoreCase("Canceled"))
        {
            resultReported = true;
        }
    }

    private void updateHintArrow()
    {
        if (session == null) { clearHintArrow(); return; }
        if (isMatchCompletedOrCanceled()) { clearHintArrow(); return; }

        if (isMatchFighting())
        {
            Player opponent = findPlayerByName(session.getOpponentRsn());
            if (opponent == null) { clearHintArrow(); return; }
            String name = opponent.getName();
            if (name != null && !name.equalsIgnoreCase(lastHintPlayerName))
            {
                client.setHintArrow(opponent);
                lastHintPlayerName = name;
                lastRallyPoint     = null;
            }
            return;
        }

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
        return session != null && session.getStatus().equalsIgnoreCase("Fighting");
    }

    private boolean isMatchCompletedOrCanceled()
    {
        return session != null
                && (session.getStatus().equalsIgnoreCase("Completed")
                || session.getStatus().equalsIgnoreCase("Canceled"));
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
        if (name == null || name.isEmpty()) return null;
        for (Player player : client.getPlayers())
        {
            if (player != null && name.equalsIgnoreCase(player.getName())) return player;
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
            log.info("Matchmaking token refresh requested by server");
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
