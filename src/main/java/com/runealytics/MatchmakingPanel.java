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
    private final JLabel playersLabel = RuneAlyticsUi.valueLabel("Players: -");
    private final JLabel matchStatusLabel = RuneAlyticsUi.valueLabel("Status: -");
    private final JLabel worldLabel = RuneAlyticsUi.valueLabel("World: -");
    private final JLabel zoneLabel = RuneAlyticsUi.valueLabel("Zone: -");
    private final JLabel riskLabel = RuneAlyticsUi.valueLabel("Risk: -");
    private final JLabel gearRulesLabel = RuneAlyticsUi.valueLabel("Gear Rules: -");
    private final JLabel player1JoinedLabel = RuneAlyticsUi.valueLabel("Player 1 Joined: -");
    private final JLabel player2JoinedLabel = RuneAlyticsUi.valueLabel("Player 2 Joined: -");
    private final JLabel player1ReadyLabel = RuneAlyticsUi.valueLabel("Player 1 Ready: -");
    private final JLabel player2ReadyLabel = RuneAlyticsUi.valueLabel("Player 2 Ready: -");
    private final JLabel rallyLabel = RuneAlyticsUi.valueLabel("Rally: -");
    private final JLabel winnerLabel = RuneAlyticsUi.valueLabel("Winner: -");
    private final JLabel winnerCombatLabel = RuneAlyticsUi.valueLabel("Winner Combat Level: -");
    private final JLabel winnerEloLabel = RuneAlyticsUi.valueLabel("Winner ELO: -");
    private final JLabel serverMessageLabel = RuneAlyticsUi.valueLabel("Message: -");
    private final JLabel rawResponseLabel = RuneAlyticsUi.valueLabel("Raw Response: -");

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

        JPanel detailsCard = RuneAlyticsUi.cardPanel();
        JLabel detailsHeader = RuneAlyticsUi.bodyLabel("Match Details");
        detailsHeader.setFont(detailsHeader.getFont().deriveFont(Font.BOLD, 12f));
        detailsCard.add(detailsHeader);
        detailsCard.add(RuneAlyticsUi.vSpace(6));
        detailsCard.add(playersLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(matchStatusLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(worldLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(zoneLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(riskLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(gearRulesLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(6));
        detailsCard.add(player1JoinedLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(player2JoinedLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(player1ReadyLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(player2ReadyLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(6));
        detailsCard.add(rallyLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(6));
        detailsCard.add(winnerLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(winnerCombatLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(winnerEloLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(6));
        detailsCard.add(serverMessageLabel);
        detailsCard.add(RuneAlyticsUi.vSpace(4));
        detailsCard.add(rawResponseLabel);

        add(formCard);
        add(RuneAlyticsUi.vSpace(8));
        add(detailsCard);
        add(Box.createVerticalGlue());

        clearMatchDetails();
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
        clearMatchDetails();
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
            updateMatchDetails(session, update);
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

            updateErrorDetails(message, update.getRawResponse());
        }

        updateUi();
    }

    private void updateMatchDetails(MatchmakingSession session, MatchmakingUpdate update)
    {
        playersLabel.setText("Players: " + session.getPlayer1Username() + " vs " + session.getPlayer2Username());
        matchStatusLabel.setText("Status: " + session.getStatus());
        worldLabel.setText("World: " + session.getWorld());
        zoneLabel.setText("Zone: " + session.getZone());
        riskLabel.setText("Risk: " + session.getRisk());
        gearRulesLabel.setText("Gear Rules: " + session.getGearRules());
        player1JoinedLabel.setText("Player 1 Joined: " + session.isPlayer1Joined());
        player2JoinedLabel.setText("Player 2 Joined: " + session.isPlayer2Joined());
        player1ReadyLabel.setText("Player 1 Ready: " + session.isPlayer1ReadyToFight());
        player2ReadyLabel.setText("Player 2 Ready: " + session.isPlayer2ReadyToFight());

        if (session.getRally() != null)
        {
            rallyLabel.setText("Rally: " + session.getRally().getX()
                    + ", " + session.getRally().getY()
                    + " (plane " + session.getRally().getPlane() + ")");
        }
        else
        {
            rallyLabel.setText("Rally: none");
        }

        MatchmakingWinner winner = session.getWinner();
        if (winner != null && winner.getOsrsRsn() != null && !winner.getOsrsRsn().isEmpty())
        {
            winnerLabel.setText("Winner: " + winner.getOsrsRsn());
            winnerCombatLabel.setText("Winner Combat Level: " + winner.getCombatLevel());
            winnerEloLabel.setText("Winner ELO: " + winner.getElo());
        }
        else
        {
            winnerLabel.setText("Winner: -");
            winnerCombatLabel.setText("Winner Combat Level: -");
            winnerEloLabel.setText("Winner ELO: -");
        }

        if (update.getMessage() != null && !update.getMessage().isEmpty())
        {
            serverMessageLabel.setText("Message: " + update.getMessage());
            serverMessageLabel.setVisible(true);
        }
        else
        {
            serverMessageLabel.setText("Message: -");
            serverMessageLabel.setVisible(false);
        }

        if (update.getRawResponse() != null && !update.getRawResponse().isEmpty())
        {
            rawResponseLabel.setText("Raw Response: " + update.getRawResponse());
            rawResponseLabel.setVisible(true);
        }
        else
        {
            rawResponseLabel.setText("Raw Response: -");
            rawResponseLabel.setVisible(false);
        }
    }

    private void updateErrorDetails(String message, String rawResponse)
    {
        clearMatchDetails();
        serverMessageLabel.setText("Message: " + message);
        serverMessageLabel.setVisible(true);

        if (rawResponse != null && !rawResponse.isEmpty())
        {
            rawResponseLabel.setText("Raw Response: " + rawResponse);
            rawResponseLabel.setVisible(true);
        }
        else
        {
            rawResponseLabel.setVisible(false);
        }
    }

    private void clearMatchDetails()
    {
        playersLabel.setText("Players: -");
        matchStatusLabel.setText("Status: -");
        worldLabel.setText("World: -");
        zoneLabel.setText("Zone: -");
        riskLabel.setText("Risk: -");
        gearRulesLabel.setText("Gear Rules: -");
        player1JoinedLabel.setText("Player 1 Joined: -");
        player2JoinedLabel.setText("Player 2 Joined: -");
        player1ReadyLabel.setText("Player 1 Ready: -");
        player2ReadyLabel.setText("Player 2 Ready: -");
        rallyLabel.setText("Rally: -");
        winnerLabel.setText("Winner: -");
        winnerCombatLabel.setText("Winner Combat Level: -");
        winnerEloLabel.setText("Winner ELO: -");
        serverMessageLabel.setText("Message: -");
        serverMessageLabel.setVisible(false);
        rawResponseLabel.setText("Raw Response: -");
        rawResponseLabel.setVisible(false);
    }
}
