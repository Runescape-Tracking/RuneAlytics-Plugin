package com.runealytics;

public class MatchmakingRally
{
    private final int x;
    private final int y;
    private final int plane;

    public MatchmakingRally(int x, int y, int plane)
    {
        this.x = x;
        this.y = y;
        this.plane = plane;
    }

    public int getX()
    {
        return x;
    }

    public int getY()
    {
        return y;
    }

    public int getPlane()
    {
        return plane;
    }
}
