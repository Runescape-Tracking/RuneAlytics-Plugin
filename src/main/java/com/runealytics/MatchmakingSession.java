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

        /** Returns the first issue whose code matches the given value, or null. */
        public ValidationIssue firstIssueByCode(String code)
        {
            if (code == null) return null;
            return issues.stream()
                    .filter(i -> code.equals(i.getCode()))
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
    //  Server-computed risk-value display (no gold wager — informational only)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A single item in a player's kept/at-risk list.  All values are computed
     * server-side; the plugin only renders the icon (by {@link #id}) and the
     * pre-formatted {@link #valueLabel}.
     */
    public static class RiskItem
    {
        private final int    id;
        private final String name;
        private final int    qty;
        private final long   value;
        private final String valueLabel;

        public RiskItem(int id, String name, int qty, long value, String valueLabel)
        {
            this.id         = id;
            this.name       = name != null ? name : "";
            this.qty        = qty;
            this.value      = value;
            this.valueLabel = valueLabel != null ? valueLabel : "";
        }

        public int    getId()         { return id; }
        public String getName()       { return name; }
        public int    getQty()        { return qty; }
        public long   getValue()      { return value; }
        public String getValueLabel() { return valueLabel; }

        /** Tooltip text shown when hovering the item icon. */
        public String getTooltip()
        {
            String q = qty > 1 ? " x" + qty : "";
            return name + q + " — " + valueLabel;
        }
    }

    /**
     * Server-computed risk valuation for one player, ready to render directly.
     */
    public static class RiskInfo
    {
        private final boolean skulled;
        private final boolean protectItem;
        private final int     keptCount;
        private final String  skullLabel;
        private final String  keptLabel;
        private final long    totalValue;
        private final String  totalValueLabel;
        private final long    riskValue;
        private final String  riskValueLabel;
        private final RiskItem mostValuableKept;
        private final List<RiskItem> keptItems;
        private final List<RiskItem> atRiskItems;

        public RiskInfo(boolean skulled, boolean protectItem, int keptCount,
                        String skullLabel, String keptLabel,
                        long totalValue, String totalValueLabel,
                        long riskValue, String riskValueLabel,
                        RiskItem mostValuableKept,
                        List<RiskItem> keptItems, List<RiskItem> atRiskItems)
        {
            this.skulled          = skulled;
            this.protectItem      = protectItem;
            this.keptCount        = keptCount;
            this.skullLabel       = skullLabel  != null ? skullLabel  : "";
            this.keptLabel        = keptLabel    != null ? keptLabel   : "";
            this.totalValue       = totalValue;
            this.totalValueLabel  = totalValueLabel != null ? totalValueLabel : "0 gp";
            this.riskValue        = riskValue;
            this.riskValueLabel   = riskValueLabel  != null ? riskValueLabel  : "0 gp";
            this.mostValuableKept = mostValuableKept;
            this.keptItems        = keptItems   != null ? Collections.unmodifiableList(keptItems)   : Collections.emptyList();
            this.atRiskItems      = atRiskItems != null ? Collections.unmodifiableList(atRiskItems) : Collections.emptyList();
        }

        public boolean isSkulled()            { return skulled; }
        public boolean isProtectItem()        { return protectItem; }
        public int     getKeptCount()         { return keptCount; }
        public String  getSkullLabel()        { return skullLabel; }
        public String  getKeptLabel()         { return keptLabel; }
        public long    getTotalValue()        { return totalValue; }
        public String  getTotalValueLabel()   { return totalValueLabel; }
        public long    getRiskValue()         { return riskValue; }
        public String  getRiskValueLabel()    { return riskValueLabel; }
        public RiskItem getMostValuableKept() { return mostValuableKept; }
        public List<RiskItem> getKeptItems()  { return keptItems; }
        public List<RiskItem> getAtRiskItems(){ return atRiskItems; }
    }

    /** Empty placeholder used before the first risk valuation arrives. */
    public static final RiskInfo RISK_UNKNOWN = new RiskInfo(
            false, false, 0, "", "", 0L, "0 gp", 0L, "0 gp",
            null, Collections.emptyList(), Collections.emptyList());

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
    private final String gearRules;

    // ── Server-computed risk-value display (set after construction) ──────────
    private RiskInfo player1Risk         = RISK_UNKNOWN;
    private RiskInfo player2Risk         = RISK_UNKNOWN;
    private long     matchTotalRiskValue = 0L;
    private String   matchTotalRiskLabel = "0 gp";
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
            String gearRules,
            MatchmakingRally rally,
            MatchmakingWinner winner,
            String token,
            String tokenExpiresAt
    )
    {
        this(matchCode, localRsn, player1Username, player2Username,
                player1Joined, player2Joined, player1ReadyToFight, player2ReadyToFight,
                world, zone, status, gearRules, rally, winner, token, tokenExpiresAt,
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

    /**
     * Stores the server-computed risk valuation for both players plus the
     * match total.  Called by the API client after the session is built.
     */
    public void setRiskData(RiskInfo player1Risk, RiskInfo player2Risk,
                            long matchTotalRiskValue, String matchTotalRiskLabel)
    {
        this.player1Risk         = player1Risk != null ? player1Risk : RISK_UNKNOWN;
        this.player2Risk         = player2Risk != null ? player2Risk : RISK_UNKNOWN;
        this.matchTotalRiskValue = matchTotalRiskValue;
        this.matchTotalRiskLabel = matchTotalRiskLabel != null ? matchTotalRiskLabel : "0 gp";
    }

    public RiskInfo getPlayer1Risk()        { return player1Risk; }
    public RiskInfo getPlayer2Risk()        { return player2Risk; }
    public long     getMatchTotalRiskValue(){ return matchTotalRiskValue; }
    public String   getMatchTotalRiskLabel(){ return matchTotalRiskLabel; }

    /** Risk valuation for the local player. */
    public RiskInfo getLocalRisk()
    {
        if (localRsn == null) return RISK_UNKNOWN;
        if (localRsn.equalsIgnoreCase(player1Username)) return player1Risk;
        if (localRsn.equalsIgnoreCase(player2Username)) return player2Risk;
        return RISK_UNKNOWN;
    }

    /** Risk valuation for the opponent. */
    public RiskInfo getOpponentRisk()
    {
        if (localRsn == null) return RISK_UNKNOWN;
        if (localRsn.equalsIgnoreCase(player1Username)) return player2Risk;
        if (localRsn.equalsIgnoreCase(player2Username)) return player1Risk;
        return RISK_UNKNOWN;
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
