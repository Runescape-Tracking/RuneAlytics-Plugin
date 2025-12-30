package com.runealytics;

import net.runelite.client.ui.ColorScheme;
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
    private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

    private final RuneAlyticsVerificationPanel verificationPanel;
    private final RunealyticsConfig config;

    // Status panel labels
    private JLabel statusLabel;
    private JLabel usernameLabel;
    private JLabel lastSyncLabel;
    private JLabel xpTrackingLabel;
    private JLabel bankTrackingLabel;

    @Inject
    public RuneAlyticsSettingsPanel(
            RuneAlyticsVerificationPanel verificationPanel,
            RunealyticsConfig config
    )
    {
        this.verificationPanel = verificationPanel;
        this.config = config;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        buildTabs();
    }

    private void buildTabs()
    {
        // Style the tab strip
        tabGroup.setLayout(new BoxLayout(tabGroup, BoxLayout.X_AXIS));
        tabGroup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tabGroup.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tabGroup.setOpaque(true);

        // Style the display area (content panel)
        display.setBackground(ColorScheme.DARK_GRAY_COLOR);
        display.setOpaque(true);

        // Create the "Verification" tab and associate it with the verification panel
        MaterialTab verificationTab = new MaterialTab("Verification", tabGroup, verificationPanel);

        // Create the "Status" tab with tracking information
        MaterialTab statusTab = new MaterialTab("Status", tabGroup, createStatusPanel());

        tabGroup.addTab(verificationTab);
        tabGroup.addTab(statusTab);

        // IMPORTANT: select via the *group*, so it swaps the content into `display`
        tabGroup.select(verificationTab);

        add(tabGroup, BorderLayout.NORTH);
        add(display, BorderLayout.CENTER);
    }

    private JPanel createStatusPanel()
    {
        JPanel panel = new JPanel();
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Title
        JLabel titleLabel = new JLabel("Tracking Status");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(15));

        // Verification Status
        JLabel statusTitleLabel = new JLabel("Verification:");
        statusTitleLabel.setForeground(Color.WHITE);
        statusTitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(statusTitleLabel);
        panel.add(Box.createVerticalStrut(5));

        statusLabel = new JLabel("Not Verified");
        statusLabel.setForeground(Color.RED);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(5));

        usernameLabel = new JLabel("Username: N/A");
        usernameLabel.setForeground(Color.LIGHT_GRAY);
        usernameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(usernameLabel);
        panel.add(Box.createVerticalStrut(15));

        // Tracking Features Card
        JPanel trackingCard = new JPanel();
        trackingCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        trackingCard.setLayout(new BoxLayout(trackingCard, BoxLayout.Y_AXIS));
        trackingCard.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        trackingCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel trackingTitle = new JLabel("Active Tracking:");
        trackingTitle.setForeground(Color.WHITE);
        trackingTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        trackingCard.add(trackingTitle);
        trackingCard.add(Box.createVerticalStrut(8));

        xpTrackingLabel = new JLabel("✓ XP Tracking: " + (config.enableXpTracking() ? "ON" : "OFF"));
        xpTrackingLabel.setForeground(config.enableXpTracking() ? Color.GREEN : Color.GRAY);
        xpTrackingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        trackingCard.add(xpTrackingLabel);
        trackingCard.add(Box.createVerticalStrut(5));

        bankTrackingLabel = new JLabel("✓ Bank Tracking: " + (config.enableBankSync() ? "ON" : "OFF"));
        bankTrackingLabel.setForeground(config.enableBankSync() ? Color.GREEN : Color.GRAY);
        bankTrackingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        trackingCard.add(bankTrackingLabel);

        panel.add(trackingCard);
        panel.add(Box.createVerticalStrut(15));

        // Last Bank Sync
        JLabel lastSyncTitleLabel = new JLabel("Last Bank Sync:");
        lastSyncTitleLabel.setForeground(Color.WHITE);
        lastSyncTitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lastSyncTitleLabel);
        panel.add(Box.createVerticalStrut(5));

        lastSyncLabel = new JLabel("Never");
        lastSyncLabel.setForeground(Color.LIGHT_GRAY);
        lastSyncLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lastSyncLabel);

        panel.add(Box.createVerticalGlue());

        // Configuration Info
        JTextArea infoText = new JTextArea(
                "To change tracking settings, use\n" +
                        "the RuneLite configuration panel.\n\n" +
                        "Click the wrench icon and search\n" +
                        "for 'RuneAlytics'."
        );
        infoText.setEditable(false);
        infoText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        infoText.setForeground(Color.LIGHT_GRAY);
        infoText.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        infoText.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(infoText);

        return panel;
    }

    /** Called externally when login state or context changes */
    public void refreshLoginState()
    {
        verificationPanel.refreshLoginState();
    }

    public void updateVerificationStatus(boolean verified, String username)
    {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null)
            {
                if (verified)
                {
                    statusLabel.setText("✓ Verified");
                    statusLabel.setForeground(Color.GREEN);
                    usernameLabel.setText("Username: " + (username != null ? username : "N/A"));
                }
                else
                {
                    statusLabel.setText("✗ Not Verified");
                    statusLabel.setForeground(Color.RED);
                    usernameLabel.setText("Username: N/A");
                }
            }
        });
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
}