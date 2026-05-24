package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JPanel display = new JPanel(new BorderLayout());

    private MaterialTabGroup tabGroup;

    private final RuneAlyticsVerificationPanel verificationPanel;
    private final RunealyticsConfig            config;
    private final RuneAlyticsState             runeAlyticsState;
    private final RunealyticsApiClient         apiClient;
    private final ScheduledExecutorService     executorService;

    private boolean verificationTabVisible = true;

    // Status panel widgets
    private JLabel statusLabel;
    private JLabel usernameLabel;
    private JLabel lastSyncLabel;
    private JLabel xpTrackingLabel;
    private JLabel bankTrackingLabel;
    private JButton reVerifyButton;

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

    private void buildTabs()
    {
        boolean shouldShowVerification = !runeAlyticsState.isVerified();

        if (tabGroup != null)
        {
            remove(tabGroup);
        }

        tabGroup = new MaterialTabGroup(display);
        RuneAlyticsUi.styleTabStrip(tabGroup);
        RuneAlyticsUi.styleDisplayPanel(display);

        MaterialTab statusTab = new MaterialTab("Status", tabGroup, createStatusPanel());
        tabGroup.addTab(statusTab);

        if (shouldShowVerification)
        {
            MaterialTab verificationTab = new MaterialTab("Verification", tabGroup, verificationPanel);
            tabGroup.addTab(verificationTab);
        }

        verificationTabVisible = shouldShowVerification;

        tabGroup.select(statusTab);

        add(tabGroup, BorderLayout.NORTH);
        add(display, BorderLayout.CENTER);

        revalidate();
        repaint();
    }

    private JPanel createStatusPanel()
    {
        JPanel panel = RuneAlyticsUi.rootContentPanel();

        JLabel titleLabel = RuneAlyticsUi.titleLabel("RuneAlytics");
        panel.add(titleLabel);
        panel.add(RuneAlyticsUi.vSpace(15));

        // ── Account status ───────────────────────────────────────────────────
        JLabel statusTitleLabel = RuneAlyticsUi.bodyLabel("Account:");
        panel.add(statusTitleLabel);
        panel.add(RuneAlyticsUi.vSpace(5));

        statusLabel = RuneAlyticsUi.statusLabel();
        statusLabel.setText("Not Verified");
        RuneAlyticsUi.styleNegativeStatus(statusLabel);
        panel.add(statusLabel);
        panel.add(RuneAlyticsUi.vSpace(5));

        usernameLabel = RuneAlyticsUi.valueLabel("Username: N/A");
        panel.add(usernameLabel);
        panel.add(RuneAlyticsUi.vSpace(10));

        reVerifyButton = RuneAlyticsUi.primaryButton("Re-verify Account");
        reVerifyButton.setEnabled(hasStoredToken());
        reVerifyButton.addActionListener(e -> reVerify());
        panel.add(reVerifyButton);
        panel.add(RuneAlyticsUi.vSpace(15));

        // ── Active tracking card ─────────────────────────────────────────────
        JPanel trackingCard = RuneAlyticsUi.cardPanel();
        JLabel trackingTitle = RuneAlyticsUi.bodyLabel("Active Tracking:");
        trackingCard.add(trackingTitle);
        trackingCard.add(RuneAlyticsUi.vSpace(8));

        // Loot tracking is always on (issue #10)
        JLabel lootStatus = RuneAlyticsUi.valueLabel("Loot Tracking: ON (always)");
        RuneAlyticsUi.stylePositiveStatus(lootStatus);
        trackingCard.add(lootStatus);
        trackingCard.add(RuneAlyticsUi.vSpace(5));

        xpTrackingLabel = RuneAlyticsUi.valueLabel(
                "XP Tracking: " + (config.enableXpTracking() ? "ON" : "OFF"));
        if (config.enableXpTracking()) RuneAlyticsUi.stylePositiveStatus(xpTrackingLabel);
        else xpTrackingLabel.setForeground(Color.GRAY);
        trackingCard.add(xpTrackingLabel);
        trackingCard.add(RuneAlyticsUi.vSpace(5));

        bankTrackingLabel = RuneAlyticsUi.valueLabel(
                "Bank Tracking: " + (config.enableBankSync() ? "ON" : "OFF"));
        if (config.enableBankSync()) RuneAlyticsUi.stylePositiveStatus(bankTrackingLabel);
        else bankTrackingLabel.setForeground(Color.GRAY);
        trackingCard.add(bankTrackingLabel);
        panel.add(trackingCard);
        panel.add(RuneAlyticsUi.vSpace(15));

        // ── Last bank sync ───────────────────────────────────────────────────
        JLabel lastSyncTitleLabel = RuneAlyticsUi.bodyLabel("Last Bank Sync:");
        panel.add(lastSyncTitleLabel);
        panel.add(RuneAlyticsUi.vSpace(5));
        lastSyncLabel = RuneAlyticsUi.valueLabel("Never");
        panel.add(lastSyncLabel);
        panel.add(RuneAlyticsUi.vSpace(18));

        // ── Feature highlights (issue #11) ───────────────────────────────────
        panel.add(buildFeatureHighlightsCard());
        panel.add(RuneAlyticsUi.vSpace(12));

        panel.add(Box.createVerticalGlue());

        JTextArea infoText = RuneAlyticsUi.infoTextArea(
                "Toggle XP / Bank sync from the RuneLite\n" +
                        "wrench icon → search 'RuneAlytics'.\n\n" +
                        "Loot tracking is always on."
        );
        panel.add(infoText);

        return panel;
    }

    /**
     * Returns a card that summarises every RuneAlytics feature so users can see
     * everything the plugin offers at a glance (issue #11).
     */
    private JPanel buildFeatureHighlightsCard()
    {
        JPanel card = RuneAlyticsUi.cardPanel();
        JLabel header = RuneAlyticsUi.bodyLabel("What RuneAlytics tracks:");
        card.add(header);
        card.add(RuneAlyticsUi.vSpace(8));

        addFeatureRow(card, "🗡  Boss kills & drops",
                "NPC loot, reward chests, instances and rare unique drops are\n" +
                "tracked automatically.");
        addFeatureRow(card, "🌿  Skilling drops",
                "Logs, fish, ores, herbs, runes, hunter loot and impling jars\n" +
                "are bucketed per skill.");
        addFeatureRow(card, "👜  Pickpocketing",
                "Per-NPC attempt counts and loot, including Rogues' outfit\n" +
                "bonuses captured via inventory diff.");
        addFeatureRow(card, "💍  Ring of Wealth auto-pickup",
                "Coins / alched items the ring grabs for you are credited\n" +
                "back to the source kill.");
        addFeatureRow(card, "📊  XP tracking",
                "Skill XP is batched every 30 seconds and synced to your\n" +
                "RuneAlytics dashboard.");
        addFeatureRow(card, "🏦  Bank value",
                "Full bank + worn equipment + carried inventory snapshot\n" +
                "sent on bank close.");
        addFeatureRow(card, "📥  RuneLite import",
                "Use the ⬇ icon in the Loot Tracker tab to import history\n" +
                "from RuneLite's built-in tracker.");
        addFeatureRow(card, "⚔  Match Finder",
                "Discover open PvP / boss-mass events and joinable rallies.");
        addFeatureRow(card, "🧮  Theory-crafting (on the website)",
                "DPS calculators, gear comparisons and per-boss BiS guides\n" +
                "are available at runealytics.com.");
        return card;
    }

    private void addFeatureRow(JPanel parent, String title, String description)
    {
        JLabel t = RuneAlyticsUi.bodyLabel(title);
        t.setForeground(java.awt.Color.WHITE);
        parent.add(t);
        JLabel d = new JLabel("<html><span style='color:#aaaaaa'>" + description.replace("\n", "<br>") + "</span></html>");
        d.setForeground(new java.awt.Color(170, 170, 170));
        d.setFont(d.getFont().deriveFont(11f));
        d.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        parent.add(d);
        parent.add(RuneAlyticsUi.vSpace(6));
    }

    // ── Re-verify ────────────────────────────────────────────────────────────

    private void reVerify()
    {
        String token = config.authToken();
        String rsn   = runeAlyticsState.getVerifiedUsername();

        if (token == null || token.isEmpty())
        {
            statusLabel.setText("No auth token in config — link your account first.");
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

                    statusLabel.setText("Not Verified — token rejected by server.");
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

    // ── External API ─────────────────────────────────────────────────────────

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
            if (statusLabel != null)
            {
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
            }
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
        updateVerificationStatus(runeAlyticsState.isVerified(), runeAlyticsState.getVerifiedUsername());
    }
}
