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
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private static final String RUNEALYTICS_URL = "https://www.runealytics.com";
    private static final String DISCORD_URL     = "https://runealytics.com/discord";

    // ── Font helper (Calibri, falls back to SansSerif on non-Windows) ─────────
    private static Font cf(int style, float size) { return new Font("Calibri", style, Math.round(size)); }

    // ── Colour palette ────────────────────────────────────────────────────────
    private static final Color GOLD_COLOR      = new Color(215, 175, 55);
    private static final Color TEAL_COLOR      = new Color(82, 196, 196);
    private static final Color VERIFY_BTN_COLOR = new Color(200, 160, 0);
    private static final Color CONNECTED_GREEN = new Color(105, 220, 140);
    private static final Color ERROR_RED       = new Color(255, 110, 110);
    private static final Color STEP_CARD_BG    = new Color(40, 40, 42);
    private static final Color STEP_ICON_BG    = new Color(55, 55, 60);
    /** Main body text — high contrast against the dark background. */
    private static final Color BODY_TEXT       = new Color(204, 204, 204);
    /** Secondary / detail text. */
    private static final Color DIM_TEXT        = new Color(180, 180, 180);

    // ── Injected dependencies ─────────────────────────────────────────────────
    private final RuneAlyticsVerificationPanel verificationPanel;
    private final RunealyticsConfig            config;
    private final RuneAlyticsState             state;
    private final RunealyticsApiClient         apiClient;
    private final ScheduledExecutorService     executorService;
    private final Client                       client;

    // ── Live widgets ──────────────────────────────────────────────────────────
    private JTextField codeField;
    private JButton    verifyButton;
    private JLabel     connectionIconLabel;
    private JLabel     connectionTitleLabel;
    private JLabel     connectionBodyLabel;

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

        this.verificationPanel.setVerificationStatusListener(this::handleVerificationStatusChange);
        add(buildScrollPane(), BorderLayout.CENTER);
    }

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
    //  Scroll pane
    // ═════════════════════════════════════════════════════════════════════════

    private JScrollPane buildScrollPane()
    {
        JScrollPane scroll = new JScrollPane(buildContent());
        scroll.setBorder(null);
        // Fixed 8-px scrollbar so the viewport width is always predictable;
        // avoids content jittering when the scrollbar appears/disappears.
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
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setOpaque(true);
        // Extra right padding (14 px) ensures content never bleeds against the scrollbar.
        panel.setBorder(new EmptyBorder(10, 10, 10, 14));

        panel.add(buildLogoSection());
        panel.add(vSpace(14));
        panel.add(buildVerificationSection());
        panel.add(vSpace(14));
        panel.add(sectionHeader("RUNEALYTICS BENEFITS"));
        panel.add(vSpace(6));
        panel.add(buildBenefitsSection());
        panel.add(vSpace(14));
        panel.add(buildNeedHelpSection());
        panel.add(vSpace(10));
        panel.add(buildVersionLabel());
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Logo section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildLogoSection()
    {
        // FlowLayout.CENTER horizontally centres the inner stack without
        // breaking the LEFT_ALIGNMENT contract with sibling sections.
        JPanel outer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        outer.setOpaque(false);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        // Prefer the full branding logo (contains "RuneAlytics" text in image).
        // Place runealytics_logo.png in src/main/resources/ to activate this path.
        BufferedImage fullLogo = tryLoadImage("/runealytics_logo.png");
        if (fullLogo != null)
        {
            int displayW = 170;
            int displayH = Math.max(1,
                    (int)(fullLogo.getHeight() * (displayW / (double) fullLogo.getWidth())));
            JLabel lbl = scaledImageLabel(fullLogo, displayW, displayH);
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(lbl);
            inner.add(vSpace(4));
            JLabel tagline = new JLabel("KNOW MORE. PLAY SMARTER.", SwingConstants.CENTER);
            tagline.setFont(cf(Font.BOLD, 9f));
            tagline.setForeground(TEAL_COLOR);
            tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(tagline);
        }
        else
        {
            // Fallback: 32x32 icon + text labels.
            BufferedImage icon = tryLoadImage("/runealytics_icon.png");
            JLabel logo;
            if (icon != null)
            {
                logo = scaledImageLabel(icon, 64, 64);
            }
            else
            {
                logo = new JLabel("RA", SwingConstants.CENTER);
                logo.setFont(cf(Font.BOLD, 24f));
                logo.setForeground(GOLD_COLOR);
                logo.setOpaque(true);
                logo.setBackground(new Color(50, 40, 10));
                logo.setPreferredSize(new Dimension(64, 64));
                logo.setMaximumSize(new Dimension(64, 64));
            }
            logo.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(logo);
            inner.add(vSpace(8));

            JLabel title = new JLabel("RUNEALYTICS", SwingConstants.CENTER);
            title.setFont(cf(Font.BOLD, 20f));
            title.setForeground(GOLD_COLOR);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(title);

            JLabel tagline = new JLabel("KNOW MORE. PLAY SMARTER.", SwingConstants.CENTER);
            tagline.setFont(cf(Font.BOLD, 10f));
            tagline.setForeground(TEAL_COLOR);
            tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(tagline);
        }

        outer.add(inner);
        return outer;
    }

    /** Loads a classpath image; returns {@code null} silently on failure. */
    private static BufferedImage tryLoadImage(String resource)
    {
        try { return ImageUtil.loadImageResource(RuneAlyticsSettingsPanel.class, resource); }
        catch (Exception e) { return null; }
    }

    /** Scales {@code src} to exactly {@code w × h} using bicubic interpolation. */
    private static JLabel scaledImageLabel(BufferedImage src, int w, int h)
    {
        BufferedImage dest = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = dest.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return new JLabel(new ImageIcon(dest));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Account Verification section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildVerificationSection()
    {
        JPanel p = verticalPanel();

        p.add(sectionHeader("ACCOUNT VERIFICATION"));
        p.add(vSpace(6));

        JLabel desc = htmlLabel(
                "<span style='color:#cccccc'>Connect your <b>RuneLite</b> client with your "
                + "<b>RuneAlytics</b> account to unlock powerful tracking and analytics "
                + "features.</span>",
                160);
        desc.setFont(cf(Font.PLAIN, 11f));
        p.add(desc);
        p.add(vSpace(10));

        p.add(buildStepCard("1", "Go to RuneAlytics.com",
                "Open your browser and visit runealytics.com."));
        p.add(vSpace(5));
        p.add(buildStepCard("2", "Create an Account",
                "Sign up and navigate to your Dashboard."));
        p.add(vSpace(5));
        p.add(buildStepCard("3", "Get Your Auth Code",
                "Find the Verify Account section and follow the steps to receive your auth code."));
        p.add(vSpace(5));
        p.add(buildStepCard("4", "Paste Your Auth Code",
                "Enter your auth code in the field below and press Verify Account."));
        p.add(vSpace(5));
        p.add(buildStepCard("5", "You're All Set!",
                "Your RuneLite client is now linked to your RuneAlytics account."));
        p.add(vSpace(10));

        p.add(sectionHeader("VERIFICATION CODE"));
        p.add(vSpace(4));

        codeField = RuneAlyticsUi.inputField();
        codeField.setFont(cf(Font.PLAIN, 12f));
        ((AbstractDocument) codeField.getDocument()).setDocumentFilter(new UpperCaseFilter(6));
        codeField.addActionListener(e -> triggerVerification());
        p.add(codeField);
        p.add(vSpace(6));

        verifyButton = buildGoldButton("Verify Account");
        verifyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        verifyButton.addActionListener(e -> triggerVerification());
        p.add(verifyButton);
        p.add(vSpace(8));

        p.add(buildConnectionStatusCard());
        return p;
    }

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
        card.add(hSpace(10));

        JPanel textCol = verticalPanel();

        JLabel t = new JLabel(title);
        t.setFont(cf(Font.BOLD, 12f));
        t.setForeground(Color.WHITE);
        textCol.add(t);

        JLabel b = htmlLabel("<span style='color:#cccccc'>" + body + "</span>", 108);
        b.setFont(cf(Font.PLAIN, 10f));
        textCol.add(b);

        card.add(textCol);
        return card;
    }

    private JLabel buildStepIcon(String num)
    {
        JLabel icon = new JLabel(num, SwingConstants.CENTER)
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(STEP_ICON_BG);
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.setColor(TEAL_COLOR);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(1, 1, getWidth() - 2, getHeight() - 2);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        icon.setFont(cf(Font.BOLD, 13f));
        icon.setForeground(TEAL_COLOR);
        icon.setOpaque(false);
        Dimension d = new Dimension(30, 30);
        icon.setPreferredSize(d);
        icon.setMinimumSize(d);
        icon.setMaximumSize(d);
        return icon;
    }

    private JPanel buildConnectionStatusCard()
    {
        JPanel card = RuneAlyticsUi.cardPanel();

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        connectionIconLabel = new JLabel("◉");
        connectionIconLabel.setFont(cf(Font.BOLD, 18f));
        connectionIconLabel.setForeground(ERROR_RED);
        connectionIconLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(connectionIconLabel);
        row.add(hSpace(10));

        JPanel textCol = verticalPanel();

        connectionTitleLabel = new JLabel("Not Connected");
        connectionTitleLabel.setFont(cf(Font.BOLD, 13f));
        connectionTitleLabel.setForeground(ERROR_RED);
        textCol.add(connectionTitleLabel);

        connectionBodyLabel = new JLabel("Your account is not linked.");
        connectionBodyLabel.setFont(cf(Font.PLAIN, 11f));
        connectionBodyLabel.setForeground(DIM_TEXT);
        textCol.add(connectionBodyLabel);

        row.add(textCol);
        card.add(row);
        return card;
    }

    // ── Verification API call ─────────────────────────────────────────────────

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
        JPanel p = verticalPanel();
        addBenefitCard(p, "Real-time Boss & Drop Tracking",
                "Track your kills, unique drops, and valuable loot in real time.");
        p.add(vSpace(5));
        addBenefitCard(p, "Performance Insights",
                "Analyze your PvM performance with detailed stats and metrics.");
        p.add(vSpace(5));
        addBenefitCard(p, "Leaderboards",
                "Compete with other players on global and server-specific rankings.");
        p.add(vSpace(5));
        addBenefitCard(p, "Session History",
                "Review your past sessions, drops, and progress over time.");
        p.add(vSpace(5));
        addBenefitCard(p, "Community Driven",
                "Join a community of players and share your achievements.");
        return p;
    }

    private void addBenefitCard(JPanel parent, String title, String desc)
    {
        JPanel card = RuneAlyticsUi.cardPanel();

        JLabel t = new JLabel(title);
        t.setFont(cf(Font.BOLD, 12f));
        t.setForeground(Color.WHITE);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(t);
        card.add(vSpace(3));

        JLabel d = htmlLabel("<span style='color:#cccccc'>" + desc + "</span>", 148);
        d.setFont(cf(Font.PLAIN, 11f));
        card.add(d);
        parent.add(card);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Need Help section
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildNeedHelpSection()
    {
        JPanel p = verticalPanel();
        p.add(sectionHeader("NEED HELP?"));
        p.add(vSpace(6));

        JLabel helpText = htmlLabel(
                "<span style='color:#cccccc'>Join our Discord or visit runealytics.com for "
                + "support and more information.</span>",
                160);
        helpText.setFont(cf(Font.PLAIN, 11f));
        p.add(helpText);
        p.add(vSpace(8));

        JButton discordBtn = buildTealButton("Discord");
        discordBtn.addActionListener(e -> LinkBrowser.browse(DISCORD_URL));
        p.add(discordBtn);
        p.add(vSpace(5));

        JButton websiteBtn = buildTealButton("Website");
        websiteBtn.addActionListener(e -> LinkBrowser.browse(RUNEALYTICS_URL));
        p.add(websiteBtn);
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Button builders
    // ═════════════════════════════════════════════════════════════════════════

    private JButton buildGoldButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(VERIFY_BTN_COLOR);
        btn.setForeground(Color.BLACK);
        btn.setFont(cf(Font.BOLD, 13f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(new Color(180, 140, 0), 1, true),
                new EmptyBorder(6, 16, 6, 16)));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        return btn;
    }

    private JButton buildTealButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(new Color(30, 70, 70));
        btn.setForeground(TEAL_COLOR);
        btn.setFont(cf(Font.BOLD, 12f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(TEAL_COLOR.darker(), 1, true),
                new EmptyBorder(5, 14, 5, 14)));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return btn;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared label/layout helpers
    // ═════════════════════════════════════════════════════════════════════════

    private JLabel sectionHeader(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setFont(cf(Font.BOLD, 10f));
        lbl.setForeground(TEAL_COLOR);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    private JLabel buildVersionLabel()
    {
        JLabel lbl = new JLabel("RuneAlytics v1.0.0");
        lbl.setFont(cf(Font.PLAIN, 10f));
        lbl.setForeground(new Color(90, 90, 90));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    private static JPanel verticalPanel()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static Component vSpace(int px) { return Box.createRigidArea(new Dimension(0, px)); }
    private static Component hSpace(int px) { return Box.createRigidArea(new Dimension(px, 0)); }

    /**
     * Creates an HTML JLabel that wraps text at {@code wrapWidth} pixels.
     * Font must be set on the returned label separately.
     */
    private static JLabel htmlLabel(String htmlContent, int wrapWidth)
    {
        JLabel lbl = new JLabel(
                "<html><body style='width:" + wrapWidth + "px'>"
                + htmlContent + "</body></html>");
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  External API (called from RuneAlyticsPlugin)
    // ═════════════════════════════════════════════════════════════════════════

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

    public void updateLastSyncTime() { /* display removed in current design */ }

    private void handleVerificationStatusChange()
    {
        updateVerificationStatus(state.isVerified(), state.getVerifiedUsername());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UpperCaseFilter — 6-char uppercase code field
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
            String upper = string.toUpperCase();
            int current = fb.getDocument().getLength();
            if (current + upper.length() > maxLen)
                upper = upper.substring(0, Math.max(0, maxLen - current));
            super.insertString(fb, offset, upper, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException
        {
            if (text == null) { super.replace(fb, offset, length, null, attrs); return; }
            String upper = text.toUpperCase();
            int current = fb.getDocument().getLength() - length;
            if (current + upper.length() > maxLen)
                upper = upper.substring(0, Math.max(0, maxLen - current));
            super.replace(fb, offset, length, upper, attrs);
        }
    }
}
