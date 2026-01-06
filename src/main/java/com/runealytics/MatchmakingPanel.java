package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

@Singleton
public class MatchmakingPanel extends RuneAlyticsPanelBase implements MatchmakingUpdateListener
{
    private final MatchmakingManager matchmakingManager;
    private final RuneAlyticsState runeAlyticsState;

    private final JTextField matchCodeField = RuneAlyticsUi.inputField();
    private final JButton loadButton = RuneAlyticsUi.primaryButton("Load match");
    private final JLabel statusLabel = RuneAlyticsUi.statusLabel();
    private final JTextArea detailsArea = RuneAlyticsUi.apiResponseArea();

    private boolean loading;

    @Inject
    public MatchmakingPanel(
            MatchmakingManager matchmakingManager,
            RuneAlyticsState runeAlyticsState
    )
    {
        this.matchmakingManager = matchmakingManager;
        this.runeAlyticsState = runeAlyticsState;

        matchmakingManager.setListener(this);

        buildUi();
        wireEvents();

        refreshLoginState();
    }

    private void buildUi()
    {
        addSectionTitle("Matchmaking");
        addSubtitle("RuneAlytics Match Linker");

        JPanel formCard = RuneAlyticsUi.cardPanel();

        JLabel label = RuneAlyticsUi.bodyLabel("Match Code:");
        label.setAlignmentX(LEFT_ALIGNMENT);

        matchCodeField.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, matchCodeField.getPreferredSize().height)
        );

        JPanel buttonRow = new JPanel();
        buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS));
        buttonRow.setOpaque(false);
        buttonRow.setAlignmentX(LEFT_ALIGNMENT);
        buttonRow.add(Box.createHorizontalGlue());
        buttonRow.add(loadButton);
        buttonRow.add(Box.createHorizontalGlue());

        statusLabel.setAlignmentX(LEFT_ALIGNMENT);

        formCard.add(label);
        formCard.add(RuneAlyticsUi.vSpace(4));
        formCard.add(matchCodeField);
        formCard.add(RuneAlyticsUi.vSpace(6));
        formCard.add(buttonRow);
        formCard.add(RuneAlyticsUi.vSpace(6));
        formCard.add(statusLabel);

        JPanel detailsCard = RuneAlyticsUi.apiResponseCard("Match Details", detailsArea);

        add(formCard);
        add(RuneAlyticsUi.vSpace(8));
        add(detailsCard);
        add(Box.createVerticalGlue());
    }

    private void wireEvents()
    {
        loadButton.addActionListener(e -> submitMatchCode());
        matchCodeField.addActionListener(e -> submitMatchCode());
    }

    public void refreshLoginState()
    {
        updateUi();
    }

    private void updateUi()
    {
        boolean loggedIn = runeAlyticsState.isLoggedIn();
        boolean verified = runeAlyticsState.isVerified();

        if (!loggedIn)
        {
            setControlsEnabled(false);
            statusLabel.setText("Please log into RuneLite to load a match.");
            statusLabel.setForeground(ColorScheme.TEXT_COLOR);
            return;
        }

        if (!verified)
        {
            setControlsEnabled(false);
            statusLabel.setText("Verify your RuneAlytics account before loading a match.");
            statusLabel.setForeground(ColorScheme.TEXT_COLOR);
            return;
        }

        if (loading)
        {
            setControlsEnabled(false);
            statusLabel.setText("Loading match...");
            statusLabel.setForeground(ColorScheme.TEXT_COLOR);
            return;
        }

        setControlsEnabled(true);
        if (matchmakingManager.hasActiveMatch())
        {
            MatchmakingSession session = matchmakingManager.getSession();
            if (session != null)
            {
                statusLabel.setText("Match status: " + session.getStatus());
                RuneAlyticsUi.stylePositiveStatus(statusLabel);
                return;
            }
        }

        statusLabel.setText("Enter a match code to load your matchmaking details.");
        statusLabel.setForeground(ColorScheme.TEXT_COLOR);
    }

    private void setControlsEnabled(boolean enabled)
    {
        matchCodeField.setEnabled(enabled);
        loadButton.setEnabled(enabled);
        detailsArea.setEnabled(true);
    }

    private void submitMatchCode()
    {
        if (!runeAlyticsState.isLoggedIn())
        {
            updateUi();
            return;
        }

        if (!runeAlyticsState.isVerified())
        {
            statusLabel.setText("Verify your RuneAlytics account before loading a match.");
            statusLabel.setForeground(ColorScheme.TEXT_COLOR);
            return;
        }

        final String matchCode = matchCodeField.getText().trim();

        if (matchCode.isEmpty())
        {
            statusLabel.setText("Please enter a match code.");
            RuneAlyticsUi.styleNegativeStatus(statusLabel);
            return;
        }

        loading = true;
        detailsArea.setText("");
        updateUi();

        matchmakingManager.loadMatch(matchCode);
    }

    @Override
    public void onMatchmakingUpdate(MatchmakingUpdate update)
    {
        loading = false;

        if (update.isSuccess() && update.getSession() != null)
        {
            MatchmakingSession session = update.getSession();
            statusLabel.setText("Match status: " + session.getStatus());
            RuneAlyticsUi.stylePositiveStatus(statusLabel);
            detailsArea.setText(buildMatchDetails(session, update));
        }
        else
        {
            String message = update.getMessage();
            if (message == null || message.isEmpty())
            {
                message = "Matchmaking request failed.";
            }
            statusLabel.setText(message);
            RuneAlyticsUi.styleNegativeStatus(statusLabel);

            detailsArea.setText(buildErrorDetails(message, update.getRawResponse()));
        }

        updateUi();
    }

    private String buildMatchDetails(MatchmakingSession session, MatchmakingUpdate update)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Players: ")
                .append(session.getPlayer1Username())
                .append(" vs ")
                .append(session.getPlayer2Username())
                .append("\n");

        builder.append("Status: ").append(session.getStatus()).append("\n");
        builder.append("World: ").append(session.getWorld()).append("\n");
        builder.append("Zone: ").append(session.getZone()).append("\n");
        builder.append("Risk: ").append(session.getRisk()).append("\n");
        builder.append("Gear Rules: ").append(session.getGearRules()).append("\n");

        builder.append("Player 1 Joined: ").append(session.isPlayer1Joined()).append("\n");
        builder.append("Player 2 Joined: ").append(session.isPlayer2Joined()).append("\n");
        builder.append("Player 1 Ready: ").append(session.isPlayer1ReadyToFight()).append("\n");
        builder.append("Player 2 Ready: ").append(session.isPlayer2ReadyToFight()).append("\n");

        if (session.getRally() != null)
        {
            builder.append("Rally: ")
                    .append(session.getRally().getX())
                    .append(", ")
                    .append(session.getRally().getY())
                    .append(" (plane ")
                    .append(session.getRally().getPlane())
                    .append(")\n");
        }
        else
        {
            builder.append("Rally: none\n");
        }

        if (session.getWinner() != null && !session.getWinner().isEmpty())
        {
            builder.append("Winner: ").append(session.getWinner()).append("\n");
        }

        if (update.getMessage() != null && !update.getMessage().isEmpty())
        {
            builder.append("\nServer Message: ").append(update.getMessage()).append("\n");
        }

        if (update.getRawResponse() != null && !update.getRawResponse().isEmpty())
        {
            builder.append("\nRaw response:\n").append(update.getRawResponse());
        }

        return builder.toString();
    }

    private String buildErrorDetails(String message, String rawResponse)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Error: ").append(message).append("\n");

        if (rawResponse != null && !rawResponse.isEmpty())
        {
            builder.append("\nRaw response:\n").append(rawResponse);
        }

        return builder.toString();
    }
}
