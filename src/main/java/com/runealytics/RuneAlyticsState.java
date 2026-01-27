package com.runealytics;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;

@Singleton
@Getter
@Setter
public class RuneAlyticsState
{
    private boolean loggedIn;
    private boolean verified;
    private String verifiedUsername;
    private String verificationCode;
    private int prestige;

    // Additional state for loot tracking
    private boolean syncInProgress;
    private long lastSyncTime;
    private int pendingLootCount;

    public void reset()
    {
        loggedIn = false;
        verified = false;
        verifiedUsername = null;
        verificationCode = null;
        syncInProgress = false;
        lastSyncTime = 0;
        pendingLootCount = 0;
    }

    public boolean canSync()
    {
        return loggedIn && verified && !syncInProgress;
    }

    public void startSync()
    {
        syncInProgress = true;
        lastSyncTime = System.currentTimeMillis();
    }

    public void endSync()
    {
        syncInProgress = false;
    }
}