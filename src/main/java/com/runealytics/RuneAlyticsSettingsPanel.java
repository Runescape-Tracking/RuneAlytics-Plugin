package com.runealytics;

import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Display area where MaterialTabGroup will put the selected tab's content
    private final JPanel display = new JPanel(new BorderLayout());

    // MaterialTabGroup manages the tab strip and swaps content into `display`
    private MaterialTabGroup tabGroup;

    private final RuneAlyticsVerificationPanel verificationPanel;
    private final RunealyticsConfig config;
    private final RuneAlyticsState runeAlyticsState;

    private boolean verificationTabVisible = true;

    // Status panel labels
    private JLabel statusLabel;
    private JLabel usernameLabel;
    private JLabel lastSyncLabel;
    private JLabel xpTrackingLabel;
    private JLabel bankTrackingLabel;

    @Inject
    public RuneAlyticsSettingsPanel(
            RuneAlyticsVerificationPanel verificationPanel,
            RunealyticsConfig config,
            RuneAlyticsState runeAlyticsState
    )
    {
        this.verificationPanel = verificationPanel;
        this.config = config;
        this.runeAlyticsState = runeAlyticsState;

        // All styling delegated to RuneAlyticsUi
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
        // Style tab strip + display via shared helpers
        RuneAlyticsUi.styleTabStrip(tabGroup);
        RuneAlyticsUi.styleDisplayPanel(display);

        // Create the "Status" tab with tracking information
        MaterialTab statusTab = new MaterialTab("Status", tabGroup, createStatusPanel());

        tabGroup.addTab(statusTab);

        if (shouldShowVerification)
        {
            // Create the "Verification" tab and associate it with the verification panel
            MaterialTab verificationTab = new MaterialTab("Verification", tabGroup, verificationPanel);
            tabGroup.addTab(verificationTab);
        }

        verificationTabVisible = shouldShowVerification;

        // IMPORTANT: select via the *group*, so it swaps the content into `display`
        tabGroup.select(statusTab);

        add(tabGroup, BorderLayout.NORTH);
        add(display, BorderLayout.CENTER);

        revalidate();
        repaint();
    }

    private JPanel createStatusPanel()
    {
        // Use standard root content panel styling (padding, bg, etc.)
        JPanel panel = RuneAlyticsUi.rootContentPanel();

        // Title
        JLabel titleLabel = RuneAlyticsUi.titleLabel("Tracking Status");
        panel.add(titleLabel);
        panel.add(RuneAlyticsUi.vSpace(15));

        // Verification Status
        JLabel statusTitleLabel = RuneAlyticsUi.bodyLabel("Verification:");
        panel.add(statusTitleLabel);
        panel.add(RuneAlyticsUi.vSpace(5));

        statusLabel = RuneAlyticsUi.statusLabel();
        statusLabel.setText("Not Verified");
        RuneAlyticsUi.styleNegativeStatus(statusLabel);
        panel.add(statusLabel);
        panel.add(RuneAlyticsUi.vSpace(5));

        usernameLabel = RuneAlyticsUi.valueLabel("Username: N/A");
        panel.add(usernameLabel);
        panel.add(RuneAlyticsUi.vSpace(15));

        // Tracking Features Card
        JPanel trackingCard = RuneAlyticsUi.cardPanel();

        JLabel trackingTitle = RuneAlyticsUi.bodyLabel("Active Tracking:");
        trackingCard.add(trackingTitle);
        trackingCard.add(RuneAlyticsUi.vSpace(8));

        xpTrackingLabel = RuneAlyticsUi.valueLabel(
                "✓ XP Tracking: " + (config.enableXpTracking() ? "ON" : "OFF")
        );
        if (config.enableXpTracking())
        {
            RuneAlyticsUi.stylePositiveStatus(xpTrackingLabel);
        }
        else
        {
            xpTrackingLabel.setForeground(Color.GRAY);
        }
        trackingCard.add(xpTrackingLabel);
        trackingCard.add(RuneAlyticsUi.vSpace(5));

        bankTrackingLabel = RuneAlyticsUi.valueLabel(
                "✓ Bank Tracking: " + (config.enableBankSync() ? "ON" : "OFF")
        );
        if (config.enableBankSync())
        {
            RuneAlyticsUi.stylePositiveStatus(bankTrackingLabel);
        }
        else
        {
            bankTrackingLabel.setForeground(Color.GRAY);
        }
        trackingCard.add(bankTrackingLabel);

        panel.add(trackingCard);
        panel.add(RuneAlyticsUi.vSpace(15));

        // Last Bank Sync
        JLabel lastSyncTitleLabel = RuneAlyticsUi.bodyLabel("Last Bank Sync:");
        panel.add(lastSyncTitleLabel);
        panel.add(RuneAlyticsUi.vSpace(5));

        lastSyncLabel = RuneAlyticsUi.valueLabel("Never");
        panel.add(lastSyncLabel);

        panel.add(Box.createVerticalGlue());

        // Configuration Info – now using global styled infoTextArea
        JTextArea infoText = RuneAlyticsUi.infoTextArea(
                "To change tracking settings, use\n" +
                        "the RuneLite configuration panel.\n\n" +
                        "Click the wrench icon and search\n" +
                        "for 'RuneAlytics'."
        );
        panel.add(infoText);

        return panel;
    }

    /** Called externally when login state or context changes */
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
                    statusLabel.setText("✓ Verified");
                    RuneAlyticsUi.stylePositiveStatus(statusLabel);
                    usernameLabel.setText("Username: " + (username != null ? username : "N/A"));
                }
                else
                {
                    statusLabel.setText("✗ Not Verified");
                    RuneAlyticsUi.styleNegativeStatus(statusLabel);
                    usernameLabel.setText("Username: N/A");
                }
            }
        });
    }

    private void syncTabs()
    {
        boolean shouldShowVerification = !runeAlyticsState.isVerified();
        if (tabGroup == null || verificationTabVisible != shouldShowVerification)
        {
            buildTabs();
        }
    }

    public void updateLastSyncTime()
    {
        SwingUtilities.invokeLater(() -> {
            if (lastSyncLabel != null)
            {
                lastSyncLabel.setText(LocalDateTime.now().format(TIME_FORMATTER));
            }
        });
    }

    private void handleVerificationStatusChange()
    {
        updateVerificationStatus(runeAlyticsState.isVerified(), runeAlyticsState.getVerifiedUsername());
    }
}
