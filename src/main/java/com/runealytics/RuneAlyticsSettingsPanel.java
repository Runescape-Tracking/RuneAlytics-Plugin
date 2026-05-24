package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String RUNEALYTICS_URL = "https://www.runealytics.com";
    private static final String DISCORD_URL     = "https://runealytics.com/discord";

    // ── Colour palette ────────────────────────────────────────────────────────
    /** Gold brand colour for the logo title */
    private static final Color GOLD_COLOR        = new Color(215, 175, 55);
    /** Teal used for section headers and tagline */
    private static final Color TEAL_COLOR        = new Color(82, 196, 196);
    /** Amber used for the full-width Verify Account CTA */
    private static final Color VERIFY_BTN_COLOR  = new Color(200, 160, 0);
    /** Green for "Connected" state indicators */
    private static final Color CONNECTED_GREEN   = new Color(105, 220, 140);
    /** Red for "Not Connected" state indicators */
    private static final Color ERROR_RED         = new Color(255, 110, 110);
    /** Background for step-number cards */
    private static final Color STEP_CARD_BG      = new Color(40, 40, 42);
    /** Background circle behind step numbers */
    private static final Color STEP_ICON_BG      = new Color(55, 55, 60);

    // ── Injected dependencies ─────────────────────────────────────────────────
    /**
     * Retained for token storage ({@link RuneAlyticsVerificationPanel#saveAccountToken},
     * {@link RuneAlyticsVerificationPanel#loadAccountToken},
     * {@link RuneAlyticsVerificationPanel#clearAccountToken}) and for back-compat
     * with plugin code that calls {@link RuneAlyticsVerificationPanel#refreshLoginState()}.
     *
     * @see RuneAlyticsVerificationPanel
     */
    private final RuneAlyticsVerificationPanel verificationPanel;
    /** @see RunealyticsConfig */
    private final RunealyticsConfig            config;
    /** @see RuneAlyticsState */
    private final RuneAlyticsState             state;
    /** @see RunealyticsApiClient */
    private final RunealyticsApiClient         apiClient;
    private final ScheduledExecutorService     executorService;
    private final Client                       client;

    // ── Verification form live widgets ────────────────────────────────────────
    /** 6-character verification code entry; auto-uppercased and length-capped */
    private JTextField codeField;
    /** Triggers the verification API call; disabled until logged in */
    private JButton    verifyButton;

    // ── Connection status card live widgets ───────────────────────────────────
    /** Bullet/icon label that changes colour with connection state */
    private JLabel connectionIconLabel;
    /** "Connected" / "Not Connected" heading inside the status card */
    private JLabel connectionTitleLabel;
    /** Detail text inside the status card */
    private JLabel connectionBodyLabel;

    @Inject
    public RuneAlyticsSettingsPanel(
            RuneAlyticsVerificationPanel verificationPanel,
            RunealyticsConfig            config,
            RuneAlyticsState             state,
            RunealyticsApiClient         apiClient,
            ScheduledExecutorService     executorService,
            Client                       client
    )
    {
        this.verificationPanel = verificationPanel;
        this.config            = config;
        this.state             = state;
        this.apiClient         = apiClient;
        this.executorService   = executorService;
        this.client            = client;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        // Forward verification callbacks so the connection card updates
        this.verificationPanel.setVerificationStatusListener(this::handleVerificationStatusChange);

        add(buildScrollPane(), BorderLayout.CENTER);
    }

    /**
     * Mirrors parent height to prevent the sidebar from inflating the RuneLite
     * window to fit tall content.  Identical pattern to {@link RuneAlyticsPanel}.
     */
    @Override
    public Dimension getPreferredSize()
    {
        Container p = getParent();
        int h = (p != null && p.getHeight() > 0) ? p.getHeight() : 400;
        return new Dimension(PluginPanel.PANEL_WIDTH, h);
    }

    @Override
    public Dimension getMinimumSize() { return new Dimension(50, 80); }

    // ═════════════════════════════════════════════════════════════════════════
    //  Top-level layout
    // ═════════════════════════════════════════════════════════════════════════

    private JScrollPane buildScrollPane()
    {
        JScrollPane scroll = new JScrollPane(buildContent());
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel buildContent()
    {
        JPanel panel = RuneAlyticsUi.rootContentPanel();

        panel.add(buildLogoSection());
        panel.add(RuneAlyticsUi.vSpace(14));

        panel.add(buildVerificationSection());
        panel.add(RuneAlyticsUi.vSpace(14));

        panel.add(sectionHeader("RUNEALYTICS BENEFITS"));
        panel.add(RuneAlyticsUi.vSpace(6));
        panel.add(buildBenefitsSection());
        panel.add(RuneAlyticsUi.vSpace(14));

        panel.add(buildNeedHelpSection());
        panel.add(RuneAlyticsUi.vSpace(10));

        panel.add(buildVersionLabel());
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Logo section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildLogoSection()
    {
        // Outer panel uses FlowLayout.CENTER so the inner stack is centred
        // horizontally without disturbing the LEFT_ALIGNMENT of its siblings
        // in the root BoxLayout (mixing alignmentX in BoxLayout causes drift).
        JPanel outer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        outer.setOpaque(false);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        JLabel logo = loadLogoLabel(64);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(logo);
        inner.add(RuneAlyticsUi.vSpace(8));

        JLabel title = new JLabel("RUNEALYTICS", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(GOLD_COLOR);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(title);

        JLabel tagline = new JLabel("KNOW MORE. PLAY SMARTER.", SwingConstants.CENTER);
        tagline.setFont(tagline.getFont().deriveFont(Font.BOLD, 10f));
        tagline.setForeground(TEAL_COLOR);
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(tagline);

        outer.add(inner);
        return outer;
    }

    /**
     * Loads {@code runealytics_icon.png} from the classpath and scales it to
     * {@code size × size}.  Falls back to a gold "RA" text label if the image
     * cannot be loaded (e.g. in test environments).
     */
    private JLabel loadLogoLabel(int size)
    {
        try
        {
            BufferedImage src = ImageUtil.loadImageResource(
                    RuneAlyticsSettingsPanel.class, "/runealytics_icon.png");
            if (src != null)
            {
                // Nearest-neighbour keeps pixel-art icons crisp at integer multiples.
                // The source icon is 32×32, so 64px = exact 2× with no blurring.
                BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(src, 0, 0, size, size, null);
                g2.dispose();
                return new JLabel(new ImageIcon(scaled));
            }
        }
        catch (Exception e)
        {
            log.debug("Logo image unavailable: {}", e.getMessage());
        }
        JLabel fallback = new JLabel("RA", SwingConstants.CENTER);
        fallback.setFont(fallback.getFont().deriveFont(Font.BOLD, 24f));
        fallback.setForeground(GOLD_COLOR);
        fallback.setOpaque(true);
        fallback.setBackground(new Color(50, 40, 10));
        fallback.setPreferredSize(new Dimension(size, size));
        fallback.setMaximumSize(new Dimension(size, size));
        return fallback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Account Verification section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildVerificationSection()
    {
        JPanel p = RuneAlyticsUi.verticalPanel();

        p.add(sectionHeader("ACCOUNT VERIFICATION"));
        p.add(RuneAlyticsUi.vSpace(6));

        JLabel desc = new JLabel(
                "<html><body style='width:195px'>"
                + "<span style='color:#cccccc'>Connect your <b>RuneLite</b> client with your "
                + "<b>RuneAlytics</b> account to unlock powerful tracking and analytics features.</span>"
                + "</body></html>");
        desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 11f));
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(desc);
        p.add(RuneAlyticsUi.vSpace(10));

        // Numbered step cards
        p.add(buildStepCard("1", "Go to Runealytics.com",
                "Open your browser and visit runealytics.com."));
        p.add(RuneAlyticsUi.vSpace(5));
        p.add(buildStepCard("2", "Link your account",
                "Log in to your account and click \"Link RuneLite\"."));
        p.add(RuneAlyticsUi.vSpace(5));
        p.add(buildStepCard("3", "Paste your code below",
                "Copy your verification code and paste it below to finish."));
        p.add(RuneAlyticsUi.vSpace(10));

        // Code input
        p.add(sectionHeader("VERIFICATION CODE"));
        p.add(RuneAlyticsUi.vSpace(4));

        codeField = RuneAlyticsUi.inputField();
        ((AbstractDocument) codeField.getDocument()).setDocumentFilter(new UpperCaseFilter(6));
        codeField.addActionListener(e -> triggerVerification());
        p.add(codeField);
        p.add(RuneAlyticsUi.vSpace(6));

        // Gold full-width Verify Account button
        verifyButton = buildGoldButton("Verify Account");
        verifyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        verifyButton.addActionListener(e -> triggerVerification());
        p.add(verifyButton);
        p.add(RuneAlyticsUi.vSpace(8));

        // Live connection-status card
        p.add(buildConnectionStatusCard());

        return p;
    }

    /**
     * A horizontal step card with a teal-outlined circle number and a title + body text.
     */
    private JPanel buildStepCard(String stepNum, String title, String body)
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.X_AXIS));
        card.setBackground(STEP_CARD_BG);
        card.setBorder(new CompoundBorder(
                new LineBorder(new Color(60, 60, 65), 1, true),
                new EmptyBorder(8, 8, 8, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(buildStepIcon(stepNum));
        card.add(RuneAlyticsUi.hSpace(10));

        JPanel textCol = RuneAlyticsUi.verticalPanel();

        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 12f));
        t.setForeground(Color.WHITE);
        textCol.add(t);

        JLabel b = new JLabel(
                "<html><body style='width:130px'><span style='color:#aaaaaa'>" + body + "</span></body></html>");
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 10f));
        textCol.add(b);

        card.add(textCol);
        return card;
    }

    /**
     * A teal-bordered circle label with the step number centred inside.
     * Painted with anti-aliased Graphics2D so the circle is smooth at any DPI.
     */
    private JLabel buildStepIcon(String num)
    {
        JLabel icon = new JLabel(num, SwingConstants.CENTER)
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(STEP_ICON_BG);
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.setColor(TEAL_COLOR);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(1, 1, getWidth() - 2, getHeight() - 2);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 13f));
        icon.setForeground(TEAL_COLOR);
        icon.setOpaque(false);
        Dimension d = new Dimension(30, 30);
        icon.setPreferredSize(d);
        icon.setMinimumSize(d);
        icon.setMaximumSize(d);
        return icon;
    }

    /**
     * The bottom card in the verification section; shows the current link state.
     * Widgets are stored in fields so {@link #updateConnectionStatus} can mutate them.
     */
    private JPanel buildConnectionStatusCard()
    {
        JPanel card = RuneAlyticsUi.cardPanel();

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        connectionIconLabel = new JLabel("◉");
        connectionIconLabel.setFont(connectionIconLabel.getFont().deriveFont(Font.BOLD, 20f));
        connectionIconLabel.setForeground(ERROR_RED);
        connectionIconLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(connectionIconLabel);
        row.add(RuneAlyticsUi.hSpace(10));

        JPanel textCol = RuneAlyticsUi.verticalPanel();

        connectionTitleLabel = new JLabel("Not Connected");
        connectionTitleLabel.setFont(connectionTitleLabel.getFont().deriveFont(Font.BOLD, 13f));
        connectionTitleLabel.setForeground(ERROR_RED);
        textCol.add(connectionTitleLabel);

        connectionBodyLabel = new JLabel("Your account is not linked.");
        connectionBodyLabel.setFont(connectionBodyLabel.getFont().deriveFont(Font.PLAIN, 11f));
        connectionBodyLabel.setForeground(new Color(170, 170, 170));
        textCol.add(connectionBodyLabel);

        row.add(textCol);
        card.add(row);

        return card;
    }

    // ── Verification API call ─────────────────────────────────────────────────

    /**
     * Reads the code field, validates input, then calls
     * {@link RunealyticsApiClient#verifyToken} on the executor.
     * Saves or clears the per-account token via {@link RuneAlyticsVerificationPanel}
     * and updates {@link RuneAlyticsState} on the EDT.
     */
    private void triggerVerification()
    {
        if (client.getGameState() != GameState.LOGGED_IN
                || client.getLocalPlayer() == null)
        {
            updateConnectionStatus(false, "Log into RuneScape first to link your account.");
            return;
        }

        String code = codeField.getText().trim().toUpperCase();
        if (code.isEmpty())
        {
            updateConnectionStatus(false, "Enter the code from RuneAlytics.com.");
            return;
        }
        if (code.length() != 6)
        {
            updateConnectionStatus(false, "Code must be exactly 6 characters.");
            return;
        }

        final String rsn = client.getLocalPlayer().getName().trim().toLowerCase();

        verifyButton.setEnabled(false);
        verifyButton.setText("Verifying...");
        updateConnectionStatus(false, "Verifying with server...");

        executorService.submit(() -> {
            boolean success = false;
            try
            {
                success = apiClient.verifyToken(code, rsn);
            }
            catch (Exception e)
            {
                log.warn("Verification request failed: {}", e.getMessage());
            }

            final boolean verified = success;
            SwingUtilities.invokeLater(() -> {
                verifyButton.setText("Verify Account");
                verifyButton.setEnabled(true);

                if (verified)
                {
                    verificationPanel.saveAccountToken(rsn, code);
                    config.authToken(code);
                    state.setVerified(true);
                    state.setVerifiedUsername(rsn);
                    state.setVerificationCode(code);
                    updateConnectionStatus(true, "Linked as " + rsn);
                    log.info("Verification succeeded for '{}'", rsn);
                }
                else
                {
                    verificationPanel.clearAccountToken(rsn);
                    config.authToken("");
                    state.setVerified(false);
                    state.setVerifiedUsername(null);
                    state.setVerificationCode(null);
                    updateConnectionStatus(false, "Invalid code — check and try again.");
                    log.warn("Verification failed for '{}'", rsn);
                }
            });
        });
    }

    /**
     * Updates the connection-status card to reflect a verified or unverified state.
     * Must be called on the EDT.
     */
    private void updateConnectionStatus(boolean connected, String detail)
    {
        if (connectionIconLabel == null) return;

        Color colour = connected ? CONNECTED_GREEN : ERROR_RED;
        connectionIconLabel.setForeground(colour);
        connectionTitleLabel.setForeground(colour);
        connectionTitleLabel.setText(connected ? "Connected" : "Not Connected");
        connectionBodyLabel.setText(detail);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Benefits section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildBenefitsSection()
    {
        JPanel p = RuneAlyticsUi.verticalPanel();

        addBenefitCard(p, "Real-time Boss & Drop Tracking",
                "Track your kills, unique drops, and valuable loot in real time.");
        p.add(RuneAlyticsUi.vSpace(5));
        addBenefitCard(p, "Performance Insights",
                "Analyze your PvM performance with detailed stats and metrics.");
        p.add(RuneAlyticsUi.vSpace(5));
        addBenefitCard(p, "Leaderboards",
                "Compete with other players on global and server-specific rankings.");
        p.add(RuneAlyticsUi.vSpace(5));
        addBenefitCard(p, "Session History",
                "Review your past sessions, drops, and progress over time.");
        p.add(RuneAlyticsUi.vSpace(5));
        addBenefitCard(p, "Community Driven",
                "Join a community of players and share your achievements.");
        return p;
    }

    private void addBenefitCard(JPanel parent, String title, String desc)
    {
        JPanel card = RuneAlyticsUi.cardPanel();

        JLabel t = RuneAlyticsUi.bodyLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 12f));
        card.add(t);
        card.add(RuneAlyticsUi.vSpace(3));

        JLabel d = new JLabel(
                "<html><body style='width:175px'>"
                + "<span style='color:#aaaaaa'>" + desc + "</span>"
                + "</body></html>");
        d.setFont(d.getFont().deriveFont(Font.PLAIN, 11f));
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(d);
        parent.add(card);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Need Help section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildNeedHelpSection()
    {
        JPanel p = RuneAlyticsUi.verticalPanel();

        p.add(sectionHeader("NEED HELP?"));
        p.add(RuneAlyticsUi.vSpace(6));

        JLabel helpText = new JLabel(
                "<html><body style='width:195px'><span style='color:#cccccc'>"
                + "Join our Discord or visit runealytics.com for support and more information."
                + "</span></body></html>");
        helpText.setFont(helpText.getFont().deriveFont(Font.PLAIN, 11f));
        helpText.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(helpText);
        p.add(RuneAlyticsUi.vSpace(8));

        JButton discordBtn = buildTealButton("Discord");
        discordBtn.addActionListener(e -> LinkBrowser.browse(DISCORD_URL));
        p.add(discordBtn);
        p.add(RuneAlyticsUi.vSpace(5));

        JButton websiteBtn = buildTealButton("Website");
        websiteBtn.addActionListener(e -> LinkBrowser.browse(RUNEALYTICS_URL));
        p.add(websiteBtn);

        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared button builders
    // ═════════════════════════════════════════════════════════════════════════

    /** Full-width amber button used for the primary Verify Account CTA. */
    private JButton buildGoldButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(VERIFY_BTN_COLOR);
        btn.setForeground(Color.BLACK);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 13f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(new Color(180, 140, 0), 1, true),
                new EmptyBorder(6, 16, 6, 16)
        ));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        return btn;
    }

    /** Dark teal outline button used for Discord and Website links. */
    private JButton buildTealButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(new Color(30, 70, 70));
        btn.setForeground(TEAL_COLOR);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 12f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(TEAL_COLOR.darker(), 1, true),
                new EmptyBorder(5, 14, 5, 14)
        ));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return btn;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared teal section-header label
    // ═════════════════════════════════════════════════════════════════════════

    private JLabel sectionHeader(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 10f));
        lbl.setForeground(TEAL_COLOR);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Version label
    // ═════════════════════════════════════════════════════════════════════════

    private JLabel buildVersionLabel()
    {
        JLabel lbl = new JLabel("Runealytics v1.0.0");
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 10f));
        lbl.setForeground(new Color(90, 90, 90));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  External API (called from RuneAlyticsPlugin)
    // ═════════════════════════════════════════════════════════════════════════

    /** Called on login/logout state change to enable or disable the code field. */
    public void refreshLoginState()
    {
        SwingUtilities.invokeLater(() -> {
            boolean loggedIn = (client.getGameState() == GameState.LOGGED_IN
                    && client.getLocalPlayer() != null);
            if (codeField    != null) codeField.setEnabled(loggedIn);
            if (verifyButton != null) verifyButton.setEnabled(loggedIn);
            if (!loggedIn && codeField != null)
                updateConnectionStatus(false, "Log into RuneScape to link your account.");
        });
        verificationPanel.refreshLoginState();
    }

    /**
     * Called by the plugin after server-side verification succeeds or fails.
     * Updates the connection-status card.
     *
     * @param verified whether the account is now verified
     * @param username the linked RSN, or {@code null} if not verified
     */
    public void updateVerificationStatus(boolean verified, String username)
    {
        SwingUtilities.invokeLater(() -> {
            if (verified)
                updateConnectionStatus(true,
                        "Linked as " + (username != null ? username : "unknown"));
            else
                updateConnectionStatus(false, "Your account is not linked.");
        });
    }

    /** No-op — kept for API compatibility; last-sync time is no longer shown here. */
    public void updateLastSyncTime()
    {
        // Last sync time display removed in this design revision
    }

    private void handleVerificationStatusChange()
    {
        updateVerificationStatus(state.isVerified(), state.getVerifiedUsername());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UpperCaseFilter — forces the code field to 6 upper-case characters max
    // ═════════════════════════════════════════════════════════════════════════

    private static final class UpperCaseFilter extends DocumentFilter
    {
        private final int maxLen;

        UpperCaseFilter(int maxLen) { this.maxLen = maxLen; }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException
        {
            if (string == null) return;
            String upper   = string.toUpperCase();
            int    current = fb.getDocument().getLength();
            if (current + upper.length() > maxLen)
                upper = upper.substring(0, Math.max(0, maxLen - current));
            super.insertString(fb, offset, upper, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException
        {
            if (text == null) { super.replace(fb, offset, length, null, attrs); return; }
            String upper   = text.toUpperCase();
            int    current = fb.getDocument().getLength() - length;
            if (current + upper.length() > maxLen)
                upper = upper.substring(0, Math.max(0, maxLen - current));
            super.replace(fb, offset, length, upper, attrs);
        }
    }
}
