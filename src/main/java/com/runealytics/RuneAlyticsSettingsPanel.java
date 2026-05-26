package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
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
import java.util.function.Consumer;

@Slf4j
@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private static final String RUNEALYTICS_URL = "https://www.runealytics.com";
    private static final String DISCORD_URL     = "https://runealytics.com/discord";

    private static Font cf(int style, float size) { return new Font("Calibri", style, Math.round(size)); }

    private static final Color GOLD_COLOR       = new Color(215, 175, 55);
    private static final Color TEAL_COLOR       = new Color(82, 196, 196);
    private static final Color VERIFY_BTN_COLOR = new Color(200, 160, 0);
    private static final Color CONNECTED_GREEN  = new Color(105, 220, 140);
    private static final Color ERROR_RED        = new Color(255, 110, 110);
    private static final Color STEP_CARD_BG     = new Color(40, 40, 42);
    private static final Color STEP_ICON_BG     = new Color(55, 55, 60);
    private static final Color BODY_TEXT        = new Color(204, 204, 204);
    private static final Color DIM_TEXT         = new Color(185, 185, 185);

    private final RuneAlyticsVerificationPanel verificationPanel;
    private final RunealyticsConfig            config;
    private final RuneAlyticsState             state;
    private final RunealyticsApiClient         apiClient;
    private final ScheduledExecutorService     executorService;
    private final Client                       client;

    // Stored so rebuild() can swap the viewport view
    private JScrollPane scroll;

    // Live UI widgets — reassigned on each rebuild
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

        verificationPanel.setVerificationStatusListener(this::handleVerificationStatusChange);
        add(buildScrollPane(), BorderLayout.CENTER);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Scroll pane + rebuild
    // ═════════════════════════════════════════════════════════════════════════

    private JScrollPane buildScrollPane()
    {
        scroll = new JScrollPane(buildContent());
        scroll.setBorder(null);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        return scroll;
    }

    /** Swaps the scroll pane content to match the current verification state. */
    private void rebuild()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::rebuild);
            return;
        }
        scroll.setViewportView(buildContent());
        // Refresh the newly-created connection status labels
        if (state.isVerified())
            updateConnectionStatus(true,
                    "Linked as " + (state.getVerifiedUsername() != null ? state.getVerifiedUsername() : ""));
        else
            updateConnectionStatus(false, "Your account is not linked.");
        scroll.revalidate();
        scroll.repaint();
    }

    // ── Content router ─────────────────────────────────────────────────────────

    private JPanel buildContent()
    {
        return state.isVerified() ? buildVerifiedContent() : buildUnverifiedContent();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Verified view — account settings
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildVerifiedContent()
    {
        JPanel panel = rootPanel();
        panel.add(buildLogoSection());
        panel.add(vSpace(12));
        panel.add(buildConnectionStatusCard());
        panel.add(vSpace(14));
        panel.add(sectionHeader("PRIVACY SETTINGS"));
        panel.add(vSpace(6));
        panel.add(buildPrivacySection());
        panel.add(vSpace(14));
        panel.add(buildNeedHelpSection());
        panel.add(vSpace(10));
        panel.add(buildVersionLabel());
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildPrivacySection()
    {
        JPanel p = verticalPanel();

        // Bank Visibility card
        JPanel bankCard = settingsCard();
        JLabel bankTitle = new JLabel("Bank Visibility");
        bankTitle.setFont(cf(Font.BOLD, 13f));
        bankTitle.setForeground(Color.WHITE);
        bankTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        bankCard.add(bankTitle);
        bankCard.add(vSpace(4));
        bankCard.add(compactLabel(
                "Controls who can see your bank value and contents on RuneAlytics.com.",
                BODY_TEXT, cf(Font.PLAIN, 11f)));
        bankCard.add(vSpace(3));
        bankCard.add(compactLabel(
                "Private: bank data used only for gear crafting and your personal view.",
                DIM_TEXT, cf(Font.ITALIC, 11f)));
        bankCard.add(vSpace(8));
        bankCard.add(buildPillToggle(config.bankPrivacy(), v -> config.bankPrivacy(v)));
        p.add(bankCard);

        p.add(vSpace(8));

        // Online Status card
        JPanel visCard = settingsCard();
        JLabel visTitle = new JLabel("Online Status");
        visTitle.setFont(cf(Font.BOLD, 13f));
        visTitle.setForeground(Color.WHITE);
        visTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        visCard.add(visTitle);
        visCard.add(vSpace(4));
        visCard.add(compactLabel(
                "Controls who can see when you are online on RuneAlytics.com.",
                BODY_TEXT, cf(Font.PLAIN, 11f)));
        visCard.add(vSpace(3));
        visCard.add(compactLabel(
                "Friends: only visible to verified players you are tracking.",
                DIM_TEXT, cf(Font.ITALIC, 11f)));
        visCard.add(vSpace(8));
        visCard.add(buildPillToggle(config.playerVisibility(), v -> config.playerVisibility(v)));
        p.add(visCard);

        return p;
    }

    // ── 3-way pill toggle ─────────────────────────────────────────────────────

    private JPanel buildPillToggle(PrivacySetting current, Consumer<PrivacySetting> onChange)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        ButtonGroup group = new ButtonGroup();
        PrivacySetting[] values = PrivacySetting.values();
        JToggleButton[] buttons = new JToggleButton[values.length];

        for (int i = 0; i < values.length; i++)
        {
            PrivacySetting v = values[i];
            JToggleButton btn = new JToggleButton(v.getLabel());
            btn.setFont(cf(Font.PLAIN, 11f));
            btn.setFocusPainted(false);
            btn.setOpaque(true);
            btn.setContentAreaFilled(true);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setSelected(v == current);
            buttons[i] = btn;
            group.add(btn);
            row.add(btn);
        }

        // Wire styles and listeners after all buttons exist so the style loop
        // can update all siblings when any selection changes.
        for (int i = 0; i < values.length; i++)
        {
            final PrivacySetting v = values[i];
            final JToggleButton btn = buttons[i];
            final JToggleButton[] all = buttons;
            applyPillStyle(btn);
            btn.addItemListener(e -> {
                for (JToggleButton b : all) applyPillStyle(b);
                if (btn.isSelected()) onChange.accept(v);
            });
        }

        return row;
    }

    private void applyPillStyle(JToggleButton btn)
    {
        if (btn.isSelected())
        {
            Color red = new Color(185, 42, 42);
            btn.setBackground(red);
            btn.setForeground(Color.BLACK);
            btn.setBorder(new CompoundBorder(
                    new LineBorder(red.darker(), 1),
                    new EmptyBorder(4, 10, 4, 10)));
        }
        else
        {
            btn.setBackground(new Color(45, 45, 48));
            btn.setForeground(DIM_TEXT);
            btn.setBorder(new CompoundBorder(
                    new LineBorder(new Color(60, 60, 65), 1),
                    new EmptyBorder(4, 10, 4, 10)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Unverified view — account verification flow
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildUnverifiedContent()
    {
        JPanel panel = rootPanel();
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

    private JPanel buildVerificationSection()
    {
        JPanel p = verticalPanel();

        p.add(sectionHeader("ACCOUNT VERIFICATION"));
        p.add(vSpace(6));

        p.add(compactLabel(
                "Connect your <b>RuneLite</b> client with your <b>RuneAlytics</b> account "
                + "to unlock powerful tracking and analytics features.",
                BODY_TEXT, cf(Font.PLAIN, 12f), 190));
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

        if (state.isLoggedIn())
        {
            codeField = RuneAlyticsUi.inputField();
            codeField.setFont(cf(Font.PLAIN, 13f));
            ((AbstractDocument) codeField.getDocument()).setDocumentFilter(new UpperCaseFilter(20));
            codeField.addActionListener(e -> triggerVerification());
            p.add(codeField);
            p.add(vSpace(6));

            verifyButton = buildGoldButton("Verify Account");
            verifyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            verifyButton.addActionListener(e -> triggerVerification());
            p.add(verifyButton);
            p.add(vSpace(8));
        }
        else
        {
            codeField   = null;
            verifyButton = null;
            p.add(compactLabel(
                    "Log into RuneScape first, then enter your auth code here.",
                    DIM_TEXT, cf(Font.ITALIC, 11f)));
            p.add(vSpace(8));
        }

        p.add(buildConnectionStatusCard());
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared sections
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildLogoSection()
    {
        JPanel outer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        outer.setOpaque(false);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

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
            tagline.setFont(cf(Font.BOLD, 10f));
            tagline.setForeground(TEAL_COLOR);
            tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(tagline);
        }
        else
        {
            BufferedImage icon = tryLoadImage("/runealytics_icon.png");
            JLabel logo;
            if (icon != null)
                logo = scaledImageLabelCrisp(icon, 64, 64);
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
            inner.add(vSpace(6));

            JLabel tagline = new JLabel("KNOW MORE. PLAY SMARTER.", SwingConstants.CENTER);
            tagline.setFont(cf(Font.BOLD, 11f));
            tagline.setForeground(TEAL_COLOR);
            tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
            inner.add(tagline);
        }

        outer.add(inner);
        return outer;
    }

    /** Card panel capped at 194 px and centered — matches the visual width of the Matches panel cards. */
    private static JPanel settingsCard()
    {
        JPanel card = RuneAlyticsUi.cardPanel();
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(194, Short.MAX_VALUE));
        return card;
    }

    private JPanel buildConnectionStatusCard()
    {
        JPanel card = settingsCard();

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
        connectionTitleLabel.setFont(cf(Font.BOLD, 14f));
        connectionTitleLabel.setForeground(ERROR_RED);
        textCol.add(connectionTitleLabel);

        connectionBodyLabel = new JLabel("Your account is not linked.");
        connectionBodyLabel.setFont(cf(Font.PLAIN, 12f));
        connectionBodyLabel.setForeground(DIM_TEXT);
        textCol.add(connectionBodyLabel);

        row.add(textCol);
        card.add(row);
        return card;
    }

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
        JPanel card = settingsCard();
        JLabel t = new JLabel(title);
        t.setFont(cf(Font.BOLD, 13f));
        t.setForeground(Color.WHITE);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(t);
        card.add(vSpace(3));
        card.add(compactLabel(desc, BODY_TEXT, cf(Font.PLAIN, 12f)));
        parent.add(card);
    }

    private JPanel buildNeedHelpSection()
    {
        JPanel p = verticalPanel();
        p.add(sectionHeader("NEED HELP?"));
        p.add(vSpace(6));
        p.add(compactLabel(
                "Join our Discord or visit runealytics.com for support and more information.",
                BODY_TEXT, cf(Font.PLAIN, 12f)));
        p.add(vSpace(8));

        JButton discordBtn = buildTealButton("Discord");
        discordBtn.addActionListener(e -> openExternalLink(DISCORD_URL, "RuneAlytics Discord"));
        p.add(discordBtn);
        p.add(vSpace(5));

        JButton websiteBtn = buildTealButton("Website");
        websiteBtn.addActionListener(e -> openExternalLink(RUNEALYTICS_URL, "RuneAlytics.com"));
        p.add(websiteBtn);
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
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(194, Short.MAX_VALUE));

        card.add(buildStepIcon(stepNum));
        card.add(hSpace(10));

        JPanel textCol = verticalPanel();
        JLabel t = new JLabel(title);
        t.setFont(cf(Font.BOLD, 13f));
        t.setForeground(Color.WHITE);
        textCol.add(t);
        // 194px card - 18px border/padding - 40px icon+gap = 136px text column
        textCol.add(compactLabel(body, BODY_TEXT, cf(Font.PLAIN, 11f), 130));

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

    // ═════════════════════════════════════════════════════════════════════════
    //  Verification logic
    // ═════════════════════════════════════════════════════════════════════════

    private void triggerVerification()
    {
        if (codeField == null) return;
        String code = codeField.getText().trim().toUpperCase();
        if (code.isEmpty())
        {
            updateConnectionStatus(false, "Enter the code from RuneAlytics.com.");
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            updateConnectionStatus(false, "Log into RuneScape first to link your account.");
            return;
        }

        final String rsn = client.getLocalPlayer().getName().trim().toLowerCase();

        verifyButton.setEnabled(false);
        verifyButton.setText("Verifying...");
        updateConnectionStatus(false, "Verifying with server...");

        executorService.submit(() -> {
            String errorMsg = null;
            try
            {
                errorMsg = apiClient.verifyTokenWithDetail(code, rsn);
            }
            catch (Exception e)
            {
                errorMsg = e.getMessage() != null
                        ? e.getMessage() : "Network error — check your connection.";
                log.warn("Verification request failed: {}", e.getMessage());
            }

            final boolean verified = (errorMsg == null);
            final String finalError = errorMsg;
            SwingUtilities.invokeLater(() -> {
                if (verified)
                {
                    verificationPanel.saveAccountToken(rsn, code);
                    config.authToken(code);
                    state.setVerified(true);
                    state.setVerifiedUsername(rsn);
                    state.setVerificationCode(code);
                    log.info("Verification succeeded for '{}'", rsn);
                    // Rebuild to the verified (account settings) view
                    rebuild();
                }
                else
                {
                    if (verifyButton != null)
                    {
                        verifyButton.setText("Verify Account");
                        verifyButton.setEnabled(true);
                    }
                    verificationPanel.clearAccountToken(rsn);
                    config.authToken("");
                    state.setVerified(false);
                    state.setVerifiedUsername(null);
                    state.setVerificationCode(null);
                    updateConnectionStatus(false, finalError);
                    log.warn("Verification failed for '{}': {}", rsn, finalError);
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
    //  External link warning
    // ═════════════════════════════════════════════════════════════════════════

    private void openExternalLink(String url, String siteName)
    {
        String message =
                "<html><body style='width:270px; font-family:Calibri; font-size:12pt'>"
                + "<b style='font-size:13pt'>&#9888; External Website</b><br><br>"
                + "You are about to open <b>" + siteName + "</b>, a third-party website."
                + "<br><br>"
                + "<b>RuneLite is not affiliated with RuneAlytics</b> and takes no "
                + "responsibility for its content or services.<br><br>"
                + "<b>By continuing you acknowledge:</b><ul style='margin:4px 0 0 16px'>"
                + "<li>This site may set <b>cookies</b> and can see your <b>IP address</b></li>"
                + "<li><b>Do NOT</b> reuse passwords — especially your OSRS, email, "
                + "or banking passwords</li>"
                + "<li>You are solely responsible for your own account security</li>"
                + "</ul></body></html>";

        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                message,
                "External Website — RuneAlytics",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.OK_OPTION)
            LinkBrowser.browse(url);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Button builders
    // ═════════════════════════════════════════════════════════════════════════

    private JButton buildGoldButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(VERIFY_BTN_COLOR);
        btn.setForeground(Color.BLACK);
        btn.setFont(cf(Font.BOLD, 14f));
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
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(new Color(30, 70, 70));
        btn.setForeground(TEAL_COLOR);
        btn.setFont(cf(Font.BOLD, 13f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(TEAL_COLOR.darker(), 1, true),
                new EmptyBorder(5, 14, 5, 14)));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        return btn;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared layout helpers
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel rootPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setOpaque(true);
        panel.setBorder(new EmptyBorder(10, 8, 10, 8));
        return panel;
    }

    private JLabel sectionHeader(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setFont(cf(Font.BOLD, 11f));
        lbl.setForeground(TEAL_COLOR);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    private JLabel buildVersionLabel()
    {
        JLabel lbl = new JLabel("RuneAlytics v1.0.0");
        lbl.setFont(cf(Font.PLAIN, 11f));
        lbl.setForeground(new Color(90, 90, 90));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    private static JLabel compactLabel(String text, Color fg, Font font)
    {
        return compactLabel(text, fg, font, 170);
    }

    private static JLabel compactLabel(String text, Color fg, Font font, int wrapPx)
    {
        JLabel lbl = new JLabel("<html><body style='width:" + wrapPx + "px; margin:0; padding:0'>" + text + "</body></html>");
        lbl.setFont(font);
        lbl.setForeground(fg);
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

    private static BufferedImage tryLoadImage(String resource)
    {
        try { return ImageUtil.loadImageResource(RuneAlyticsSettingsPanel.class, resource); }
        catch (Exception e) { return null; }
    }

    /** Bicubic scaling — good for non-integer scale ratios (e.g. banner logo). */
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

    /** Nearest-neighbour scaling — crisp for exact integer multiples (e.g. 1× → 2×). */
    private static JLabel scaledImageLabelCrisp(BufferedImage src, int w, int h)
    {
        BufferedImage dest = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = dest.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return new JLabel(new ImageIcon(dest));
    }

    private static Component vSpace(int px) { return Box.createRigidArea(new Dimension(0, px)); }
    private static Component hSpace(int px) { return Box.createRigidArea(new Dimension(px, 0)); }

    // ═════════════════════════════════════════════════════════════════════════
    //  External API (called from RuneAlyticsPlugin)
    // ═════════════════════════════════════════════════════════════════════════

    public void refreshLoginState()
    {
        SwingUtilities.invokeLater(() -> {
            if (!state.isVerified())
            {
                // Rebuild the unverified view so the code field / verify button
                // appear only when the player is actually logged into the game.
                rebuild();
            }
        });
        verificationPanel.refreshLoginState();
    }

    public void updateVerificationStatus(boolean verified, String username)
    {
        SwingUtilities.invokeLater(() -> {
            rebuild();
            // Be explicit about the status text in case state lags the argument
            if (verified)
                updateConnectionStatus(true,
                        "Linked as " + (username != null ? username : ""));
            else
                updateConnectionStatus(false, "Your account is not linked.");
        });
    }

    public void updateLastSyncTime() { /* display removed */ }

    /**
     * Re-reads the current config values and rebuilds the verified view so the
     * pill toggles reflect any changes made via the RuneLite config panel.
     * No-op when the unverified view is shown (nothing to sync there).
     */
    public void refreshPrivacySettings()
    {
        if (state.isVerified()) rebuild();
    }

    private void handleVerificationStatusChange()
    {
        SwingUtilities.invokeLater(this::rebuild);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UpperCaseFilter
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
