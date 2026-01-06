package com.runealytics;

public class MatchmakingSession
{
    private final String matchCode;
    private final String localRsn;
    private final String player1Username;
    private final String player2Username;
    private final boolean player1Joined;
    private final boolean player2Joined;
    private final boolean player1ReadyToFight;
    private final boolean player2ReadyToFight;
    private final int world;
    private final String zone;
    private final String status;
    private final String risk;
    private final String gearRules;
    private final MatchmakingRally rally;
    private final MatchmakingWinner winner;
    private final String token;
    private final String tokenExpiresAt;

    public MatchmakingSession(
            String matchCode,
            String localRsn,
            String player1Username,
            String player2Username,
            boolean player1Joined,
            boolean player2Joined,
            boolean player1ReadyToFight,
            boolean player2ReadyToFight,
            int world,
            String zone,
            String status,
            String risk,
            String gearRules,
            MatchmakingRally rally,
            MatchmakingWinner winner,
            String token,
            String tokenExpiresAt
    )
    {
        this.matchCode = matchCode;
        this.localRsn = localRsn;
        this.player1Username = player1Username;
        this.player2Username = player2Username;
        this.player1Joined = player1Joined;
        this.player2Joined = player2Joined;
        this.player1ReadyToFight = player1ReadyToFight;
        this.player2ReadyToFight = player2ReadyToFight;
        this.world = world;
        this.zone = zone;
        this.status = status;
        this.risk = risk;
        this.gearRules = gearRules;
        this.rally = rally;
        this.winner = winner;
        this.token = token;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getMatchCode()
    {
        return matchCode;
    }

    public String getLocalRsn()
    {
        return localRsn;
    }

    public String getPlayer1Username()
    {
        return player1Username;
    }

    public String getPlayer2Username()
    {
        return player2Username;
    }

    public boolean isPlayer1Joined()
    {
        return player1Joined;
    }

    public boolean isPlayer2Joined()
    {
        return player2Joined;
    }

    public boolean isPlayer1ReadyToFight()
    {
        return player1ReadyToFight;
    }

    public boolean isPlayer2ReadyToFight()
    {
        return player2ReadyToFight;
    }

    public int getWorld()
    {
        return world;
    }

    public String getZone()
    {
        return zone;
    }

    public String getStatus()
    {
        return status;
    }

    public String getRisk()
    {
        return risk;
    }

    public String getGearRules()
    {
        return gearRules;
    }

    public MatchmakingRally getRally()
    {
        return rally;
    }

    public MatchmakingWinner getWinner()
    {
        return winner;
    }

    public String getToken()
    {
        return token;
    }

    public String getTokenExpiresAt()
    {
        return tokenExpiresAt;
    }

    public String getLocalToken()
    {
        return token;
    }

    public String getOpponentRsn()
    {
        if (localRsn == null)
        {
            return null;
        }
        if (localRsn.equalsIgnoreCase(player1Username))
        {
            return player2Username;
        }
        if (localRsn.equalsIgnoreCase(player2Username))
        {
            return player1Username;
        }
        return null;
    }

    public boolean isLocalJoined()
    {
        if (localRsn == null)
        {
            return false;
        }
        if (localRsn.equalsIgnoreCase(player1Username))
        {
            return player1Joined;
        }
        if (localRsn.equalsIgnoreCase(player2Username))
        {
            return player2Joined;
        }
        return false;
    }

    public boolean isLocalReadyToFight()
    {
        if (localRsn == null)
        {
            return false;
        }
        if (localRsn.equalsIgnoreCase(player1Username))
        {
            return player1ReadyToFight;
        }
        if (localRsn.equalsIgnoreCase(player2Username))
        {
            return player2ReadyToFight;
        }
        return false;
    }
}
