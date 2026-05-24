package com.runealytics;

import java.util.Collections;
import java.util.List;

public class MatchmakingSession
{
    // ── Server-driven validation result ──────────────────────────────────────

    /**
     * A single compliance issue returned by the server for one player.
     * The plugin never hard-codes rule logic — it just shows whatever the
     * server sends back here.
     */
    public static class ValidationIssue
    {
        private final String code;
        private final String message;
        private final String severity; // "error" | "warning"

        public ValidationIssue(String code, String message, String severity)
        {
            this.code     = code;
            this.message  = message;
            this.severity = severity != null ? severity : "error";
        }

        public String getCode()     { return code; }
        public String getMessage()  { return message; }
        public String getSeverity() { return severity; }
        public boolean isError()    { return "error".equalsIgnoreCase(severity); }
        public boolean isWarning()  { return "warning".equalsIgnoreCase(severity); }
    }

    /**
     * Aggregate compliance state for one player in the match.
     */
    public static class PlayerValidation
    {
        private final boolean valid;
        private final List<ValidationIssue> issues;

        public PlayerValidation(boolean valid, List<ValidationIssue> issues)
        {
            this.valid  = valid;
            this.issues = issues != null ? Collections.unmodifiableList(issues) : Collections.emptyList();
        }

        public boolean isValid()                    { return valid; }
        public List<ValidationIssue> getIssues()    { return issues; }

        /** First error-severity issue message, or null if none. */
        public String firstErrorMessage()
        {
            return issues.stream()
                    .filter(ValidationIssue::isError)
                    .map(ValidationIssue::getMessage)
                    .findFirst()
                    .orElse(null);
        }

        /** First warning-severity issue message, or null if none. */
        public String firstWarningMessage()
        {
            return issues.stream()
                    .filter(ValidationIssue::isWarning)
                    .map(ValidationIssue::getMessage)
                    .findFirst()
                    .orElse(null);
        }

        /** True when there are no error-level issues. */
        public boolean hasOnlyWarnings()
        {
            return !valid && issues.stream().noneMatch(ValidationIssue::isError);
        }
    }

    // ── Static "unknown" sentinel used before first validation arrives ────────
    public static final PlayerValidation VALIDATION_UNKNOWN =
            new PlayerValidation(true, Collections.emptyList());

    // ─────────────────────────────────────────────────────────────────────────
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

    /** Server-computed compliance result for player 1. */
    private final PlayerValidation player1Validation;
    /** Server-computed compliance result for player 2. */
    private final PlayerValidation player2Validation;

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
        this(matchCode, localRsn, player1Username, player2Username,
                player1Joined, player2Joined, player1ReadyToFight, player2ReadyToFight,
                world, zone, status, risk, gearRules, rally, winner, token, tokenExpiresAt,
                VALIDATION_UNKNOWN, VALIDATION_UNKNOWN);
    }

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
            String tokenExpiresAt,
            PlayerValidation player1Validation,
            PlayerValidation player2Validation
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
        this.player1Validation = player1Validation != null ? player1Validation : VALIDATION_UNKNOWN;
        this.player2Validation = player2Validation != null ? player2Validation : VALIDATION_UNKNOWN;
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

    public PlayerValidation getPlayer1Validation()
    {
        return player1Validation;
    }

    public PlayerValidation getPlayer2Validation()
    {
        return player2Validation;
    }

    /**
     * Validation result for the local player (convenience accessor).
     */
    public PlayerValidation getLocalValidation()
    {
        if (localRsn == null) return VALIDATION_UNKNOWN;
        if (localRsn.equalsIgnoreCase(player1Username)) return player1Validation;
        if (localRsn.equalsIgnoreCase(player2Username)) return player2Validation;
        return VALIDATION_UNKNOWN;
    }

    /**
     * Validation result for the opponent (convenience accessor).
     */
    public PlayerValidation getOpponentValidation()
    {
        if (localRsn == null) return VALIDATION_UNKNOWN;
        if (localRsn.equalsIgnoreCase(player1Username)) return player2Validation;
        if (localRsn.equalsIgnoreCase(player2Username)) return player1Validation;
        return VALIDATION_UNKNOWN;
    }
}
