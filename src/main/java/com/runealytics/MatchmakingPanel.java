package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Match Finder sidebar panel — shows the live status of a PvP match loaded
 * from runealytics.com.  The UI is phase-driven: the displayed card changes
 * as the match progresses through Pending → Ready → Fighting → Completed.
 */
@Singleton
public class MatchmakingPanel extends RuneAlyticsPanelBase implements MatchmakingUpdateListener
{
    // ── phase-colour palette ─────────────────────────────────────────────────
    private static final Color COL_PENDING   = new Color(0xF0A500);
    private static final Color COL_READY     = new Color(0x3A9E5E);
    private static final Color COL_FIGHTING  = new Color(0xCC3333);
    private static final Color COL_COMPLETE  = new Color(0x2986CC);
    private static final Color COL_CANCELED  = new Color(0x888888);
    private static final Color COL_BADGE_FG  = Color.WHITE;

    private static final Color COL_JOINED    = new Color(0x3A9E5E);
    private static final Color COL_WAITING   = new Color(0xF0A500);
    private static final Color COL_DOT_OFF   = new Color(0x555555);

    private static final Color CARD_BG       = new Color(0x2A2A2A);
    private static final Color DIVIDER_COL   = new Color(0x444444);

    // ── fields ───────────────────────────────────────────────────────────────
    private final MatchmakingManager matchmakingManager;
    private final RuneAlyticsState   runeAlyticsState;

    // Entry card (always visible)
    private final JTextField matchCodeField  = new JTextField();
    private final JButton    loadButton      = new JButton("Load Match");
    private final JLabel     entryStatus     = new JLabel(" ");

    // Phase card (shown when a match is active)
    private final JPanel  phaseCard          = new JPanel();
    private final JLabel  matchIdLabel       = new JLabel();
    private final JLabel  metaLabel          = new JLabel();
    private final JLabel  statusBadge        = new JLabel();
    private final JLabel  statusHint         = new JLabel();

    // Player rows
    private final JLabel  p1Name            = new JLabel();
    private final JLabel  p1JoinDot         = new JLabel("●");
    private final JLabel  p1ReadyDot        = new JLabel("●");
    private final JLabel  p2Name            = new JLabel();
    private final JLabel  p2JoinDot         = new JLabel("●");
    private final JLabel  p2ReadyDot        = new JLabel("●");

    // Winner / result row
    private final JPanel  resultRow         = new JPanel();
    private final JLabel  resultLabel       = new JLabel();

    // Reset button (shown on terminal states)
    private final JButton resetButton       = new JButton("New match");

    private boolean loading;

    // ── constructor ──────────────────────────────────────────────────────────

    @Inject
    public MatchmakingPanel(MatchmakingManager matchmakingManager,
                            RuneAlyticsState runeAlyticsState)
    {
        this.matchmakingManager = matchmakingManager;
        this.runeAlyticsState   = runeAlyticsState;

        matchmakingManager.setListener(this);

        buildUi();
        wireEvents();
        refreshLoginState();
    }

    // ── UI construction ──────────────────────────────────────────────────────

    private void buildUi()
    {
        addSectionTitle("Match Finder");
        addSubtitle("PvP Match Tracker");

        add(buildEntryCard());
        add(RuneAlyticsUi.vSpace(8));
        add(buildPhaseCard());
        add(Box.createVerticalGlue());

        showPhaseCard(false);
    }

    private JPanel buildEntryCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG);
        card.setBorder(new EmptyBorder(10, 10, 10, 10));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JLabel instruction = new JLabel("<html>Enter the match code from<br>"
                + "<b>runealytics.com/pvp</b></html>");
        instruction.setForeground(new Color(0xAAAAAA));
        instruction.setFont(instruction.getFont().deriveFont(11f));
        instruction.setAlignmentX(LEFT_ALIGNMENT);

        styleInputField(matchCodeField);
        matchCodeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        matchCodeField.setAlignmentX(LEFT_ALIGNMENT);

        styleLoadButton(loadButton);
        loadButton.setAlignmentX(LEFT_ALIGNMENT);
        loadButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        entryStatus.setAlignmentX(LEFT_ALIGNMENT);
        entryStatus.setFont(entryStatus.getFont().deriveFont(11f));
        entryStatus.setForeground(new Color(0xAAAAAA));

        card.add(instruction);
        card.add(RuneAlyticsUi.vSpace(6));
        card.add(matchCodeField);
        card.add(RuneAlyticsUi.vSpace(6));
        card.add(loadButton);
        card.add(RuneAlyticsUi.vSpace(6));
        card.add(entryStatus);
        return card;
    }

    private JPanel buildPhaseCard()
    {
        phaseCard.setLayout(new BoxLayout(phaseCard, BoxLayout.Y_AXIS));
        phaseCard.setBackground(CARD_BG);
        phaseCard.setBorder(new EmptyBorder(10, 10, 10, 10));
        phaseCard.setAlignmentX(LEFT_ALIGNMENT);

        // Match ID row
        matchIdLabel.setForeground(new Color(0x888888));
        matchIdLabel.setFont(matchIdLabel.getFont().deriveFont(Font.PLAIN, 10f));
        matchIdLabel.setAlignmentX(LEFT_ALIGNMENT);

        // Meta row (World • Zone • Risk)
        metaLabel.setForeground(Color.WHITE);
        metaLabel.setFont(metaLabel.getFont().deriveFont(Font.BOLD, 11f));
        metaLabel.setAlignmentX(LEFT_ALIGNMENT);

        // Status badge
        statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 11f));
        statusBadge.setOpaque(true);
        statusBadge.setBorder(new EmptyBorder(2, 6, 2, 6));
        statusBadge.setAlignmentX(LEFT_ALIGNMENT);

        statusHint.setForeground(new Color(0xAAAAAA));
        statusHint.setFont(statusHint.getFont().deriveFont(Font.ITALIC, 10f));
        statusHint.setAlignmentX(LEFT_ALIGNMENT);

        phaseCard.add(matchIdLabel);
        phaseCard.add(RuneAlyticsUi.vSpace(4));
        phaseCard.add(metaLabel);
        phaseCard.add(RuneAlyticsUi.vSpace(8));
        phaseCard.add(statusBadge);
        phaseCard.add(RuneAlyticsUi.vSpace(4));
        phaseCard.add(statusHint);
        phaseCard.add(RuneAlyticsUi.vSpace(10));
        phaseCard.add(buildDivider());
        phaseCard.add(RuneAlyticsUi.vSpace(8));
        phaseCard.add(buildPlayerRow(p1Name, p1JoinDot, p1ReadyDot, "you"));
        phaseCard.add(RuneAlyticsUi.vSpace(6));
        phaseCard.add(buildPlayerRow(p2Name, p2JoinDot, p2ReadyDot, "opponent"));
        phaseCard.add(RuneAlyticsUi.vSpace(10));
        phaseCard.add(buildDivider());
        phaseCard.add(RuneAlyticsUi.vSpace(8));

        // Result row
        resultRow.setLayout(new BoxLayout(resultRow, BoxLayout.Y_AXIS));
        resultRow.setOpaque(false);
        resultRow.setAlignmentX(LEFT_ALIGNMENT);
        resultLabel.setFont(resultLabel.getFont().deriveFont(Font.BOLD, 12f));
        resultLabel.setAlignmentX(LEFT_ALIGNMENT);
        resultRow.add(resultLabel);
        phaseCard.add(resultRow);
        phaseCard.add(RuneAlyticsUi.vSpace(6));

        // Reset button
        styleResetButton(resetButton);
        resetButton.setAlignmentX(LEFT_ALIGNMENT);
        resetButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        phaseCard.add(resetButton);

        return phaseCard;
    }

    private JPanel buildPlayerRow(JLabel nameLabel, JLabel joinDot, JLabel readyDot, String tag)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(Color.WHITE);

        JLabel tagLabel = new JLabel(tag);
        tagLabel.setForeground(new Color(0x888888));
        tagLabel.setFont(tagLabel.getFont().deriveFont(Font.ITALIC, 10f));

        JPanel nameCol = new JPanel();
        nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
        nameCol.setOpaque(false);
        nameCol.add(nameLabel);
        nameCol.add(tagLabel);

        JPanel dots = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        dots.setOpaque(false);

        joinDot.setFont(joinDot.getFont().deriveFont(9f));
        joinDot.setToolTipText("Joined");
        readyDot.setFont(readyDot.getFont().deriveFont(9f));
        readyDot.setToolTipText("Ready to fight");

        JLabel joinLabel  = new JLabel("J");
        JLabel readyLabel = new JLabel("R");
        joinLabel.setFont(joinLabel.getFont().deriveFont(Font.PLAIN, 8f));
        joinLabel.setForeground(new Color(0x888888));
        readyLabel.setFont(readyLabel.getFont().deriveFont(Font.PLAIN, 8f));
        readyLabel.setForeground(new Color(0x888888));

        dots.add(joinDot);
        dots.add(joinLabel);
        dots.add(Box.createHorizontalStrut(4));
        dots.add(readyDot);
        dots.add(readyLabel);

        row.add(nameCol, BorderLayout.WEST);
        row.add(dots, BorderLayout.EAST);
        return row;
    }

    private JSeparator buildDivider()
    {
        JSeparator sep = new JSeparator();
        sep.setForeground(DIVIDER_COL);
        sep.setBackground(DIVIDER_COL);
        sep.setAlignmentX(LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return sep;
    }

    // ── event wiring ─────────────────────────────────────────────────────────

    private void wireEvents()
    {
        loadButton.addActionListener(e -> submitMatchCode());
        matchCodeField.addActionListener(e -> submitMatchCode());
        resetButton.addActionListener(e -> resetToIdle());
    }

    // ── login / state refresh ─────────────────────────────────────────────────

    public void refreshLoginState()
    {
        updateEntryCard();
    }

    private void updateEntryCard()
    {
        boolean loggedIn = runeAlyticsState.isLoggedIn();
        boolean verified = runeAlyticsState.isVerified();

        if (!loggedIn)
        {
            setEntryEnabled(false);
            setEntryStatus("Log in to RuneLite to use Match Finder.", COL_DOT_OFF);
            return;
        }
        if (!verified)
        {
            setEntryEnabled(false);
            setEntryStatus("Verify your account on the Settings tab first.", COL_DOT_OFF);
            return;
        }
        if (loading)
        {
            setEntryEnabled(false);
            setEntryStatus("Loading match...", COL_WAITING);
            return;
        }
        if (matchmakingManager.hasActiveMatch())
        {
            setEntryEnabled(false);
            setEntryStatus("Match active — see details below.", COL_READY);
            return;
        }

        setEntryEnabled(true);
        setEntryStatus("Ready.", new Color(0x888888));
    }

    // ── submit ────────────────────────────────────────────────────────────────

    private void submitMatchCode()
    {
        if (!runeAlyticsState.isLoggedIn() || !runeAlyticsState.isVerified())
        {
            updateEntryCard();
            return;
        }

        String code = matchCodeField.getText().trim();
        if (code.isEmpty())
        {
            setEntryStatus("Please enter a match code.", COL_FIGHTING);
            return;
        }

        loading = true;
        showPhaseCard(false);
        updateEntryCard();
        matchmakingManager.loadMatch(code);
    }

    private void resetToIdle()
    {
        matchmakingManager.reset();
        showPhaseCard(false);
        matchCodeField.setText("");
        loading = false;
        updateEntryCard();
    }

    // ── MatchmakingUpdateListener ─────────────────────────────────────────────

    @Override
    public void onMatchmakingUpdate(MatchmakingUpdate update)
    {
        loading = false;

        if (!update.isSuccess() || update.getSession() == null)
        {
            String msg = update.getMessage();
            if (msg == null || msg.isEmpty()) msg = "Failed to load match — check your code.";
            setEntryStatus(msg, COL_FIGHTING);
            showPhaseCard(false);
            updateEntryCard();
            return;
        }

        MatchmakingSession s = update.getSession();
        applySession(s);
        updateEntryCard();
    }

    private void applySession(MatchmakingSession s)
    {
        // --- header ---
        matchIdLabel.setText("Match code: " + s.getMatchCode());

        String risk = formatRisk(s.getRisk());
        metaLabel.setText("World " + s.getWorld() + "  ·  " + s.getZone() + "  ·  " + risk);

        // --- player rows ---
        String localRsn    = s.getLocalRsn();
        boolean localIsP1  = s.getPlayer1Username() != null
                && s.getPlayer1Username().equalsIgnoreCase(localRsn);

        String myName   = localIsP1 ? s.getPlayer1Username() : s.getPlayer2Username();
        String oppName  = localIsP1 ? s.getPlayer2Username() : s.getPlayer1Username();
        boolean myJoined  = localIsP1 ? s.isPlayer1Joined()     : s.isPlayer2Joined();
        boolean myReady   = localIsP1 ? s.isPlayer1ReadyToFight(): s.isPlayer2ReadyToFight();
        boolean oppJoined = localIsP1 ? s.isPlayer2Joined()     : s.isPlayer1Joined();
        boolean oppReady  = localIsP1 ? s.isPlayer2ReadyToFight(): s.isPlayer1ReadyToFight();

        p1Name.setText(myName  != null ? myName  : "—");
        p2Name.setText(oppName != null ? oppName : "—");

        setDot(p1JoinDot,  myJoined);
        setDot(p1ReadyDot, myReady);
        setDot(p2JoinDot,  oppJoined);
        setDot(p2ReadyDot, oppReady);

        // --- status badge ---
        String status = s.getStatus() != null ? s.getStatus() : "Unknown";
        applyStatusBadge(status, s);

        // --- result row ---
        MatchmakingWinner winner = s.getWinner();
        boolean terminal = status.equalsIgnoreCase("Completed") || status.equalsIgnoreCase("Canceled");
        resultRow.setVisible(terminal);
        resetButton.setVisible(terminal);

        if (terminal && winner != null && winner.getOsrsRsn() != null && !winner.getOsrsRsn().isEmpty())
        {
            String eloStr = winner.getElo() > 0 ? "  (ELO: " + winner.getElo() + ")" : "";
            resultLabel.setText("🏆 " + winner.getOsrsRsn() + " wins!" + eloStr);
            resultLabel.setForeground(COL_COMPLETE);
        }
        else if (status.equalsIgnoreCase("Canceled"))
        {
            resultLabel.setText("Match was canceled.");
            resultLabel.setForeground(COL_CANCELED);
        }
        else
        {
            resultLabel.setText("");
        }

        showPhaseCard(true);
        phaseCard.revalidate();
        phaseCard.repaint();
    }

    private void applyStatusBadge(String status, MatchmakingSession s)
    {
        Color badgeBg;
        String badgeText;
        String hint;

        switch (status.toLowerCase())
        {
            case "pending":
                badgeBg   = COL_PENDING;
                badgeText = "PENDING";
                hint      = "Waiting for both players to join…";
                break;
            case "ready":
                badgeBg   = COL_READY;
                badgeText = "READY";
                if (s.getRally() != null)
                    hint = "Head to the rally point  ("
                            + s.getRally().getX() + ", " + s.getRally().getY() + ")";
                else
                    hint = "Head to the designated zone";
                break;
            case "fighting":
                badgeBg   = COL_FIGHTING;
                badgeText = "⚔  FIGHTING";
                String opp = s.getOpponentRsn();
                hint = opp != null ? "Opponent: " + opp : "Match in progress";
                break;
            case "completed":
                badgeBg   = COL_COMPLETE;
                badgeText = "COMPLETED";
                hint      = "Match over — see result below";
                break;
            case "canceled":
                badgeBg   = COL_CANCELED;
                badgeText = "CANCELED";
                hint      = "The match was canceled";
                break;
            default:
                badgeBg   = COL_DOT_OFF;
                badgeText = status.toUpperCase();
                hint      = "";
        }

        statusBadge.setText("  " + badgeText + "  ");
        statusBadge.setBackground(badgeBg);
        statusBadge.setForeground(COL_BADGE_FG);
        statusHint.setText(hint);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void showPhaseCard(boolean visible)
    {
        phaseCard.setVisible(visible);
    }

    private void setEntryEnabled(boolean enabled)
    {
        matchCodeField.setEnabled(enabled);
        loadButton.setEnabled(enabled);
    }

    private void setEntryStatus(String msg, Color color)
    {
        entryStatus.setText(msg);
        entryStatus.setForeground(color);
    }

    private void setDot(JLabel dot, boolean active)
    {
        dot.setForeground(active ? COL_JOINED : COL_DOT_OFF);
    }

    private String formatRisk(String raw)
    {
        if (raw == null || raw.isEmpty()) return "No risk";
        try
        {
            long v = Long.parseLong(raw);
            if (v == 0) return "No risk";
            if (v >= 1_000_000) return String.format("%.1fM gp", v / 1_000_000.0);
            if (v >= 1_000)     return String.format("%,d k gp", v / 1_000);
            return v + " gp";
        }
        catch (NumberFormatException ignored)
        {
            return raw;
        }
    }

    private void styleInputField(JTextField field)
    {
        field.setBackground(new Color(0x1A1A1A));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setFont(field.getFont().deriveFont(12f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x555555)),
                new EmptyBorder(4, 6, 4, 6)));
    }

    private void styleLoadButton(JButton btn)
    {
        btn.setBackground(ColorScheme.BRAND_ORANGE);
        btn.setForeground(Color.WHITE);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 12f));
        btn.setBorder(new EmptyBorder(6, 12, 6, 12));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void styleResetButton(JButton btn)
    {
        btn.setBackground(new Color(0x444444));
        btn.setForeground(Color.WHITE);
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
        btn.setBorder(new EmptyBorder(4, 10, 4, 10));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public void onDataRefresh() { }
}
