package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

@Singleton
public class MatchmakingPanel extends JPanel implements MatchmakingUpdateListener
{
    private static Font cf(int style, float size) { return new Font("Calibri", style, Math.round(size)); }

    private static final Color TEAL  = new Color(82, 196, 196);
    private static final Color GOLD  = new Color(215, 175, 55);
    private static final Color GREEN = new Color(105, 220, 140);
    private static final Color RED   = new Color(255, 110, 110);
    private static final Color MUTED = new Color(200, 200, 200);
    private static final Color DIM   = new Color(150, 150, 150);

    private final MatchmakingManager matchmakingManager;
    private final RuneAlyticsState   state;

    // Load section
    private JPanel     loadSection;
    private JTextField matchCodeField;
    private JButton    loadButton;

    // Status
    private JLabel statusLabel;

    // Match details
    private JPanel matchSection;
    private JLabel codeVal, worldVal, zoneVal, riskVal, rulesVal, statusVal;

    // Players
    private JPanel playersSection;
    private JLabel p1Label, p2Label, p1ReadyLabel, p2ReadyLabel;

    // Winner
    private JPanel winnerSection;
    private JLabel winnerLabel, eloLabel;

    // Actions
    private JButton newMatchButton;
    private JButton forfeitButton;

    // W/L record
    private JLabel recordLabel;

    private boolean loading;
    private String  lastCompletedMatchCode;

    @Inject
    public MatchmakingPanel(MatchmakingManager matchmakingManager, RuneAlyticsState state)
    {
        this.matchmakingManager = matchmakingManager;
        this.state              = state;

        matchmakingManager.setListener(this);

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        add(buildScrollPane(), BorderLayout.CENTER);

        refreshLoginState();
    }

    private JScrollPane buildScrollPane()
    {
        JScrollPane scroll = new JScrollPane(buildContent());
        scroll.setBorder(null);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        return scroll;
    }

    private JPanel buildContent()
    {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(ColorScheme.DARK_GRAY_COLOR);
        root.setOpaque(true);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));

        // ── Header ──────────────────────────────────────────────────────────────
        JLabel title = new JLabel("Matches");
        title.setFont(cf(Font.BOLD, 17f));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(title);

        JLabel subtitle = new JLabel("PvP Match Tracker");
        subtitle.setFont(cf(Font.PLAIN, 12f));
        subtitle.setForeground(MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(subtitle);

        root.add(vSpace(4));

        recordLabel = new JLabel(buildRecordText());
        recordLabel.setFont(cf(Font.BOLD, 12f));
        recordLabel.setForeground(GOLD);
        recordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(recordLabel);

        root.add(vSpace(10));

        // ── Status ──────────────────────────────────────────────────────────────
        statusLabel = new JLabel(" ");
        statusLabel.setFont(cf(Font.PLAIN, 11f));
        statusLabel.setForeground(MUTED);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(statusLabel);
        root.add(vSpace(4));

        // ── Load section ─────────────────────────────────────────────────────────
        loadSection = buildLoadSection();
        root.add(loadSection);
        root.add(vSpace(8));

        // ── Match details ────────────────────────────────────────────────────────
        matchSection = buildMatchSection();
        root.add(matchSection);
        root.add(vSpace(6));

        // ── Players ──────────────────────────────────────────────────────────────
        playersSection = buildPlayersSection();
        root.add(playersSection);
        root.add(vSpace(6));

        // ── Winner ───────────────────────────────────────────────────────────────
        winnerSection = buildWinnerSection();
        root.add(winnerSection);
        root.add(vSpace(10));

        // ── Actions ──────────────────────────────────────────────────────────────
        root.add(buildActionsSection());
        root.add(vSpace(8));

        root.add(Box.createVerticalGlue());
        return root;
    }

    private JPanel buildLoadSection()
    {
        JPanel card = sectionCard();

        JLabel label = new JLabel("Match Code");
        label.setFont(cf(Font.BOLD, 11f));
        label.setForeground(DIM);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(label);
        card.add(vSpace(4));

        matchCodeField = RuneAlyticsUi.inputField();
        matchCodeField.setAlignmentX(Component.LEFT_ALIGNMENT);
        matchCodeField.addActionListener(e -> submitMatchCode());
        card.add(matchCodeField);
        card.add(vSpace(6));

        loadButton = new JButton("Load Match");
        styleBtn(loadButton, new Color(40, 80, 120), new Color(60, 110, 160), Color.WHITE);
        loadButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        loadButton.addActionListener(e -> submitMatchCode());
        card.add(loadButton);

        return card;
    }

    private JPanel buildMatchSection()
    {
        JPanel card = sectionCard();

        JLabel header = sectionTag("MATCH DETAILS");
        card.add(header);
        card.add(vSpace(6));

        codeVal   = valLabel("—");
        worldVal  = valLabel("—");
        zoneVal   = valLabel("—");
        riskVal   = valLabel("—");
        rulesVal  = valLabel("—");
        statusVal = valLabel("—");

        card.add(kvRow("Code",   codeVal));
        card.add(vSpace(2));
        card.add(kvRow("World",  worldVal));
        card.add(vSpace(2));
        card.add(kvRow("Zone",   zoneVal));
        card.add(vSpace(2));
        card.add(kvRow("Risk",   riskVal));
        card.add(vSpace(2));
        card.add(kvRow("Status", statusVal));
        card.add(vSpace(6));
        card.add(rulesRow());

        card.setVisible(false);
        return card;
    }

    private JPanel buildPlayersSection()
    {
        JPanel card = sectionCard();

        card.add(sectionTag("PLAYERS"));
        card.add(vSpace(6));

        p1Label      = playerLabel("—");
        p2Label      = playerLabel("—");
        p1ReadyLabel = stateLabel("Waiting");
        p2ReadyLabel = stateLabel("Waiting");

        card.add(playerRow(p1Label, p1ReadyLabel));
        card.add(vSpace(3));
        card.add(playerRow(p2Label, p2ReadyLabel));

        card.setVisible(false);
        return card;
    }

    private JPanel buildWinnerSection()
    {
        JPanel card = sectionCard();
        card.setBackground(new Color(25, 45, 25));

        card.add(sectionTag("RESULT"));
        card.add(vSpace(4));

        winnerLabel = new JLabel("—");
        winnerLabel.setFont(cf(Font.BOLD, 14f));
        winnerLabel.setForeground(GREEN);
        winnerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(winnerLabel);

        eloLabel = new JLabel(" ");
        eloLabel.setFont(cf(Font.PLAIN, 11f));
        eloLabel.setForeground(MUTED);
        eloLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(eloLabel);

        card.setVisible(false);
        return card;
    }

    private JPanel buildActionsSection()
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        newMatchButton = new JButton("New Match");
        styleBtn(newMatchButton, new Color(35, 55, 35), new Color(55, 85, 55), GREEN);
        newMatchButton.setVisible(false);
        newMatchButton.addActionListener(e -> onNewMatch());

        forfeitButton = new JButton("Forfeit");
        styleBtn(forfeitButton, new Color(60, 28, 28), new Color(90, 42, 42), RED);
        forfeitButton.setVisible(false);
        forfeitButton.addActionListener(e -> onForfeit());

        row.add(newMatchButton);
        row.add(hSpace(6));
        row.add(forfeitButton);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    // ── Event handlers ─────────────────────────────────────────────────────────

    private void submitMatchCode()
    {
        if (!state.isLoggedIn())
        {
            setStatus("Log into RuneScape to load a match.", false);
            return;
        }
        if (!state.isVerified())
        {
            setStatus("Verify your account before loading a match.", false);
            return;
        }

        String code = matchCodeField.getText().trim();
        if (code.isEmpty())
        {
            setStatus("Enter a match code.", false);
            return;
        }

        loading = true;
        setStatus("Loading match...", null);
        loadButton.setEnabled(false);
        matchCodeField.setEnabled(false);
        matchmakingManager.loadMatch(code);
    }

    private void onNewMatch()
    {
        matchmakingManager.reset();
        loadSection.setVisible(true);
        matchSection.setVisible(false);
        playersSection.setVisible(false);
        winnerSection.setVisible(false);
        forfeitButton.setVisible(false);
        newMatchButton.setVisible(false);
        matchCodeField.setText("");
        matchCodeField.setEnabled(true);
        loadButton.setEnabled(state.isLoggedIn() && state.isVerified());
        setStatus(" ", null);
        revalidate();
        repaint();
    }

    private void onForfeit()
    {
        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "Are you sure you want to forfeit?\nThis gives your opponent the win.",
                "Forfeit Match",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.YES_OPTION)
        {
            forfeitButton.setEnabled(false);
            matchmakingManager.forfeitMatch();
        }
    }

    @Override
    public void onMatchmakingUpdate(MatchmakingUpdate update)
    {
        loading = false;
        loadButton.setEnabled(state.isLoggedIn() && state.isVerified());
        matchCodeField.setEnabled(state.isLoggedIn() && state.isVerified());

        if (update.isSuccess() && update.getSession() != null)
        {
            MatchmakingSession session = update.getSession();
            boolean active = isActiveMatch(session);
            boolean done   = isCompletedOrCanceled(session);

            updateMatchDetails(session);

            loadSection.setVisible(!active);
            matchSection.setVisible(true);
            playersSection.setVisible(true);
            forfeitButton.setVisible(active);
            forfeitButton.setEnabled(true);
            newMatchButton.setVisible(done);

            if (done)
            {
                updateWinnerSection(session);
                winnerSection.setVisible(true);
                forfeitButton.setVisible(false);
                trackMatchResult(session);
            }
            else
            {
                winnerSection.setVisible(false);
            }

            setStatus("Status: " + session.getStatus(), true);
        }
        else
        {
            String msg = update.getMessage();
            setStatus(msg != null && !msg.isEmpty() ? msg : "Request failed.", false);
        }

        revalidate();
        repaint();
    }

    private void updateMatchDetails(MatchmakingSession session)
    {
        codeVal.setText(session.getMatchCode() != null ? session.getMatchCode() : "—");
        worldVal.setText(String.valueOf(session.getWorld()));
        zoneVal.setText(session.getZone() != null ? session.getZone() : "—");
        riskVal.setText(session.getRisk() != null ? session.getRisk() : "—");
        String rules = session.getGearRules();
        if (rules != null && !rules.isEmpty())
            rulesVal.setText("<html><body style='width:145px; margin:0; padding:0'>" + rules + "</body></html>");
        else
            rulesVal.setText("—");
        statusVal.setText(session.getStatus() != null ? session.getStatus() : "—");

        String localRsn = session.getLocalRsn();
        String p1 = session.getPlayer1Username();
        String p2 = session.getPlayer2Username();

        boolean p1Local = localRsn != null && localRsn.equalsIgnoreCase(p1);
        boolean p2Local = localRsn != null && localRsn.equalsIgnoreCase(p2);

        p1Label.setText(p1 != null ? (p1Local ? p1 + " (You)" : p1) : "—");
        p2Label.setText(p2 != null ? (p2Local ? p2 + " (You)" : p2) : "—");

        applyReadyLabel(p1ReadyLabel, session.isPlayer1ReadyToFight(), session.isPlayer1Joined());
        applyReadyLabel(p2ReadyLabel, session.isPlayer2ReadyToFight(), session.isPlayer2Joined());
    }

    private void updateWinnerSection(MatchmakingSession session)
    {
        MatchmakingWinner winner = session.getWinner();
        if (winner != null && winner.getOsrsRsn() != null && !winner.getOsrsRsn().isEmpty())
        {
            String localRsn = session.getLocalRsn();
            boolean localWon = localRsn != null && localRsn.equalsIgnoreCase(winner.getOsrsRsn());
            winnerLabel.setText(localWon ? "You Won!" : "Winner: " + winner.getOsrsRsn());
            winnerLabel.setForeground(localWon ? GREEN : RED);
            eloLabel.setText("ELO: " + winner.getElo());
        }
        else
        {
            winnerLabel.setText("Match " + session.getStatus());
            winnerLabel.setForeground(MUTED);
            eloLabel.setText(" ");
        }
    }

    private void trackMatchResult(MatchmakingSession session)
    {
        if (session.getMatchCode() == null) return;
        if (session.getMatchCode().equals(lastCompletedMatchCode)) return;

        MatchmakingWinner winner = session.getWinner();
        if (winner == null || winner.getOsrsRsn() == null) return;

        String localRsn = session.getLocalRsn();
        if (localRsn == null) return;

        lastCompletedMatchCode = session.getMatchCode();

        if (localRsn.equalsIgnoreCase(winner.getOsrsRsn()))
            state.setMatchWins(state.getMatchWins() + 1);
        else
            state.setMatchLosses(state.getMatchLosses() + 1);

        recordLabel.setText(buildRecordText());
    }

    private String buildRecordText()
    {
        return state.getMatchWins() + "W  —  " + state.getMatchLosses() + "L";
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void refreshLoginState()
    {
        if (loadButton == null) return;

        boolean canLoad = state.isLoggedIn() && state.isVerified() && !loading;
        loadButton.setEnabled(canLoad);
        if (matchCodeField != null) matchCodeField.setEnabled(canLoad);

        if (!state.isLoggedIn())
            setStatus("Log into RuneScape to use Match Finder.", false);
        else if (!state.isVerified())
            setStatus("Verify your account to use Match Finder.", false);
        else if (!matchmakingManager.hasActiveMatch())
            setStatus(" ", null);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isActiveMatch(MatchmakingSession session)
    {
        if (session == null) return false;
        String s = session.getStatus();
        return s != null && !s.equalsIgnoreCase("Completed") && !s.equalsIgnoreCase("Canceled");
    }

    private boolean isCompletedOrCanceled(MatchmakingSession session)
    {
        if (session == null) return false;
        String s = session.getStatus();
        return "Completed".equalsIgnoreCase(s) || "Canceled".equalsIgnoreCase(s);
    }

    private void setStatus(String msg, Boolean positive)
    {
        if (statusLabel == null) return;
        statusLabel.setText(msg != null && !msg.isEmpty() ? msg : " ");
        if (positive == null)        statusLabel.setForeground(MUTED);
        else if (positive)           statusLabel.setForeground(GREEN);
        else                         statusLabel.setForeground(RED);
    }

    private void applyReadyLabel(JLabel lbl, boolean ready, boolean joined)
    {
        if (ready)         { lbl.setText("Ready");  lbl.setForeground(GREEN); }
        else if (joined)   { lbl.setText("Joined");  lbl.setForeground(TEAL); }
        else               { lbl.setText("Waiting"); lbl.setForeground(DIM);  }
    }

    // ── Layout builders ────────────────────────────────────────────────────────

    private JPanel sectionCard()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        p.setOpaque(true);
        p.setBorder(new CompoundBorder(
                new LineBorder(new Color(60, 60, 60, 180), 1, true),
                new EmptyBorder(8, 10, 8, 10)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JLabel sectionTag(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(cf(Font.BOLD, 10f));
        l.setForeground(TEAL);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel valLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(cf(Font.PLAIN, 11f));
        l.setForeground(MUTED);
        return l;
    }

    private JLabel playerLabel(String name)
    {
        JLabel l = new JLabel(name);
        l.setFont(cf(Font.BOLD, 12f));
        l.setForeground(Color.WHITE);
        return l;
    }

    private JLabel stateLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(cf(Font.PLAIN, 11f));
        l.setForeground(DIM);
        return l;
    }

    private JPanel playerRow(JLabel name, JLabel state)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(name);
        row.add(Box.createHorizontalGlue());
        row.add(state);
        return row;
    }

    private JPanel kvRow(String key, JLabel val)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JLabel k = new JLabel(key);
        k.setFont(cf(Font.PLAIN, 11f));
        k.setForeground(DIM);
        Dimension kSize = new Dimension(52, 16);
        k.setPreferredSize(kSize);
        k.setMinimumSize(kSize);
        k.setMaximumSize(new Dimension(52, 20));
        row.add(k);
        row.add(val);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel rulesRow()
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel header = new JLabel("Rules");
        header.setFont(cf(Font.PLAIN, 11f));
        header.setForeground(DIM);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(header);
        row.add(vSpace(2));

        rulesVal.setAlignmentX(Component.LEFT_ALIGNMENT);
        rulesVal.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        row.add(rulesVal);
        return row;
    }

    private void styleBtn(JButton btn, Color bg, Color border, Color fg)
    {
        btn.setFont(cf(Font.BOLD, 12f));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setBorder(new CompoundBorder(
                new LineBorder(border, 1, true),
                new EmptyBorder(5, 12, 5, 12)));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private Component vSpace(int px) { return Box.createRigidArea(new Dimension(0, px)); }
    private Component hSpace(int px) { return Box.createRigidArea(new Dimension(px, 0)); }
}
