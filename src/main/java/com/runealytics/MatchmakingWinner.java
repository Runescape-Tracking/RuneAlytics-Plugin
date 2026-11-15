package com.runealytics;

public class MatchmakingWinner
{
    private final String osrsRsn;
    private final int combatLevel;
    private final int elo;

    public MatchmakingWinner(String osrsRsn, int combatLevel, int elo)
    {
        this.osrsRsn = osrsRsn;
        this.combatLevel = combatLevel;
        this.elo = elo;
    }

    public String getOsrsRsn()
    {
        return osrsRsn;
    }

    public int getCombatLevel()
    {
        return combatLevel;
    }

    public int getElo()
    {
        return elo;
    }
}
