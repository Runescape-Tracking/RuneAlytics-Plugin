package com.runealytics;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * Match Finder sidebar panel.
 *
 * <p>Phase-driven layout:</p>
 * <ol>
 *   <li><b>Idle</b> — code entry + Load Match button</li>
 *   <li><b>Active</b> — Current Match card + Players card</li>
 *   <li><b>Terminal</b> — result trophy card + New Match button</li>
 * </ol>
 */
@Singleton
public class MatchmakingPanel extends RuneAlyticsPanelBase implements MatchmakingUpdateListener
{
    // ── colour palette (mirrors RuneAlyticsSettingsPanel) ────────────────────
    private static final Color BG           = new Color(38, 38, 38);
    private static final Color CARD_BG      = new Color(27, 27, 28);
    private static final Color CARD_BORDER  = new Color(70, 70, 74);
    private static final Color TEAL_COLOR   = new Color(82, 196, 196);
    private static final Color COL_WHITE    = Color.WHITE;
    private static final Color COL_MUTED    = new Color(220, 220, 220);
    private static final Color COL_DIM      = new Color(190, 190, 190);

    private static final Color COL_GREEN    = new Color(72, 199, 116);
    private static final Color COL_RED      = new Color(220, 80,  80);
    private static final Color COL_BLUE     = new Color(13,  110, 253);
    private static final Color COL_ORANGE   = ColorScheme.BRAND_ORANGE;
    private static final Color COL_GRAY_TAG = new Color(80,  80,  80);

    // status badge colours
    private static final Color STATUS_PENDING   = new Color(200, 140,   0);
    private static final Color STATUS_READY     = new Color( 50, 160,  90);
    private static final Color STATUS_FIGHTING  = new Color(200,  55,  55);
    private static final Color STATUS_COMPLETED = new Color( 30, 130, 200);
    private static final Color STATUS_CANCELED  = new Color( 90,  90,  90);

    // font helper (matches RuneAlyticsUi / RuneAlyticsSettingsPanel)
    private static Font cf(int style, float size)
    { return new Font("Calibri", style, Math.round(size)); }

    // ── dependencies ─────────────────────────────────────────────────────────
    private final MatchmakingManager matchmakingManager;
    private final RuneAlyticsState   state;
    private final ItemManager        itemManager;

    private static final Color RISK_GREEN  = new Color(64, 200, 120);
    private static final Color SUMMARY_BG   = new Color(45, 38, 20);
    private static final Color SUMMARY_LINE = new Color(120, 95, 35);

    // ── entry-card widgets ────────────────────────────────────────────────────
    private final JTextField codeInput     = RuneAlyticsUi.inputField();
    private final JButton    loadButton    = buildBlueButton("Load Match");
    private final JLabel     entryStatus   = new JLabel(" ");

    // ── current-match-card widgets ────────────────────────────────────────────
    private final JPanel  matchCard        = vertPanel();
    private final JLabel  matchCodeVal     = infoValue("-");
    private final JLabel  worldVal         = infoValue("-");
    private final JLabel  locationVal      = infoValue("-");
    private final JLabel  statusBadge      = new JLabel();

    // ── risk-value display holders (rebuilt every update) ─────────────────────
    // These render the server-computed, purely informational item-value cards.
    private final JPanel  localRiskHolder  = vertPanel();
    private final JPanel  oppRiskHolder    = vertPanel();
    private final JPanel  summaryHolder    = vertPanel();

    // ── players-card widgets ──────────────────────────────────────────────────
    private final JPanel  playersCard      = vertPanel();
    private AvatarLabel   p1Avatar;
    private AvatarLabel   p2Avatar;
    private final JLabel  p1Name           = playerName("-");
    private final JLabel  p2Name           = playerName("-");
    private final JLabel  p1Tag            = playerTag("You");
    private final JLabel  p2Tag            = playerTag("Opponent");
    private final JLabel  p1Status         = readyLabel();
    private final JLabel  p2Status         = readyLabel();

    // ── validation badge per player ───────────────────────────────────────────
    // Shows server-computed compliance state (risk, gear rules).  Colour-coded:
    //   green ✓  — fully compliant
    //   yellow ⚠ — warnings only (honor rules)
    //   red ✗    — one or more errors (e.g. insufficient risk)
    // The text comes directly from the server so the plugin has zero rule logic.
    private final JLabel  p1Validation     = validationLabel();
    private final JLabel  p2Validation     = validationLabel();

    // ── result-card widgets ───────────────────────────────────────────────────
    private final JPanel  resultCard       = vertPanel();
    private final JLabel  resultName       = new JLabel();
    private final JLabel  resultElo        = new JLabel();

    // ── gear-rules strip ─────────────────────────────────────────────────────
    private final JPanel  rulesStrip       = new JPanel(
            new WrapLayout(FlowLayout.LEFT, 4, 3));

    private final JButton newMatchButton   = buildSecondaryButton("New Match");

    private boolean loading;

    // ═════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ═════════════════════════════════════════════════════════════════════════

    @Inject
    public MatchmakingPanel(MatchmakingManager matchmakingManager,
                            RuneAlyticsState   state,
                            ItemManager        itemManager)
    {
        this.matchmakingManager = matchmakingManager;
        this.state              = state;
        this.itemManager        = itemManager;

        setBackground(BG);
        setBorder(new EmptyBorder(10, 12, 10, 12));

        matchmakingManager.setListener(this);
        buildUi();
        wireEvents();
        refreshLoginState();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI construction
    // ═════════════════════════════════════════════════════════════════════════

    private void buildUi()
    {
        add(RuneAlyticsUi.buildPanelHeader("Match Finder"));
        add(RuneAlyticsUi.vSpace(8));

        add(buildLoadCard());
        add(RuneAlyticsUi.vSpace(5));
        add(buildMatchCard());
        add(RuneAlyticsUi.vSpace(5));
        add(buildPlayersCard());
        add(RuneAlyticsUi.vSpace(5));
        add(localRiskHolder);
        add(RuneAlyticsUi.vSpace(5));
        add(oppRiskHolder);
        add(RuneAlyticsUi.vSpace(5));
        add(summaryHolder);
        add(RuneAlyticsUi.vSpace(5));
        add(buildResultCard());
        add(RuneAlyticsUi.vSpace(5));
        add(newMatchButton);
        add(Box.createVerticalGlue());

        localRiskHolder.setAlignmentX(LEFT_ALIGNMENT);
        oppRiskHolder.setAlignmentX(LEFT_ALIGNMENT);
        summaryHolder.setAlignmentX(LEFT_ALIGNMENT);

        hideActiveCards();
    }

    // ── Load card ─────────────────────────────────────────────────────────────

    private JPanel buildLoadCard()
    {
        JPanel body = vertPanel();

        JLabel desc = new JLabel("<html><body style='width:155px; margin:0; padding:0'>Enter a match code from runealytics.com/pvp</body></html>");
        desc.setFont(cf(Font.PLAIN, 11f));
        desc.setForeground(COL_MUTED);
        desc.setAlignmentX(LEFT_ALIGNMENT);

        codeInput.setFont(cf(Font.PLAIN, 13f));
        codeInput.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        codeInput.setAlignmentX(LEFT_ALIGNMENT);

        loadButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        loadButton.setAlignmentX(LEFT_ALIGNMENT);

        entryStatus.setFont(cf(Font.PLAIN, 11f));
        entryStatus.setForeground(COL_MUTED);
        entryStatus.setAlignmentX(LEFT_ALIGNMENT);

        body.add(desc);
        body.add(RuneAlyticsUi.vSpace(4));
        body.add(codeInput);
        body.add(RuneAlyticsUi.vSpace(4));
        body.add(loadButton);
        body.add(RuneAlyticsUi.vSpace(3));
        body.add(entryStatus);

        return sectionCard("⊟", "Load Match", body);
    }

    // ── Current Match card ────────────────────────────────────────────────────

    private JPanel buildMatchCard()
    {
        JPanel body = vertPanel();

        body.add(infoRow("#",   "Match Code", matchCodeVal));
        body.add(RuneAlyticsUi.vSpace(4));
        body.add(infoRow("⊕",  "World",      worldVal));
        body.add(RuneAlyticsUi.vSpace(4));
        body.add(infoRow("◉",  "Location",   locationVal));
        body.add(RuneAlyticsUi.vSpace(5));

        // gear rules strip
        rulesStrip.setOpaque(false);
        rulesStrip.setAlignmentX(LEFT_ALIGNMENT);
        body.add(rulesStrip);
        body.add(RuneAlyticsUi.vSpace(6));

        // status badge row
        styleStatusBadge(statusBadge, "Pending", STATUS_PENDING);
        statusBadge.setAlignmentX(LEFT_ALIGNMENT);
        body.add(statusBadge);

        matchCard.add(sectionCard("⛨", "Current Match", body));
        return matchCard;
    }

    // ── Players card ──────────────────────────────────────────────────────────

    private JPanel buildPlayersCard()
    {
        p1Avatar = new AvatarLabel("?");
        p2Avatar = new AvatarLabel("?");

        JPanel body = vertPanel();
        body.add(buildPlayerRow(p1Avatar, p1Name, p1Tag, p1Status, p1Validation));
        body.add(RuneAlyticsUi.vSpace(4));
        body.add(buildPlayerRow(p2Avatar, p2Name, p2Tag, p2Status, p2Validation));

        playersCard.add(sectionCard("⚐", "Players", body));
        return playersCard;
    }

    private JPanel buildPlayerRow(AvatarLabel avatar, JLabel name, JLabel tag,
                                  JLabel status, JLabel validation)
    {
        // Top sub-row: avatar | name + tag | status
        // The status label now sits to the RIGHT of the name/tag block on the
        // same line, so it does NOT steal horizontal space from the validation
        // label.  Previously the validation lived inside the constrained centre
        // column and was clipped by the EAST status panel.
        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        topRow.setOpaque(false);
        topRow.setAlignmentX(LEFT_ALIGNMENT);

        topRow.add(avatar, BorderLayout.WEST);

        JPanel nameBlock = vertPanel();
        nameBlock.setAlignmentY(Component.CENTER_ALIGNMENT);
        name.setAlignmentX(LEFT_ALIGNMENT);
        tag.setAlignmentX(LEFT_ALIGNMENT);
        nameBlock.add(name);
        nameBlock.add(RuneAlyticsUi.vSpace(1));
        nameBlock.add(tag);
        topRow.add(nameBlock, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(status);
        topRow.add(right, BorderLayout.EAST);

        // Validation label spans the full row width below the top sub-row so
        // it is never truncated by the status label on the right.
        validation.setAlignmentX(LEFT_ALIGNMENT);

        JPanel wrapper = vertPanel();
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        wrapper.add(topRow);
        wrapper.add(RuneAlyticsUi.vSpace(3));
        wrapper.add(validation);

        return wrapper;
    }

    // ── Result card ───────────────────────────────────────────────────────────

    private JPanel buildResultCard()
    {
        JPanel body = vertPanel();

        // large trophy circle
        JPanel trophyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        trophyRow.setOpaque(false);
        trophyRow.setAlignmentX(LEFT_ALIGNMENT);

        TrophyIcon trophy = new TrophyIcon();
        trophyRow.add(trophy);
        trophyRow.add(RuneAlyticsUi.hSpace(8));

        JPanel textCol = vertPanel();
        resultName.setFont(cf(Font.BOLD, 14f));
        resultName.setForeground(COL_GREEN);
        resultName.setAlignmentX(LEFT_ALIGNMENT);
        resultElo.setFont(cf(Font.PLAIN, 11f));
        resultElo.setForeground(COL_MUTED);
        resultElo.setAlignmentX(LEFT_ALIGNMENT);
        textCol.add(resultName);
        textCol.add(RuneAlyticsUi.vSpace(2));
        textCol.add(resultElo);

        trophyRow.add(textCol);
        body.add(trophyRow);

        resultCard.add(sectionCard("", "", body));
        return resultCard;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Event wiring
    // ═════════════════════════════════════════════════════════════════════════

    private void wireEvents()
    {
        loadButton.addActionListener(e  -> submitMatchCode());
        codeInput.addActionListener(e   -> submitMatchCode());
        newMatchButton.addActionListener(e -> resetToIdle());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Login state (called by plugin on every login / logout)
    // ═════════════════════════════════════════════════════════════════════════

    public void refreshLoginState()
    {
        SwingUtilities.invokeLater(this::updateEntryCard);
    }

    private void updateEntryCard()
    {
        if (!state.isLoggedIn())
        {
            setEntryEnabled(false);
            setEntryStatus("Log in to RuneLite to use Match Finder.", COL_DIM);
            return;
        }
        if (!state.isVerified())
        {
            setEntryEnabled(false);
            setEntryStatus("Verify your account on the Settings tab first.", COL_DIM);
            return;
        }
        if (loading)
        {
            setEntryEnabled(false);
            setEntryStatus("Loading match…", COL_ORANGE);
            return;
        }
        if (matchmakingManager.hasActiveMatch())
        {
            setEntryEnabled(false);
            setEntryStatus("Match active — see below.", COL_GREEN);
            return;
        }

        setEntryEnabled(true);
        setEntryStatus("Ready.", COL_DIM);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Submit / reset
    // ═════════════════════════════════════════════════════════════════════════

    private void submitMatchCode()
    {
        if (!state.isLoggedIn() || !state.isVerified())
        {
            updateEntryCard();
            return;
        }

        String code = codeInput.getText().trim();
        if (code.isEmpty())
        {
            setEntryStatus("Please enter a match code.", COL_RED);
            return;
        }

        loading = true;
        hideActiveCards();
        updateEntryCard();
        matchmakingManager.loadMatch(code);
    }

    private void resetToIdle()
    {
        matchmakingManager.reset();
        hideActiveCards();
        codeInput.setText("");
        loading = false;
        updateEntryCard();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  MatchmakingUpdateListener
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onMatchmakingUpdate(MatchmakingUpdate update)
    {
        loading = false;

        if (!update.isSuccess() || update.getSession() == null)
        {
            String msg = update.getMessage();
            if (msg == null) msg = "";

            // If a match is already active, a transient poll or background-call
            // failure should be shown in the entry status bar but MUST NOT hide
            // the active match cards — that would make the whole UI disappear on
            // every blip (the root cause of the "death not reported" UX problem).
            if (matchmakingManager.hasActiveMatch())
            {
                if (!msg.isEmpty())
                {
                    setEntryStatus(msg, COL_RED);
                }
                return;
            }

            // No active match — this is an explicit load failure; reset to idle.
            // updateEntryCard() is called first so it restores the enabled state
            // and sets "Ready." — then we immediately override the status label
            // with the actual error so it is the last thing written and stays visible.
            hideActiveCards();
            updateEntryCard();
            setEntryStatus(msg.isEmpty() ? "Failed to load match." : msg, COL_RED);
            return;
        }

        applySession(update.getSession());
        updateEntryCard();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Session rendering
    // ═════════════════════════════════════════════════════════════════════════

    private void applySession(MatchmakingSession s)
    {
        // ── match info card ──────────────────────────────────────────────────
        matchCodeVal.setText(s.getMatchCode() != null ? s.getMatchCode() : "-");
        worldVal.setText(s.getWorld() > 0 ? String.valueOf(s.getWorld()) : "-");
        locationVal.setText(s.getZone() != null && !s.getZone().isEmpty() ? s.getZone() : "-");

        // gear rules pills
        rulesStrip.removeAll();
        String rules = s.getGearRules();
        if (rules != null && !rules.isEmpty())
        {
            // gear_rules arrives as a JSON array string like ["No Overheads","DDS Only"]
            // Strip brackets/quotes and split
            rules = rules.replaceAll("[\\[\\]\"]", "");
            for (String rule : rules.split(","))
            {
                rule = rule.trim();
                if (!rule.isEmpty())
                    rulesStrip.add(rulePill(rule));
            }
        }
        rulesStrip.revalidate();
        rulesStrip.repaint();

        String status = s.getStatus() != null ? s.getStatus() : "Unknown";
        applyStatus(statusBadge, status);

        // ── players card ─────────────────────────────────────────────────────
        String localRsn = s.getLocalRsn();
        boolean localIsP1 = s.getPlayer1Username() != null
                && s.getPlayer1Username().equalsIgnoreCase(localRsn);

        String myName   = localIsP1 ? s.getPlayer1Username() : s.getPlayer2Username();
        String oppName  = localIsP1 ? s.getPlayer2Username() : s.getPlayer1Username();
        boolean myJoined  = localIsP1 ? s.isPlayer1Joined()          : s.isPlayer2Joined();
        boolean oppJoined = localIsP1 ? s.isPlayer2Joined()          : s.isPlayer1Joined();
        boolean myReady   = localIsP1 ? s.isPlayer1ReadyToFight()    : s.isPlayer2ReadyToFight();
        boolean oppReady  = localIsP1 ? s.isPlayer2ReadyToFight()    : s.isPlayer1ReadyToFight();

        String p1n = myName  != null && !myName.isEmpty()  ? myName  : "—";
        String p2n = oppName != null && !oppName.isEmpty() ? oppName : "—";

        p1Name.setText(p1n);
        p2Name.setText(p2n);
        p1Avatar.setInitial(p1n.charAt(0));
        p2Avatar.setInitial(p2n.charAt(0));

        applyPlayerStatus(p1Status, myJoined,  myReady,  status);
        applyPlayerStatus(p2Status, oppJoined, oppReady, status);

        // ── validation badges (server-driven — zero rule logic here) ─────────
        MatchmakingSession.PlayerValidation myVal  = localIsP1
                ? s.getPlayer1Validation() : s.getPlayer2Validation();
        MatchmakingSession.PlayerValidation oppVal = localIsP1
                ? s.getPlayer2Validation() : s.getPlayer1Validation();
        applyValidation(p1Validation, myVal);
        applyValidation(p2Validation, oppVal);

        // ── risk-value cards (server-computed, informational only) ───────────
        renderRiskCard(localRiskHolder, "Your Risk Value", p1n, s.getLocalRisk(),    true);
        renderRiskCard(oppRiskHolder,   "Opponent Risk",   p2n, s.getOpponentRisk(), false);
        renderSummaryCard(summaryHolder, s.getLocalRisk(), s.getOpponentRisk(), s.getMatchTotalRiskLabel());

        localRiskHolder.setVisible(true);
        oppRiskHolder.setVisible(true);
        summaryHolder.setVisible(true);

        // ── result card ──────────────────────────────────────────────────────
        boolean terminal = status.equalsIgnoreCase("Completed")
                || status.equalsIgnoreCase("Canceled");

        if (terminal && s.getWinner() != null
                && s.getWinner().getOsrsRsn() != null
                && !s.getWinner().getOsrsRsn().isEmpty())
        {
            resultName.setText(s.getWinner().getOsrsRsn() + " wins!");
            resultName.setForeground(COL_GREEN);
            int elo = s.getWinner().getElo();
            resultElo.setText(elo > 0 ? "ELO: " + elo : "");
            resultCard.setVisible(true);
        }
        else if (status.equalsIgnoreCase("Canceled"))
        {
            resultName.setText("Match canceled");
            resultName.setForeground(COL_MUTED);
            resultElo.setText("");
            resultCard.setVisible(true);
        }
        else
        {
            resultCard.setVisible(false);
        }

        newMatchButton.setVisible(terminal);
        matchCard.setVisible(true);
        playersCard.setVisible(true);

        revalidate();
        repaint();
    }

    private void applyStatus(JLabel badge, String status)
    {
        Color bg;
        String icon;
        switch (status.toLowerCase())
        {
            case "pending":   bg = STATUS_PENDING;   icon = "●"; break;
            case "ready":     bg = STATUS_READY;     icon = "↗"; break;
            case "fighting":  bg = STATUS_FIGHTING;  icon = "⚔"; break;
            case "completed": bg = STATUS_COMPLETED; icon = "✓"; break;
            case "canceled":  bg = STATUS_CANCELED;  icon = "✕"; break;
            default:          bg = STATUS_CANCELED;  icon = "?"; break;
        }
        styleStatusBadge(badge, icon + "  " + capitalize(status), bg);
    }

    /**
     * Updates the validation label for one player from the server's compliance
     * result.  The plugin contains zero rule logic — it simply renders what
     * the server returns.
     *
     * <ul>
     *   <li>Green  ✓  — no issues (or only warnings)  → valid</li>
     *   <li>Yellow ⚠  — warnings only (honor rules)    → technically valid</li>
     *   <li>Red    ✗  — one or more errors             → invalid</li>
     * </ul>
     */
    private void applyValidation(JLabel lbl, MatchmakingSession.PlayerValidation validation)
    {
        if (validation == null || validation == MatchmakingSession.VALIDATION_UNKNOWN)
        {
            lbl.setText("");
            return;
        }

        // ── Violation grace countdown — shown with high-urgency orange ────────
        // The server injects a "violation_grace" issue at the front of the list
        // when a countdown is active.  We render it before other error checks so
        // it's always the most prominent message while the timer is running.
        MatchmakingSession.ValidationIssue graceIssue =
                validation.firstIssueByCode("violation_grace");
        if (graceIssue != null)
        {
            lbl.setText("<html><div width=\"220\">\u23F1 " + escapeHtml(graceIssue.getMessage()) + "</div></html>");
            lbl.setForeground(new Color(230, 120, 0)); // orange
            return;
        }

        String firstError   = validation.firstErrorMessage();
        String firstWarning = validation.firstWarningMessage();

        // Validation now sits below the player row (full-panel width) so we
        // can use a wider div — 220px fits the standard plugin panel width.
        if (firstError != null)
        {
            lbl.setText("<html><div width=\"220\">\u2717 " + escapeHtml(firstError) + "</div></html>");
            lbl.setForeground(COL_RED);
        }
        else if (firstWarning != null)
        {
            lbl.setText("<html><div width=\"220\">\u26A0 " + escapeHtml(firstWarning) + "</div></html>");
            lbl.setForeground(new Color(230, 180, 40));
        }
        else
        {
            lbl.setText("\u2713 Rules met");
            lbl.setForeground(COL_GREEN);
        }
    }

    private static String escapeHtml(String s)
    {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void applyPlayerStatus(JLabel lbl, boolean joined, boolean ready, String matchStatus)
    {
        if (matchStatus.equalsIgnoreCase("Fighting")
                || matchStatus.equalsIgnoreCase("Completed")
                || matchStatus.equalsIgnoreCase("Canceled"))
        {
            lbl.setText("● Ready");
            lbl.setForeground(COL_GREEN);
            return;
        }
        if (ready)
        {
            lbl.setText("● Ready");
            lbl.setForeground(COL_GREEN);
        }
        else if (joined)
        {
            lbl.setText("● Joined");
            lbl.setForeground(COL_ORANGE);
        }
        else
        {
            lbl.setText("○ Waiting");
            lbl.setForeground(COL_DIM);
        }
    }

    private void hideActiveCards()
    {
        matchCard.setVisible(false);
        playersCard.setVisible(false);
        localRiskHolder.setVisible(false);
        oppRiskHolder.setVisible(false);
        summaryHolder.setVisible(false);
        resultCard.setVisible(false);
        newMatchButton.setVisible(false);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Risk-value card rendering (server-computed — informational only)
    //
    //  The plugin performs ZERO valuation logic.  Every number, label and item
    //  list is computed on the server (MatchItemValuationService) and rendered
    //  verbatim here.  This lets the website change valuation / retention rules
    //  on the fly without shipping a new plugin build.
    // ═════════════════════════════════════════════════════════════════════════

    /** Max item tiles shown inline before collapsing the rest behind a "+N" tile. */
    private static final int RISK_TILE_SLOTS = 4;

    private void renderRiskCard(JPanel holder, String title, String playerName,
                                MatchmakingSession.RiskInfo risk, boolean local)
    {
        holder.removeAll();
        if (risk == null || risk == MatchmakingSession.RISK_UNKNOWN)
        {
            holder.setVisible(false);
            return;
        }

        JPanel body = vertPanel();

        // ── player header ────────────────────────────────────────────────────
        JLabel header = new JLabel(playerName + (local ? "  (You)" : "  (Opponent)"));
        header.setFont(cf(Font.BOLD, 12f));
        header.setForeground(local ? COL_BLUE.brighter() : COL_ORANGE);
        header.setAlignmentX(LEFT_ALIGNMENT);
        body.add(header);
        body.add(RuneAlyticsUi.vSpace(4));

        // ── skull status + kept-count line (labels come fully formed) ─────────
        JPanel skullRow = new JPanel(new BorderLayout(6, 0));
        skullRow.setOpaque(false);
        skullRow.setAlignmentX(LEFT_ALIGNMENT);
        skullRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel skull = new JLabel(risk.getSkullLabel());
        skull.setFont(cf(Font.PLAIN, 11f));
        skull.setForeground(COL_DIM);

        JLabel kept = new JLabel(risk.getKeptLabel());
        kept.setFont(cf(Font.PLAIN, 11f));
        kept.setForeground(COL_DIM);

        skullRow.add(skull, BorderLayout.WEST);
        skullRow.add(kept, BorderLayout.EAST);
        body.add(skullRow);
        body.add(RuneAlyticsUi.vSpace(6));

        // ── RISK VALUE headline ───────────────────────────────────────────────
        JLabel rv = new JLabel("RISK VALUE");
        rv.setFont(cf(Font.BOLD, 9f));
        rv.setForeground(COL_DIM);
        rv.setAlignmentX(LEFT_ALIGNMENT);
        body.add(rv);

        JLabel rvVal = new JLabel(risk.getRiskValueLabel());
        rvVal.setFont(cf(Font.BOLD, 20f));
        rvVal.setForeground(RISK_GREEN);
        rvVal.setAlignmentX(LEFT_ALIGNMENT);
        body.add(rvVal);
        body.add(RuneAlyticsUi.vSpace(6));

        // ── most valuable kept item (local card only, matches mockup) ─────────
        if (local && risk.getMostValuableKept() != null)
        {
            JLabel mv = new JLabel("Most Valuable Kept Item");
            mv.setFont(cf(Font.PLAIN, 10f));
            mv.setForeground(COL_DIM);
            mv.setAlignmentX(LEFT_ALIGNMENT);
            body.add(mv);
            body.add(RuneAlyticsUi.vSpace(2));
            body.add(itemRowWithLabel(risk.getMostValuableKept()));
            body.add(RuneAlyticsUi.vSpace(6));
        }

        // ── item grid: "At Risk" for local, "Top Kept Items" for opponent ────
        if (local && !risk.getAtRiskItems().isEmpty())
        {
            JLabel atr = new JLabel("At Risk (Will Be Lost)");
            atr.setFont(cf(Font.PLAIN, 10f));
            atr.setForeground(COL_RED);
            atr.setAlignmentX(LEFT_ALIGNMENT);
            body.add(atr);
            body.add(RuneAlyticsUi.vSpace(3));
            body.add(itemGrid(risk.getAtRiskItems()));
            body.add(RuneAlyticsUi.vSpace(6));
        }
        else if (!local && !risk.getKeptItems().isEmpty())
        {
            JLabel tk = new JLabel("Top Kept Items");
            tk.setFont(cf(Font.PLAIN, 10f));
            tk.setForeground(COL_DIM);
            tk.setAlignmentX(LEFT_ALIGNMENT);
            body.add(tk);
            body.add(RuneAlyticsUi.vSpace(3));
            body.add(itemGrid(risk.getKeptItems()));
            body.add(RuneAlyticsUi.vSpace(6));
        }

        // ── total-risk footer ─────────────────────────────────────────────────
        JPanel totalRow = new JPanel(new BorderLayout(6, 0));
        totalRow.setOpaque(false);
        totalRow.setAlignmentX(LEFT_ALIGNMENT);
        totalRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel tl = new JLabel("Total Risk");
        tl.setFont(cf(Font.PLAIN, 11f));
        tl.setForeground(COL_MUTED);

        JLabel tv = new JLabel(risk.getRiskValueLabel());
        tv.setFont(cf(Font.BOLD, 11f));
        tv.setForeground(COL_WHITE);

        totalRow.add(tl, BorderLayout.WEST);
        totalRow.add(tv, BorderLayout.EAST);
        body.add(totalRow);

        holder.add(sectionCard("\u2696", title, body));
        holder.setVisible(true);
        holder.revalidate();
        holder.repaint();
    }

    private void renderSummaryCard(JPanel holder,
                                   MatchmakingSession.RiskInfo local,
                                   MatchmakingSession.RiskInfo opp,
                                   String totalLabel)
    {
        holder.removeAll();

        JPanel body = vertPanel();

        JPanel split = new JPanel(new GridLayout(1, 2, 8, 0));
        split.setOpaque(false);
        split.setAlignmentX(LEFT_ALIGNMENT);
        split.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        split.add(summaryCol("Your Risk",
                local != null ? local.getRiskValueLabel() : "0 gp"));
        split.add(summaryCol("Opponent Risk",
                opp != null ? opp.getRiskValueLabel() : "0 gp"));
        body.add(split);
        body.add(RuneAlyticsUi.vSpace(6));

        JPanel banner = new JPanel();
        banner.setLayout(new BoxLayout(banner, BoxLayout.Y_AXIS));
        banner.setBackground(SUMMARY_BG);
        banner.setAlignmentX(LEFT_ALIGNMENT);
        banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        banner.setBorder(new CompoundBorder(
                new LineBorder(SUMMARY_LINE, 1, true),
                new EmptyBorder(6, 8, 6, 8)));

        JLabel tlbl = new JLabel("Total Risk in Match");
        tlbl.setFont(cf(Font.PLAIN, 10f));
        tlbl.setForeground(COL_MUTED);
        tlbl.setAlignmentX(CENTER_ALIGNMENT);

        JLabel tval = new JLabel(totalLabel != null ? totalLabel : "0 gp");
        tval.setFont(cf(Font.BOLD, 16f));
        tval.setForeground(COL_ORANGE);
        tval.setAlignmentX(CENTER_ALIGNMENT);

        banner.add(tlbl);
        banner.add(tval);
        body.add(banner);
        body.add(RuneAlyticsUi.vSpace(4));

        JLabel foot = new JLabel("<html><div width=\"220\">Values calculated using OSRS Wild Item Retention Rules</div></html>");
        foot.setFont(cf(Font.ITALIC, 9f));
        foot.setForeground(COL_DIM);
        foot.setAlignmentX(LEFT_ALIGNMENT);
        body.add(foot);

        holder.add(sectionCard("\u2694", "Match Summary", body));
        holder.setVisible(true);
        holder.revalidate();
        holder.repaint();
    }

    private JPanel summaryCol(String label, String value)
    {
        JPanel col = vertPanel();
        JLabel l = new JLabel(label);
        l.setFont(cf(Font.PLAIN, 10f));
        l.setForeground(COL_DIM);
        l.setAlignmentX(LEFT_ALIGNMENT);

        JLabel v = new JLabel(value);
        v.setFont(cf(Font.BOLD, 13f));
        v.setForeground(COL_RED);
        v.setAlignmentX(LEFT_ALIGNMENT);

        col.add(l);
        col.add(v);
        return col;
    }

    // ── item rendering helpers ─────────────────────────────────────────────────

    /** A labelled item row: [icon] name ............. value (right-aligned). */
    private JPanel itemRowWithLabel(MatchmakingSession.RiskItem item)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(itemIcon(item));

        JLabel name = new JLabel(item.getName());
        name.setFont(cf(Font.PLAIN, 11f));
        name.setForeground(COL_MUTED);
        left.add(name);

        JLabel val = new JLabel(item.getValueLabel());
        val.setFont(cf(Font.BOLD, 11f));
        val.setForeground(COL_WHITE);

        row.add(left, BorderLayout.WEST);
        row.add(val, BorderLayout.EAST);
        return row;
    }

    /**
     * Builds a wrapping grid of item tiles.  When there are more items than
     * {@link #RISK_TILE_SLOTS} the overflow collapses behind a clickable "+N"
     * tile that expands a second grid below (per the user's requested UX).
     */
    private JPanel itemGrid(List<MatchmakingSession.RiskItem> items)
    {
        JPanel container = vertPanel();
        container.setAlignmentX(LEFT_ALIGNMENT);

        JPanel inline = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 3));
        inline.setOpaque(false);
        inline.setAlignmentX(LEFT_ALIGNMENT);

        JPanel overflow = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 3));
        overflow.setOpaque(false);
        overflow.setAlignmentX(LEFT_ALIGNMENT);
        overflow.setVisible(false);

        boolean overflowing = items.size() > RISK_TILE_SLOTS;
        int inlineCount = overflowing ? RISK_TILE_SLOTS - 1 : items.size();

        for (int i = 0; i < inlineCount; i++)
            inline.add(iconTile(items.get(i)));

        if (overflowing)
        {
            int remaining = items.size() - inlineCount;
            inline.add(plusTile(remaining, overflow));
            for (int i = inlineCount; i < items.size(); i++)
                overflow.add(iconTile(items.get(i)));
        }

        container.add(inline);
        container.add(overflow);
        return container;
    }

    /** A bordered square holding one item icon, with a value tooltip on hover. */
    private JComponent iconTile(MatchmakingSession.RiskItem item)
    {
        JLabel tile = itemIcon(item);
        tile.setHorizontalAlignment(SwingConstants.CENTER);
        tile.setVerticalAlignment(SwingConstants.CENTER);
        tile.setPreferredSize(new Dimension(34, 34));
        tile.setOpaque(true);
        tile.setBackground(new Color(40, 40, 44));
        tile.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(1, 1, 1, 1)));
        return tile;
    }

    /** The "+N" tile that toggles the overflow grid open/closed. */
    private JComponent plusTile(int remaining, JPanel overflow)
    {
        JLabel tile = new JLabel("+" + remaining);
        tile.setHorizontalAlignment(SwingConstants.CENTER);
        tile.setFont(cf(Font.BOLD, 12f));
        tile.setForeground(COL_MUTED);
        tile.setPreferredSize(new Dimension(34, 34));
        tile.setOpaque(true);
        tile.setBackground(new Color(55, 55, 60));
        tile.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(1, 1, 1, 1)));
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tile.setToolTipText("Show " + remaining + " more item" + (remaining == 1 ? "" : "s"));
        tile.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                boolean show = !overflow.isVisible();
                overflow.setVisible(show);
                tile.setText(show ? "\u2212" : "+" + remaining);
                overflow.revalidate();
                overflow.repaint();
                MatchmakingPanel.this.revalidate();
                MatchmakingPanel.this.repaint();
            }
        });
        return tile;
    }

    /** Creates a JLabel bound to the (async) item image with a value tooltip. */
    private JLabel itemIcon(MatchmakingSession.RiskItem item)
    {
        JLabel lbl = new JLabel();
        lbl.setPreferredSize(new Dimension(32, 32));
        lbl.setToolTipText(item.getTooltip());
        if (item.getId() > 0)
        {
            AsyncBufferedImage img = itemManager.getImage(
                    item.getId(), Math.max(1, item.getQty()), item.getQty() > 1);
            img.addTo(lbl);
        }
        return lbl;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Builder helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Wraps content in a card panel with an icon + title section header,
     * consistent with the RuneAlyticsSettingsPanel step-card style.
     */
    private JPanel sectionCard(String icon, String title, JPanel body)
    {
        JPanel card = new AutoHeightPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG);
        card.setOpaque(true);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1),
                new EmptyBorder(10, 10, 10, 10)));

        if (!title.isEmpty())
        {
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            header.setOpaque(false);
            header.setAlignmentX(LEFT_ALIGNMENT);

            if (!icon.isEmpty())
            {
                JLabel iconLbl = new JLabel(icon);
                iconLbl.setFont(cf(Font.BOLD, 11f));
                iconLbl.setForeground(TEAL_COLOR);
                header.add(iconLbl);
                header.add(RuneAlyticsUi.hSpace(5));
            }

            JLabel titleLbl = new JLabel(title);
            titleLbl.setFont(cf(Font.BOLD, 11f));
            titleLbl.setForeground(TEAL_COLOR);
            header.add(titleLbl);

            card.add(header);
            card.add(RuneAlyticsUi.vSpace(8));
        }

        card.add(body);
        return card;
    }

    private JPanel infoRow(String icon, String label, JLabel value)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);

        JLabel iconLbl = new JLabel(icon);
        iconLbl.setFont(cf(Font.PLAIN, 11f));
        iconLbl.setForeground(COL_DIM);
        iconLbl.setPreferredSize(new Dimension(14, 16));

        JLabel keyLbl = new JLabel(label);
        keyLbl.setFont(cf(Font.PLAIN, 12f));
        keyLbl.setForeground(COL_MUTED);

        left.add(iconLbl);
        left.add(RuneAlyticsUi.hSpace(4));
        left.add(keyLbl);

        row.add(left, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    // ── static factory helpers ────────────────────────────────────────────────

    private static JLabel validationLabel()
    {
        JLabel lbl = new JLabel("");
        lbl.setFont(cf(Font.PLAIN, 10f));
        lbl.setForeground(COL_DIM);
        return lbl;
    }

    private static JPanel vertPanel()
    {
        JPanel p = new AutoHeightPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel infoValue(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(cf(Font.BOLD, 12f));
        l.setForeground(COL_WHITE);
        return l;
    }

    private static JLabel playerName(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(cf(Font.BOLD, 13f));
        l.setForeground(COL_WHITE);
        return l;
    }

    private static JLabel playerTag(String text)
    {
        JLabel l = new JLabel(text)
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = text.equalsIgnoreCase("You") ? COL_BLUE : COL_GRAY_TAG;
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(cf(Font.BOLD, 10f));
        l.setForeground(COL_WHITE);
        l.setOpaque(false);
        l.setBorder(new EmptyBorder(1, 6, 1, 6));
        return l;
    }

    private static JLabel readyLabel()
    {
        JLabel l = new JLabel("○ Waiting");
        l.setFont(cf(Font.PLAIN, 11f));
        l.setForeground(COL_DIM);
        return l;
    }

    private static void styleStatusBadge(JLabel badge, String text, Color bg)
    {
        badge.setText("  " + text + "  ");
        badge.setFont(cf(Font.BOLD, 11f));
        badge.setForeground(COL_WHITE);
        badge.setOpaque(true);
        badge.setBackground(bg);
        badge.setBorder(new CompoundBorder(
                new LineBorder(bg.darker(), 1, true),
                new EmptyBorder(2, 6, 2, 6)));
    }

    private static JLabel rulePill(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(cf(Font.PLAIN, 10f));
        l.setForeground(new Color(200, 200, 200));
        l.setOpaque(true);
        l.setBackground(new Color(55, 55, 60));
        l.setBorder(new CompoundBorder(
                new LineBorder(new Color(70, 70, 75), 1, true),
                new EmptyBorder(1, 5, 1, 5)));
        return l;
    }

    private static JButton buildBlueButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFont(cf(Font.BOLD, 13f));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(COL_BLUE);
        btn.setForeground(COL_WHITE);
        btn.setBorder(new CompoundBorder(
                new LineBorder(COL_BLUE.darker(), 1, true),
                new EmptyBorder(5, 12, 5, 12)));
        return btn;
    }

    private static JButton buildSecondaryButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFont(cf(Font.PLAIN, 12f));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(new Color(50, 50, 50));
        btn.setForeground(COL_WHITE);
        btn.setAlignmentX(LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        btn.setBorder(new CompoundBorder(
                new LineBorder(new Color(80, 80, 80), 1, true),
                new EmptyBorder(5, 12, 5, 12)));
        return btn;
    }

    // ── entry card helpers ────────────────────────────────────────────────────

    private void setEntryEnabled(boolean enabled)
    {
        codeInput.setEnabled(enabled);
        loadButton.setEnabled(enabled);
    }

    private void setEntryStatus(String msg, Color color)
    {
        entryStatus.setText(msg);
        entryStatus.setForeground(color);
    }

    // ── formatting ────────────────────────────────────────────────────────────

    private static String capitalize(String s)
    {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Inner classes
    // ═════════════════════════════════════════════════════════════════════════

    /** Circular avatar with a single initial letter, matching the mockup style. */
    private static class AvatarLabel extends JComponent
    {
        private char initial;
        private static final int SIZE = 36;

        AvatarLabel(String name)
        {
            this.initial = name != null && !name.isEmpty() ? Character.toUpperCase(name.charAt(0)) : '?';
            Dimension d = new Dimension(SIZE, SIZE);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        void setInitial(char c)
        {
            this.initial = Character.toUpperCase(c);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            // circle background
            g2.setColor(new Color(55, 55, 65));
            g2.fillOval(0, 0, SIZE, SIZE);
            g2.setColor(new Color(80, 80, 95));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(0, 0, SIZE - 1, SIZE - 1);

            // initial letter
            g2.setColor(COL_WHITE);
            g2.setFont(new Font("Calibri", Font.BOLD, 15));
            FontMetrics fm = g2.getFontMetrics();
            String s = String.valueOf(initial);
            int x = (SIZE - fm.stringWidth(s)) / 2;
            int y = (SIZE - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(s, x, y);
            g2.dispose();
        }
    }

    /** Green trophy circle, drawn entirely in code (no image dependency). */
    private static class TrophyIcon extends JComponent
    {
        private static final int SIZE = 40;

        TrophyIcon()
        {
            Dimension d = new Dimension(SIZE, SIZE);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(new Color(30, 80, 50));
            g2.fillOval(0, 0, SIZE, SIZE);
            g2.setColor(COL_GREEN);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(0, 0, SIZE - 1, SIZE - 1);

            g2.setColor(COL_GREEN);
            g2.setFont(new Font("Calibri", Font.BOLD, 20));
            FontMetrics fm = g2.getFontMetrics();
            String s = "🏆";
            // fallback to text glyph for environments without emoji font
            if (fm.stringWidth(s) == 0) s = "W";
            int x = (SIZE - fm.stringWidth(s)) / 2;
            int y = (SIZE - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(s, x, y);
            g2.dispose();
        }
    }

    /**
     * FlowLayout variant that wraps items to a new row when the container
     * is too narrow, rather than clipping.  Used for the gear-rules pill strip.
     */
    private static class WrapLayout extends FlowLayout
    {
        WrapLayout(int align, int hgap, int vgap)
        { super(align, hgap, vgap); }

        @Override
        public Dimension preferredLayoutSize(Container target)
        { return layoutSize(target, true); }

        @Override
        public Dimension minimumLayoutSize(Container target)
        { return layoutSize(target, false); }

        private Dimension layoutSize(Container target, boolean preferred)
        {
            synchronized (target.getTreeLock())
            {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

                int hgap = getHgap(), vgap = getVgap();
                Insets insets = target.getInsets();
                int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);

                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0, rowHeight = 0;

                int nmembers = target.getComponentCount();
                for (int i = 0; i < nmembers; i++)
                {
                    Component m = target.getComponent(i);
                    if (m.isVisible())
                    {
                        Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                        if (rowWidth + d.width > maxWidth && rowWidth > 0)
                        {
                            dim.width = Math.max(dim.width, rowWidth);
                            dim.height += rowHeight + vgap;
                            rowWidth = 0;
                            rowHeight = 0;
                        }
                        rowWidth += d.width + hgap;
                        rowHeight = Math.max(rowHeight, d.height);
                    }
                }
                dim.width  = Math.max(dim.width, rowWidth);
                dim.height += rowHeight + insets.top + insets.bottom + vgap * 2;
                return dim;
            }
        }
    }

    private static final class AutoHeightPanel extends JPanel
    {
        @Override
        public Dimension getMaximumSize()
        {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
    }

    @Override
    public void onDataRefresh() { }
}
