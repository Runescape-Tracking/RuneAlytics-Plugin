package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private static final DateTimeFormatter TIME_FORMATTER    = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String           RUNEALYTICS_URL   = "https://www.runealytics.com";

    // ── Palette ───────────────────────────────────────────────────────────────
    /** Gold used for the "RUNEALYTICS" brand title */
    private static final Color GOLD_COLOR         = new Color(215, 175, 55);
    /** Teal used for section headers and tagline */
    private static final Color TEAL_COLOR         = new Color(82, 196, 196);
    /** Purple used for the primary Link Account CTA */
    private static final Color LINK_BTN_BG        = new Color(108, 76, 203);
    /** Amber border on the 3rd-party warning card */
    private static final Color WARN_BORDER        = new Color(200, 160, 0, 200);
    /** Gold background on the Continue confirmation button */
    private static final Color CONTINUE_BTN_BG    = new Color(200, 160, 0);

    // ── MaterialTabGroup plumbing ─────────────────────────────────────────────
    /** Content area managed by {@link MaterialTabGroup}; swaps its child on tab select. */
    private final JPanel display = new JPanel(new BorderLayout());

    /** @see MaterialTabGroup */
    private MaterialTabGroup tabGroup;

    // ── Injected dependencies ─────────────────────────────────────────────────
    /** @see RuneAlyticsVerificationPanel */
    private final RuneAlyticsVerificationPanel verificationPanel;
    /** @see RunealyticsConfig */
    private final RunealyticsConfig            config;
    /** @see RuneAlyticsState */
    private final RuneAlyticsState             runeAlyticsState;
    /** @see RunealyticsApiClient */
    private final RunealyticsApiClient         apiClient;
    private final ScheduledExecutorService     executorService;

    // ── State guards ──────────────────────────────────────────────────────────
    /** Tracks whether the Verification tab is currently added to avoid redundant rebuilds. */
    private boolean verificationTabVisible = true;

    // ── Live-update widgets (replaced on each {@link #buildTabs()} call) ──────
    /** Shows "Verified ✓" or "Not Verified" with colour feedback. */
    private JLabel  statusLabel;
    /** Displays the linked OSRS username. */
    private JLabel  usernameLabel;
    /** Timestamp of the last successful bank sync. */
    private JLabel  lastSyncLabel;
    /** "XP: ON/OFF" tracking indicator. */
    private JLabel  xpTrackingLabel;
    /** "Bank: ON/OFF" tracking indicator. */
    private JLabel  bankTrackingLabel;
    /** Triggers a server-side token re-verification. */
    private JButton reVerifyButton;

    // ── Link-account warning (inline; toggled by button click) ───────────────
    /** The amber-bordered 3rd-party disclaimer card; hidden until user clicks Link Account. */
    private JPanel  warningPanel;
    /** Disabled while {@link #warningPanel} is visible. */
    private JButton linkAccountButton;

    @Inject
    public RuneAlyticsSettingsPanel(
            RuneAlyticsVerificationPanel verificationPanel,
            RunealyticsConfig            config,
            RuneAlyticsState             runeAlyticsState,
            RunealyticsApiClient         apiClient,
            ScheduledExecutorService     executorService
    )
    {
        this.verificationPanel = verificationPanel;
        this.config            = config;
        this.runeAlyticsState  = runeAlyticsState;
        this.apiClient         = apiClient;
        this.executorService   = executorService;

        RuneAlyticsUi.styleRootPanel(this);
        this.verificationPanel.setVerificationStatusListener(this::handleVerificationStatusChange);
        buildTabs();
    }

    /**
     * Mirrors the parent container's current height so this panel never requests
     * more vertical space than the sidebar has available.  Without this, the
     * long feature-highlight list would bubble a huge preferred height up through
     * the JTabbedPane and trigger an unwanted RuneLite window resize.
     */
    @Override
    public Dimension getPreferredSize()
    {
        Container p = getParent();
        int h = (p != null && p.getHeight() > 0) ? p.getHeight() : 400;
        return new Dimension(PluginPanel.PANEL_WIDTH, h);
    }

    @Override
    public Dimension getMinimumSize()
    {
        return new Dimension(50, 80);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab construction
    // ═════════════════════════════════════════════════════════════════════════

    private void buildTabs()
    {
        boolean shouldShowVerification = !runeAlyticsState.isVerified();

        removeAll();

        tabGroup = new MaterialTabGroup(display);
        RuneAlyticsUi.styleTabStrip(tabGroup);
        RuneAlyticsUi.styleDisplayPanel(display);

        // Each tab's content is wrapped in its own JScrollPane so that tall
        // content scrolls in-place rather than forcing the sidebar to expand.
        MaterialTab statusTab = new MaterialTab("Status", tabGroup,
                makeScrollPane(createStatusPanel()));
        tabGroup.addTab(statusTab);

        if (shouldShowVerification)
        {
            MaterialTab verifyTab = new MaterialTab("Verification", tabGroup,
                    makeScrollPane(verificationPanel));
            tabGroup.addTab(verifyTab);
        }

        verificationTabVisible = shouldShowVerification;
        tabGroup.select(statusTab);

        add(tabGroup, BorderLayout.NORTH);
        add(display,  BorderLayout.CENTER);

        revalidate();
        repaint();
    }

    /**
     * Creates a transparent, vertically-scrollable pane that never shows a
     * horizontal scrollbar.  The unit increment is tuned for comfortable
     * mouse-wheel scrolling through the feature list.
     */
    private JScrollPane makeScrollPane(JComponent content)
    {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Status panel layout
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel createStatusPanel()
    {
        JPanel panel = RuneAlyticsUi.rootContentPanel();

        panel.add(buildBrandingSection());
        panel.add(RuneAlyticsUi.vSpace(14));

        panel.add(buildAccountStatusSection());
        panel.add(RuneAlyticsUi.vSpace(14));

        panel.add(sectionHeader("BENEFITS"));
        panel.add(RuneAlyticsUi.vSpace(6));
        panel.add(buildBenefitsSection());
        panel.add(RuneAlyticsUi.vSpace(14));

        panel.add(sectionHeader("LINK TO RUNEALYTICS"));
        panel.add(RuneAlyticsUi.vSpace(6));
        panel.add(buildLinkSection());

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ── Branding: gold logo, teal tagline, description ───────────────────────

    private JPanel buildBrandingSection()
    {
        JPanel p = RuneAlyticsUi.verticalPanel();

        JLabel title = new JLabel("RUNEALYTICS");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(GOLD_COLOR);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(title);

        JLabel tagline = new JLabel("KNOW MORE. PLAY SMARTER.");
        tagline.setFont(tagline.getFont().deriveFont(Font.BOLD, 10f));
        tagline.setForeground(TEAL_COLOR);
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(tagline);

        p.add(RuneAlyticsUi.vSpace(8));

        JLabel desc = new JLabel(
                "<html><body style='width:195px'>"
                + "<span style='color:#cccccc'>RuneAlytics gives you powerful insights, "
                + "real-time data, and advanced tracking tools to enhance your OSRS experience.</span>"
                + "</body></html>");
        desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 11f));
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(desc);

        return p;
    }

    // ── Account status card ───────────────────────────────────────────────────

    private JPanel buildAccountStatusSection()
    {
        JPanel card = RuneAlyticsUi.cardPanel();

        card.add(sectionHeader("ACCOUNT STATUS"));
        card.add(RuneAlyticsUi.vSpace(6));

        statusLabel = RuneAlyticsUi.statusLabel();
        statusLabel.setText("Not Verified");
        RuneAlyticsUi.styleNegativeStatus(statusLabel);
        card.add(statusLabel);
        card.add(RuneAlyticsUi.vSpace(3));

        usernameLabel = RuneAlyticsUi.valueLabel("Username: N/A");
        card.add(usernameLabel);
        card.add(RuneAlyticsUi.vSpace(8));

        // Compact tracking-status row
        JLabel lootLabel = RuneAlyticsUi.valueLabel("Loot: ON");
        RuneAlyticsUi.stylePositiveStatus(lootLabel);

        xpTrackingLabel = RuneAlyticsUi.valueLabel("XP: " + (config.enableXpTracking() ? "ON" : "OFF"));
        if (config.enableXpTracking()) RuneAlyticsUi.stylePositiveStatus(xpTrackingLabel);
        else xpTrackingLabel.setForeground(Color.GRAY);

        bankTrackingLabel = RuneAlyticsUi.valueLabel("Bank: " + (config.enableBankSync() ? "ON" : "OFF"));
        if (config.enableBankSync()) RuneAlyticsUi.stylePositiveStatus(bankTrackingLabel);
        else bankTrackingLabel.setForeground(Color.GRAY);

        card.add(RuneAlyticsUi.formRow(
                lootLabel,        RuneAlyticsUi.hSpace(8),
                xpTrackingLabel,  RuneAlyticsUi.hSpace(8),
                bankTrackingLabel
        ));
        card.add(RuneAlyticsUi.vSpace(6));

        JLabel syncTitle = RuneAlyticsUi.valueLabel("Last Bank Sync: ");
        lastSyncLabel    = RuneAlyticsUi.valueLabel("Never");
        card.add(RuneAlyticsUi.formRow(syncTitle, lastSyncLabel));
        card.add(RuneAlyticsUi.vSpace(8));

        reVerifyButton = RuneAlyticsUi.secondaryButton("Re-verify Account");
        reVerifyButton.setEnabled(hasStoredToken());
        reVerifyButton.addActionListener(e -> reVerify());
        card.add(reVerifyButton);

        return card;
    }

    // ── Benefits cards ────────────────────────────────────────────────────────

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

    // ── Link-to-RuneAlytics section ───────────────────────────────────────────

    private JPanel buildLinkSection()
    {
        JPanel p = RuneAlyticsUi.verticalPanel();

        JLabel connectText = new JLabel(
                "<html><body style='width:195px'>"
                + "<span style='color:#cccccc'>"
                + "<b>Connect</b> your account to unlock full tracking and analytics features."
                + "</span></body></html>");
        connectText.setFont(connectText.getFont().deriveFont(Font.PLAIN, 11f));
        connectText.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(connectText);
        p.add(RuneAlyticsUi.vSpace(10));

        linkAccountButton = buildPurpleButton("Link Account");
        linkAccountButton.addActionListener(e -> revealWarning(p));
        p.add(linkAccountButton);

        // Warning card: hidden until Link Account is clicked
        warningPanel = buildWarningPanel(p);
        warningPanel.setVisible(false);
        p.add(RuneAlyticsUi.vSpace(8));
        p.add(warningPanel);

        p.add(RuneAlyticsUi.vSpace(14));

        JLabel alreadyLinked = RuneAlyticsUi.valueLabel("Already linked?");
        p.add(alreadyLinked);
        p.add(RuneAlyticsUi.vSpace(4));

        JButton manageBtn = RuneAlyticsUi.secondaryButton("Manage Connection");
        manageBtn.addActionListener(e -> revealWarning(p));
        p.add(manageBtn);
        p.add(RuneAlyticsUi.vSpace(8));

        return p;
    }

    /** Purple full-width button matching the screenshot's Link Account CTA. */
    private JButton buildPurpleButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(LINK_BTN_BG);
        btn.setForeground(Color.WHITE);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 13f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(LINK_BTN_BG.darker(), 1, true),
                new EmptyBorder(6, 16, 6, 16)
        ));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return btn;
    }

    /**
     * Builds the amber-bordered 3rd-party disclaimer card.
     * Buttons inside capture {@code linkSection} so they can revalidate the
     * correct parent after toggling visibility.
     *
     * @param linkSection the panel that owns this warning card
     */
    private JPanel buildWarningPanel(JPanel linkSection)
    {
        JPanel warn = RuneAlyticsUi.cardPanel();
        warn.setBorder(new CompoundBorder(
                new LineBorder(WARN_BORDER, 1, true),
                new EmptyBorder(10, 10, 10, 10)
        ));

        JLabel iconLabel = new JLabel("⚠  WARNING");
        iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 11f));
        iconLabel.setForeground(new Color(240, 180, 0));
        iconLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        warn.add(iconLabel);
        warn.add(RuneAlyticsUi.vSpace(6));

        JLabel warnText = new JLabel(
                "<html><body style='width:185px'>"
                + "<span style='color:#cccccc'>"
                + "You are about to leave the client to link your account on the official RuneAlytics website."
                + "<br><br>"
                + "This is a 3rd party website. We are not responsible for its content or policies."
                + "</span></body></html>");
        warnText.setFont(warnText.getFont().deriveFont(Font.PLAIN, 11f));
        warnText.setAlignmentX(Component.LEFT_ALIGNMENT);
        warn.add(warnText);
        warn.add(RuneAlyticsUi.vSpace(10));

        JButton cancelBtn = RuneAlyticsUi.secondaryButton("Cancel");
        cancelBtn.addActionListener(e -> {
            warningPanel.setVisible(false);
            linkAccountButton.setEnabled(true);
            linkSection.revalidate();
            linkSection.repaint();
        });

        JButton continueBtn = buildGoldButton("Continue");
        continueBtn.addActionListener(e -> {
            warningPanel.setVisible(false);
            linkAccountButton.setEnabled(true);
            linkSection.revalidate();
            linkSection.repaint();
            LinkBrowser.browse(RUNEALYTICS_URL);
        });

        warn.add(RuneAlyticsUi.formRow(cancelBtn, RuneAlyticsUi.hSpace(8), continueBtn));
        return warn;
    }

    /** Gold button used for the Continue confirmation action. */
    private JButton buildGoldButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(CONTINUE_BTN_BG);
        btn.setForeground(Color.BLACK);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 12f));
        btn.setBorder(new CompoundBorder(
                new LineBorder(new Color(180, 140, 0), 1, true),
                new EmptyBorder(4, 12, 4, 12)
        ));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        return btn;
    }

    /** Disables the Link Account button and reveals the 3rd-party warning card. */
    private void revealWarning(JPanel linkSection)
    {
        linkAccountButton.setEnabled(false);
        warningPanel.setVisible(true);
        linkSection.revalidate();
        linkSection.repaint();
    }

    // ── Shared teal section-header label ─────────────────────────────────────

    private JLabel sectionHeader(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 10f));
        lbl.setForeground(TEAL_COLOR);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Re-verify
    // ═════════════════════════════════════════════════════════════════════════

    private void reVerify()
    {
        String token = config.authToken();
        String rsn   = runeAlyticsState.getVerifiedUsername();

        if (token == null || token.isEmpty())
        {
            statusLabel.setText("No auth token — link your account first.");
            RuneAlyticsUi.styleNegativeStatus(statusLabel);
            return;
        }

        reVerifyButton.setEnabled(false);
        reVerifyButton.setText("Checking...");
        statusLabel.setText("Checking with server...");

        executorService.submit(() -> {
            boolean confirmed = false;
            try
            {
                confirmed = apiClient.verifyToken(token, rsn);
            }
            catch (Exception e)
            {
                log.warn("Re-verify request failed: {}", e.getMessage());
            }

            final boolean success = confirmed;
            SwingUtilities.invokeLater(() -> {
                reVerifyButton.setText("Re-verify Account");
                reVerifyButton.setEnabled(true);

                if (success)
                {
                    runeAlyticsState.setVerified(true);
                    if (rsn != null) runeAlyticsState.setVerifiedUsername(rsn);
                    runeAlyticsState.setVerificationCode(token);
                    statusLabel.setText("Verified ✓");
                    RuneAlyticsUi.stylePositiveStatus(statusLabel);
                    log.info("Re-verify succeeded for '{}'", rsn);
                }
                else
                {
                    runeAlyticsState.setVerified(false);
                    runeAlyticsState.setVerifiedUsername(null);
                    runeAlyticsState.setVerificationCode(null);
                    config.authToken("");
                    statusLabel.setText("Not Verified — token rejected.");
                    RuneAlyticsUi.styleNegativeStatus(statusLabel);
                    log.warn("Re-verify failed for '{}' — token cleared", rsn);
                }

                syncTabs();
            });
        });
    }

    private boolean hasStoredToken()
    {
        String t = config.authToken();
        return t != null && !t.isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  External API (called from RuneAlyticsPlugin)
    // ═════════════════════════════════════════════════════════════════════════

    public void refreshLoginState()
    {
        syncTabs();
        verificationPanel.refreshLoginState();
        updateVerificationStatus(runeAlyticsState.isVerified(), runeAlyticsState.getVerifiedUsername());
    }

    public void updateVerificationStatus(boolean verified, String username)
    {
        syncTabs();
        SwingUtilities.invokeLater(() -> {
            if (statusLabel == null) return;

            if (verified)
            {
                statusLabel.setText("Verified ✓");
                RuneAlyticsUi.stylePositiveStatus(statusLabel);
                usernameLabel.setText("Username: " + (username != null ? username : "N/A"));
                log.info("UI updated: Account verified as {}", username);
            }
            else
            {
                statusLabel.setText("Not Verified");
                RuneAlyticsUi.styleNegativeStatus(statusLabel);
                usernameLabel.setText("Username: N/A");
                log.info("UI updated: Account not verified");
            }

            if (reVerifyButton != null)
                reVerifyButton.setEnabled(hasStoredToken());
        });
    }

    private void syncTabs()
    {
        boolean shouldShow = !runeAlyticsState.isVerified();
        if (tabGroup == null || verificationTabVisible != shouldShow)
        {
            SwingUtilities.invokeLater(this::buildTabs);
        }
    }

    public void updateLastSyncTime()
    {
        SwingUtilities.invokeLater(() -> {
            if (lastSyncLabel != null)
                lastSyncLabel.setText(LocalDateTime.now().format(TIME_FORMATTER));
        });
    }

    private void handleVerificationStatusChange()
    {
        updateVerificationStatus(
                runeAlyticsState.isVerified(),
                runeAlyticsState.getVerifiedUsername()
        );
    }
}
