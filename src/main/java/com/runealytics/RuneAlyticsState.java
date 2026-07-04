package com.runealytics;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
@Getter
@Setter
public class RuneAlyticsState
{
    // These flags/tokens are written on the client thread and read from the
    // background executor and OkHttp callback threads, so they are volatile to
    // guarantee cross-thread visibility.
    private volatile boolean loggedIn;
    private volatile boolean verified;
    private volatile String verifiedUsername;
    private volatile String verificationCode;
    private int prestige;

    // Additional state for loot tracking
    private volatile boolean syncInProgress;
    private volatile long lastSyncTime;

    /**
     * Current game mode resolved at the time of the last loot/XP event.
     * Possible values: "regular", "ironman", "leagues", "deadman",
     * "fresh_start", "grid_master".
     */
    private volatile String currentGameMode = "regular";

    /**
     * Current OSRS account subtype for server-side filtering.
     * Possible values: "normal", "ironman", "hardcore_ironman",
     * "ultimate_ironman", "group_ironman", "hardcore_group_ironman",
     * "unranked_group_ironman".
     */
    private volatile String currentAccountSubtype = "normal";

    /**
     * Most recent local-player location, captured on the client thread at each
     * XP gain. Read off-thread by the API client when the 30s XP batch flushes,
     * so it is {@code volatile}. May be {@code null} (e.g. not logged in) in
     * which case the {@code location} field is simply omitted from the payload.
     */
    private volatile PlayerLocationSnapshot currentLocation;

    /**
     * Players the website says the local player is allowed to see on the live
     * map, refreshed from each {@code /plugin/heartbeat} response. Written on the
     * OkHttp callback thread and read on the client thread by
     * {@link LiveMapMinimapOverlay}, hence {@code volatile} + an immutable list.
     */
    private volatile List<MapPlayer> visibleMapPlayers = Collections.emptyList();

    /**
     * Stores an unmodifiable snapshot so the client-thread overlay can iterate
     * {@link #visibleMapPlayers} without risk of the writing thread mutating the
     * backing list mid-render. Defined explicitly so Lombok's {@code @Setter}
     * does not generate a plain assignment.
     */
    public void setVisibleMapPlayers(List<MapPlayer> players)
    {
        this.visibleMapPlayers = (players == null || players.isEmpty())
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(players));
    }

    public void reset()
    {
        loggedIn = false;
        verified = false;
        verifiedUsername = null;
        verificationCode = null;
        syncInProgress = false;
        lastSyncTime = 0;
        currentGameMode = "regular";
        currentAccountSubtype = "normal";
        currentLocation = null;
        visibleMapPlayers = Collections.emptyList();
    }

    public boolean canSync()
    {
        return loggedIn && verified && !syncInProgress;
    }

    /**
     * Atomically claims the sync slot: returns {@code true} (and marks a sync in
     * progress) only if a sync is allowed and not already running. Callers that
     * succeed MUST call {@link #endSync()} in a {@code finally}. This closes the
     * check-then-act race where two callers both passed {@link #canSync()} and
     * then both started a sync, producing duplicate uploads.
     */
    public synchronized boolean tryStartSync()
    {
        if (!loggedIn || !verified || syncInProgress) return false;
        syncInProgress = true;
        lastSyncTime = System.currentTimeMillis();
        return true;
    }

    public synchronized void endSync()
    {
        syncInProgress = false;
    }
}
