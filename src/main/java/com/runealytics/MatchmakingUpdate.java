package com.runealytics;

public class MatchmakingUpdate
{
    private final MatchmakingSession session;
    private final String message;
    private final String rawResponse;
    private final boolean success;
    private final boolean tokenRefresh;

    public MatchmakingUpdate(
            MatchmakingSession session,
            String message,
            String rawResponse,
            boolean success,
            boolean tokenRefresh
    )
    {
        this.session = session;
        this.message = message;
        this.rawResponse = rawResponse;
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
