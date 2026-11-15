package com.runealytics;

import com.runealytics.MatchmakingSession.PlayerValidation;
import com.runealytics.MatchmakingSession.RiskInfo;
import com.runealytics.MatchmakingSession.RiskItem;
import com.runealytics.MatchmakingSession.ValidationIssue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exhaustive coverage of the matchmaking session model: RSN normalization,
 * local/opponent resolution, and the nested validation/risk value types.
 */
public class MatchmakingSessionTest
{
    private static MatchmakingSession session(String local, String p1, String p2)
    {
        return new MatchmakingSession(
                "CODE", local, p1, p2,
                true, false, true, false,
                301, "Edge", "Ready", "any",
                null, null, "token", "expires");
    }

    // ── RSN normalization ────────────────────────────────────────────────────

    @Test
    public void constructor_normalizesNbspUnderscoreAndTrims()
    {
        MatchmakingSession s = session("  Zez\u00A0ima  ", "Zez_ima", "Foo");
        assertEquals("Zez ima", s.getLocalRsn());
        assertEquals("Zez ima", s.getPlayer1Username());
    }

    @Test
    public void constructor_preservesNullLocalRsn()
    {
        MatchmakingSession s = session(null, "A", "B");
        assertNull(s.getLocalRsn());
    }

    @Test
    public void constructor_nullValidations_defaultToUnknown()
    {
        MatchmakingSession s = session("A", "A", "B");
        assertSame(MatchmakingSession.VALIDATION_UNKNOWN, s.getPlayer1Validation());
        assertSame(MatchmakingSession.VALIDATION_UNKNOWN, s.getPlayer2Validation());
    }

    // ── local / opponent resolution ──────────────────────────────────────────

    @Test
    public void localAccessors_whenLocalIsPlayer1()
    {
        MatchmakingSession s = session("Zezima", "Zezima", "Durial321");
        assertEquals("Durial321", s.getOpponentRsn());
        assertTrue(s.isLocalJoined());
        assertTrue(s.isLocalReadyToFight());
    }

    @Test
    public void localAccessors_whenLocalIsPlayer2()
    {
        MatchmakingSession s = session("Durial321", "Zezima", "Durial321");
        assertEquals("Zezima", s.getOpponentRsn());
        // player2Joined=false, player2ReadyToFight=false in the fixture.
        assertFalse(s.isLocalJoined());
        assertFalse(s.isLocalReadyToFight());
    }

    @Test
    public void localAccessors_areCaseInsensitiveAfterNormalization()
    {
        MatchmakingSession s = session("zez_ima", "ZEZ IMA", "Other");
        assertEquals("Other", s.getOpponentRsn());
        assertTrue(s.isLocalJoined());
    }

    @Test
    public void localAccessors_nullLocal_returnFalsyDefaults()
    {
        MatchmakingSession s = session(null, "A", "B");
        assertNull(s.getOpponentRsn());
        assertFalse(s.isLocalJoined());
        assertFalse(s.isLocalReadyToFight());
        assertSame(MatchmakingSession.RISK_UNKNOWN, s.getLocalRisk());
        assertSame(MatchmakingSession.VALIDATION_UNKNOWN, s.getLocalValidation());
    }

    @Test
    public void localAccessors_localMatchesNeither_returnUnknownDefaults()
    {
        MatchmakingSession s = session("Stranger", "A", "B");
        assertNull(s.getOpponentRsn());
        assertSame(MatchmakingSession.RISK_UNKNOWN, s.getLocalRisk());
        assertSame(MatchmakingSession.RISK_UNKNOWN, s.getOpponentRisk());
        assertSame(MatchmakingSession.VALIDATION_UNKNOWN, s.getOpponentValidation());
    }

    // ── risk data ─────────────────────────────────────────────────────────────

    @Test
    public void setRiskData_nullsFallBackToSentinels()
    {
        MatchmakingSession s = session("A", "A", "B");
        s.setRiskData(null, null, 500L, null);
        assertSame(MatchmakingSession.RISK_UNKNOWN, s.getPlayer1Risk());
        assertSame(MatchmakingSession.RISK_UNKNOWN, s.getPlayer2Risk());
        assertEquals(500L, s.getMatchTotalRiskValue());
        assertEquals("0 gp", s.getMatchTotalRiskLabel());
    }

    @Test
    public void riskAccessors_resolveByLocalIdentity()
    {
        RiskInfo p1Risk = new RiskInfo(true, false, 1, "Skulled", "1 kept",
                100L, "100 gp", 90L, "90 gp", null,
                Collections.emptyList(), Collections.emptyList());
        RiskInfo p2Risk = new RiskInfo(false, true, 2, "Unskulled", "2 kept",
                200L, "200 gp", 10L, "10 gp", null,
                Collections.emptyList(), Collections.emptyList());

        MatchmakingSession s = session("Zezima", "Zezima", "Durial321");
        s.setRiskData(p1Risk, p2Risk, 300L, "300 gp");

        assertSame(p1Risk, s.getLocalRisk());
        assertSame(p2Risk, s.getOpponentRisk());
    }

    // ── ValidationIssue ────────────────────────────────────────────────────────

    @Test
    public void validationIssue_nullSeverityDefaultsToError()
    {
        ValidationIssue issue = new ValidationIssue("C1", "msg", null);
        assertEquals("error", issue.getSeverity());
        assertTrue(issue.isError());
        assertFalse(issue.isWarning());
    }

    @Test
    public void validationIssue_severityIsCaseInsensitive()
    {
        assertTrue(new ValidationIssue("c", "m", "ERROR").isError());
        assertTrue(new ValidationIssue("c", "m", "Warning").isWarning());
    }

    @Test
    public void validationIssue_unknownSeverityIsNeither()
    {
        ValidationIssue issue = new ValidationIssue("c", "m", "info");
        assertFalse(issue.isError());
        assertFalse(issue.isWarning());
    }

    // ── PlayerValidation ───────────────────────────────────────────────────────

    @Test
    public void playerValidation_nullIssues_becomeEmptyImmutableList()
    {
        PlayerValidation pv = new PlayerValidation(true, null);
        assertTrue(pv.getIssues().isEmpty());
        try
        {
            pv.getIssues().add(new ValidationIssue("c", "m", "error"));
            fail("issues list should be unmodifiable");
        }
        catch (UnsupportedOperationException expected)
        {
            // expected
        }
    }

    @Test
    public void playerValidation_firstErrorAndWarningMessages()
    {
        List<ValidationIssue> issues = Arrays.asList(
                new ValidationIssue("w1", "warn one", "warning"),
                new ValidationIssue("e1", "err one", "error"),
                new ValidationIssue("e2", "err two", "error"));
        PlayerValidation pv = new PlayerValidation(false, issues);

        assertEquals("err one", pv.firstErrorMessage());
        assertEquals("warn one", pv.firstWarningMessage());
    }

    @Test
    public void playerValidation_firstMessages_nullWhenAbsent()
    {
        PlayerValidation onlyWarnings = new PlayerValidation(false,
                Arrays.asList(new ValidationIssue("w", "w", "warning")));
        assertNull(onlyWarnings.firstErrorMessage());
        assertEquals("w", onlyWarnings.firstWarningMessage());
    }

    @Test
    public void playerValidation_firstIssueByCode()
    {
        List<ValidationIssue> issues = Arrays.asList(
                new ValidationIssue("dup", "first", "error"),
                new ValidationIssue("dup", "second", "error"));
        PlayerValidation pv = new PlayerValidation(false, issues);

        assertNull(pv.firstIssueByCode(null));
        assertNull(pv.firstIssueByCode("missing"));
        assertEquals("first", pv.firstIssueByCode("dup").getMessage());
    }

    @Test
    public void playerValidation_hasOnlyWarnings()
    {
        assertFalse(new PlayerValidation(true, Collections.emptyList()).hasOnlyWarnings());

        assertTrue(new PlayerValidation(false,
                Arrays.asList(new ValidationIssue("w", "w", "warning"))).hasOnlyWarnings());

        assertFalse(new PlayerValidation(false,
                Arrays.asList(new ValidationIssue("e", "e", "error"))).hasOnlyWarnings());

        // invalid but empty issues → vacuously no errors → true
        assertTrue(new PlayerValidation(false, Collections.emptyList()).hasOnlyWarnings());
    }

    // ── RiskItem ────────────────────────────────────────────────────────────────

    @Test
    public void riskItem_nullNameAndLabelBecomeEmpty()
    {
        RiskItem item = new RiskItem(4151, null, 1, 100L, null);
        assertEquals("", item.getName());
        assertEquals("", item.getValueLabel());
    }

    @Test
    public void riskItem_tooltip_includesQuantitySuffixOnlyWhenAboveOne()
    {
        assertEquals("Abyssal whip — 3m", new RiskItem(4151, "Abyssal whip", 1, 3_000_000L, "3m").getTooltip());
        assertEquals("Coins x100 — 100 gp", new RiskItem(995, "Coins", 100, 100L, "100 gp").getTooltip());
        // qty 0 is not > 1, so no suffix.
        assertEquals("Nothing — 0 gp", new RiskItem(0, "Nothing", 0, 0L, "0 gp").getTooltip());
    }

    // ── RiskInfo ─────────────────────────────────────────────────────────────────

    @Test
    public void riskInfo_nullLabelsAndListsGetDefaults()
    {
        RiskInfo info = new RiskInfo(true, false, 2, null, null,
                50L, null, 40L, null, null, null, null);
        assertEquals("", info.getSkullLabel());
        assertEquals("", info.getKeptLabel());
        assertEquals("0 gp", info.getTotalValueLabel());
        assertEquals("0 gp", info.getRiskValueLabel());
        assertTrue(info.getKeptItems().isEmpty());
        assertTrue(info.getAtRiskItems().isEmpty());
        assertNull(info.getMostValuableKept());
    }

    @Test
    public void riskInfo_listsAreUnmodifiable()
    {
        List<RiskItem> kept = new ArrayList<>();
        kept.add(new RiskItem(1, "a", 1, 1L, "1"));
        RiskInfo info = new RiskInfo(false, false, 1, "s", "k",
                1L, "1", 1L, "1", null, kept, Collections.emptyList());
        try
        {
            info.getKeptItems().add(new RiskItem(2, "b", 1, 1L, "1"));
            fail("kept items should be unmodifiable");
        }
        catch (UnsupportedOperationException expected)
        {
            // expected
        }
    }

    @Test
    public void riskUnknownSentinel_isEmptyAndZeroLabelled()
    {
        RiskInfo unknown = MatchmakingSession.RISK_UNKNOWN;
        assertEquals("0 gp", unknown.getTotalValueLabel());
        assertEquals("0 gp", unknown.getRiskValueLabel());
        assertTrue(unknown.getKeptItems().isEmpty());
        assertFalse(unknown.isSkulled());
    }
}
