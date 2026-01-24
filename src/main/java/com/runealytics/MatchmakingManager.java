package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
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
    private static final int RALLY_DISTANCE = 15;

    private final Client client;
    private final RuneAlyticsState runeAlyticsState;
    private final MatchmakingApiClient apiClient;
    private final ScheduledExecutorService executorService;

    private MatchmakingSession session;
    private MatchmakingUpdateListener listener;

    private int tickCounter;
    private boolean requestInFlight;
    private boolean beginInFlight;
    private boolean reportInFlight;
    private boolean itemsReportInFlight;
    private boolean resultReported;
    private boolean itemsReported;
    private WorldPoint lastRallyPoint;
    private String lastHintPlayerName;
    private String lastFailureSignature = "";

    @Inject
    public MatchmakingManager(
            Client client,
            RuneAlyticsState runeAlyticsState,
            MatchmakingApiClient apiClient,
            ScheduledExecutorService executorService
    )
    {
        this.client = client;
        this.runeAlyticsState = runeAlyticsState;
        this.apiClient = apiClient;
        this.executorService = executorService;
    }

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
        if (requestInFlight)
        {
            return;
        }

        reset();

        String verificationCode = resolveVerificationCode();
        String rsn = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            notifyListener(new MatchmakingUpdate(null,
                    "Missing verification or RSN. Please re-verify your account.",
                    "",
                    false,
                    false));
            return;
        }

        requestInFlight = true;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                session = result.getSession();
                attemptAcceptMatchIfNeeded();
                updateResultStatus();
            }

            requestInFlight = false;
        });
    }

    public void onGameTick()
    {
        if (session == null || requestInFlight)
        {
            return;
        }

        tickCounter++;

        updateHintArrow();

        if (tickCounter % POLL_INTERVAL_TICKS == 0)
        {
            pollMatch();
        }

        attemptBeginMatchIfNeeded();
        reportItemsIfNeeded();
    }

    public void reset()
    {
        session = null;
        tickCounter = 0;
        requestInFlight = false;
        beginInFlight = false;
        reportInFlight = false;
        itemsReportInFlight = false;
        resultReported = false;
        itemsReported = false;
        lastFailureSignature = "";
        clearHintArrow();
    }

    public void onActorDeath(Player player)
    {
        if (session == null || reportInFlight || resultReported)
        {
            return;
        }

        if (player == null || player.getName() == null)
        {
            return;
        }

        String deathName = player.getName();
        if (!deathName.equalsIgnoreCase(session.getPlayer1Username())
                && !deathName.equalsIgnoreCase(session.getPlayer2Username()))
        {
            return;
        }

        String token = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn = resolveLocalRsn();

        if (token == null || token.isEmpty() || verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
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

    private void pollMatch()
    {
        if (requestInFlight)
        {
            return;
        }

        String verificationCode = resolveVerificationCode();
        String rsn = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            return;
        }

        requestInFlight = true;

        String matchCode = session.getMatchCode();

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, matchCode, rsn);
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess() && result.getSession() != null)
            {
                session = result.getSession();
                attemptAcceptMatchIfNeeded();
                updateResultStatus();
            }

            requestInFlight = false;
        });
    }

    private void attemptAcceptMatchIfNeeded()
    {
        if (session == null || session.isLocalJoined())
        {
            return;
        }

        String token = session.getLocalToken();
        if (token == null || token.isEmpty())
        {
            return;
        }

        String verificationCode = resolveVerificationCode();
        String rsn = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            return;
        }

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.acceptMatch(verificationCode, session.getMatchCode(), rsn, token);
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

    private void attemptBeginMatchIfNeeded()
    {
        if (session == null || beginInFlight || session.isLocalReadyToFight())
        {
            return;
        }

        if (session.getStatus().equalsIgnoreCase("Fighting")
                || session.getStatus().equalsIgnoreCase("Completed")
                || session.getStatus().equalsIgnoreCase("Canceled"))
        {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            return;
        }

        String opponentRsn = session.getOpponentRsn();
        Player opponent = findPlayerByName(opponentRsn);
        if (opponent == null)
        {
            return;
        }

        boolean shouldBegin = false;
        if (session.getRally() != null)
        {
            WorldPoint rallyPoint = new WorldPoint(
                    session.getRally().getX(),
                    session.getRally().getY(),
                    session.getRally().getPlane()
            );
            shouldBegin = isWithinRally(localPlayer, opponent, rallyPoint);
        }

        if (!shouldBegin)
        {
            shouldBegin = isPlayerEngaged(localPlayer, opponent);
        }

        if (!shouldBegin)
        {
            return;
        }

        String token = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn = resolveLocalRsn();

        if (token == null || token.isEmpty() || verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            return;
        }

        beginInFlight = true;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.beginMatch(verificationCode, session.getMatchCode(), rsn, token);
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

    private void reportItemsIfNeeded()
    {
        if (session == null || itemsReportInFlight || itemsReported)
        {
            return;
        }

        if (!session.getStatus().equalsIgnoreCase("Fighting"))
        {
            return;
        }

        String token = session.getLocalToken();
        String verificationCode = resolveVerificationCode();
        String rsn = resolveLocalRsn();

        if (token == null || token.isEmpty() || verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty())
        {
            return;
        }

        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer gear = client.getItemContainer(InventoryID.EQUIPMENT);

        JsonArray inventoryItems = buildItemPayload(inventory);
        JsonArray gearItems = buildItemPayload(gear);

        itemsReportInFlight = true;

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.reportItems(
                        verificationCode,
                        session.getMatchCode(),
                        rsn,
                        token,
                        inventoryItems,
                        gearItems
                );
            }
            catch (IOException ex)
            {
                result = new MatchmakingApiResult(null, ex.getMessage(), "", false, false);
            }

            handleResult(result);

            if (result.isSuccess())
            {
                itemsReported = true;
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
        String rsn = resolveLocalRsn();

        if (verificationCode == null || verificationCode.isEmpty() || rsn == null || rsn.isEmpty() || session == null)
        {
            return;
        }

        executorService.submit(() -> {
            MatchmakingApiResult result;
            try
            {
                result = apiClient.getMatch(verificationCode, session.getMatchCode(), rsn);
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

    private void updateResultStatus()
    {
        if (session == null)
        {
            return;
        }

        if (session.getStatus().equalsIgnoreCase("Completed")
                || session.getStatus().equalsIgnoreCase("Canceled"))
        {
            resultReported = true;
        }
    }

    private void updateHintArrow()
    {
        if (session == null)
        {
            clearHintArrow();
            return;
        }

        if (isMatchCompletedOrCanceled())
        {
            clearHintArrow();
            return;
        }

        if (isMatchFighting())
        {
            Player opponent = findPlayerByName(session.getOpponentRsn());
            if (opponent == null)
            {
                clearHintArrow();
                return;
            }

            String opponentName = opponent.getName();
            if (opponentName != null && !opponentName.equalsIgnoreCase(lastHintPlayerName))
            {
                client.setHintArrow(opponent);
                lastHintPlayerName = opponentName;
                lastRallyPoint = null;
            }
            return;
        }

        MatchmakingRally rally = session.getRally();
        if (rally == null)
        {
            clearHintArrow();
            return;
        }

        WorldPoint rallyPoint = new WorldPoint(rally.getX(), rally.getY(), rally.getPlane());

        if (!rallyPoint.equals(lastRallyPoint) || lastHintPlayerName != null)
        {
            client.setHintArrow(rallyPoint);
            lastRallyPoint = rallyPoint;
            lastHintPlayerName = null;
        }
    }

    private void clearHintArrow()
    {
        if (lastRallyPoint != null || lastHintPlayerName != null)
        {
            client.clearHintArrow();
            lastRallyPoint = null;
            lastHintPlayerName = null;
        }
    }

    public WorldPoint getMinimapTarget()
    {
        if (session == null || isMatchCompletedOrCanceled())
        {
            return null;
        }

        if (isMatchFighting())
        {
            Player opponent = findPlayerByName(session.getOpponentRsn());
            if (opponent != null)
            {
                return opponent.getWorldLocation();
            }
        }

        MatchmakingRally rally = session.getRally();
        if (rally == null)
        {
            return null;
        }

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
        if (localPlayer == null || opponent == null || rallyPoint == null)
        {
            return false;
        }

        WorldPoint localPoint = localPlayer.getWorldLocation();
        WorldPoint opponentPoint = opponent.getWorldLocation();

        if (localPoint.getPlane() != rallyPoint.getPlane()
                || opponentPoint.getPlane() != rallyPoint.getPlane())
        {
            return false;
        }

        return localPoint.distanceTo(rallyPoint) <= RALLY_DISTANCE
                && opponentPoint.distanceTo(rallyPoint) <= RALLY_DISTANCE;
    }

    private boolean isPlayerEngaged(Player localPlayer, Player opponent)
    {
        if (localPlayer.getInteracting() == opponent)
        {
            return true;
        }

        return opponent.getInteracting() == localPlayer;
    }

    private Player findPlayerByName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }

        for (Player player : client.getPlayers())
        {
            if (player != null && name.equalsIgnoreCase(player.getName()))
            {
                return player;
            }
        }

        return null;
    }

    private JsonArray buildItemPayload(ItemContainer container)
    {
        JsonArray items = new JsonArray();

        if (container == null)
        {
            return items;
        }

        Item[] containerItems = container.getItems();
        if (containerItems == null)
        {
            return items;
        }

        for (Item item : containerItems)
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
            {
                JsonObject itemData = new JsonObject();
                itemData.addProperty("id", item.getId());
                itemData.addProperty("qty", item.getQuantity());
                items.add(itemData);
            }
        }

        return items;
    }

    private void handleResult(MatchmakingApiResult result)
    {
        MatchmakingSession sessionForUpdate = result.getSession();
        if (sessionForUpdate == null && result.isSuccess())
        {
            sessionForUpdate = session;
        }

        MatchmakingUpdate update = new MatchmakingUpdate(
                sessionForUpdate,
                result.getMessage(),
                result.getRawResponse(),
                result.isSuccess(),
                result.isTokenRefresh()
        );

        if (shouldNotify(update))
        {
            notifyListener(update);
        }

        if (result.isTokenRefresh())
        {
            log.info("Matchmaking token refresh requested by server");
            refreshToken();
        }
    }

    private boolean shouldNotify(MatchmakingUpdate update)
    {
        if (update.isSuccess())
        {
            lastFailureSignature = "";
            return true;
        }

        String message = update.getMessage() != null ? update.getMessage() : "";
        String rawResponse = update.getRawResponse() != null ? update.getRawResponse() : "";

        boolean htmlFailure = message.equalsIgnoreCase("Matchmaking API returned HTML instead of JSON.")
                || rawResponse.startsWith("<");

        String signature = htmlFailure ? message : message + "|" + rawResponse;
        if (signature.equals(lastFailureSignature))
        {
            return false;
        }

        lastFailureSignature = signature;
        return true;
    }

    private void notifyListener(MatchmakingUpdate update)
    {
        if (listener == null)
        {
            return;
        }

        SwingUtilities.invokeLater(() -> listener.onMatchmakingUpdate(update));
    }

    private String resolveVerificationCode()
    {
        String verificationCode = runeAlyticsState.getVerificationCode();
        if (verificationCode == null || verificationCode.isEmpty())
        {
            return null;
        }
        return verificationCode;
    }

    private String resolveLocalRsn()
    {
        if (runeAlyticsState.getVerifiedUsername() != null && !runeAlyticsState.getVerifiedUsername().isEmpty())
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
