package com.runealytics;

/**
 * Immutable snapshot of account/session context for a synchronization operation.
 * Protects against stale asynchronous results from overwriting a different account's
 * or a different session's data after logout, login, or account switch.
 *
 * Each async task captures this at start time and validates it before applying results.
 */
public final class SyncContext
{
    private final String username;
    private final String accountHash;
    private final long sessionGeneration;
    private final long capturedAtMs;
    private final String reason;

    public SyncContext(String username, String accountHash, long sessionGeneration, String reason)
    {
        this.username = username;
        this.accountHash = accountHash;
        this.sessionGeneration = sessionGeneration;
        this.capturedAtMs = System.currentTimeMillis();
        this.reason = reason;
    }

    public String getUsername() { return username; }
    public String getAccountHash() { return accountHash; }
    public long getSessionGeneration() { return sessionGeneration; }
    public long getCapturedAtMs() { return capturedAtMs; }
    public String getReason() { return reason; }

    /**
     * Check if this context is still valid given current state.
     * Returns false if account has switched or plugin has logged out.
     */
    public boolean isStillValid(RuneAlyticsState currentState)
    {
        return currentState != null
            && currentState.isLoggedIn()
            && currentState.isVerified()
            && username != null
            && username.equals(currentState.getVerifiedUsername())
            && sessionGeneration == currentState.getSessionGeneration();
    }

    @Override
    public String toString()
    {
        return String.format("SyncContext{user='%s' hash='%s' gen=%d reason='%s' age=%dms}",
            username, accountHash, sessionGeneration, reason,
            System.currentTimeMillis() - capturedAtMs);
    }
}
