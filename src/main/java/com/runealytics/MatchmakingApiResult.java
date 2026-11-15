package com.runealytics;

public class MatchmakingApiResult
{
    private final MatchmakingSession session;
    private final String message;
    private final String rawResponse;
    private final boolean success;
    private final boolean tokenRefresh;

    public MatchmakingApiResult(
            MatchmakingSession session,
            String message,
            String rawResponse,
            boolean success,
            boolean tokenRefresh
    )
    {
        this.session = session;
        // Normalise to empty strings so callers can call .length()/.isEmpty()
        // without null-guarding.
        this.message = message != null ? message : "";
        this.rawResponse = rawResponse != null ? rawResponse : "";
        this.success = success;
        this.tokenRefresh = tokenRefresh;
    }

    public MatchmakingSession getSession()
    {
        return session;
    }

    public String getMessage()
    {
        return message;
    }

    public String getRawResponse()
    {
        return rawResponse;
    }

    public boolean isSuccess()
    {
        return success;
    }

    public boolean isTokenRefresh()
    {
        return tokenRefresh;
    }
}
