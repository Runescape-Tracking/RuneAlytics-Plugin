package com.runealytics;

import com.google.gson.JsonObject;
import net.runelite.client.ui.ColorScheme;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

@Singleton
public class DuelArenaMatchPanel extends JPanel
{
    private static final String DUEL_ARENA_PATH = "/duel_arena";

    private final OkHttpClient httpClient;
    private final RuneAlyticsState runeAlyticsState;
    private final RunealyticsConfig config;
    private final ScheduledExecutorService executorService;

    private final JTextField matchCodeField = RuneAlyticsUi.inputField();
    private final JButton submitButton = RuneAlyticsUi.primaryButton("Load match");
    private final JLabel statusLabel = RuneAlyticsUi.statusLabel();
    private final JTextArea resultArea = RuneAlyticsUi.apiResponseArea();

    private final JPanel headerPanel;
    private final JPanel contentPanel;
    private final JLabel loginMessage;

    @Inject
    public DuelArenaMatchPanel(
            OkHttpClient httpClient,
            RuneAlyticsState runeAlyticsState,
            RunealyticsConfig config,
            ScheduledExecutorService executorService
    )
    {
        this.httpClient = httpClient;
        this.runeAlyticsState = runeAlyticsState;
        this.config = config;
        this.executorService = executorService;

        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        this.headerPanel = buildHeader();
        this.contentPanel = buildContent();

        this.loginMessage = new JLabel("Please log in.", SwingConstants.CENTER);
        loginMessage.setForeground(ColorScheme.TEXT_COLOR);
        loginMessage.setFont(loginMessage.getFont().deriveFont(Font.BOLD, 14f));

        submitButton.addActionListener(e -> submitMatchCode());

        refreshLoginState();
    }

    public void refreshLoginState()
    {
        if (runeAlyticsState.isLoggedIn())
        {
            showLoggedIn();
        }
        else
        {
            showLoggedOut();
        }
    }

    private void showLoggedIn()
    {
        removeAll();
        add(headerPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void showLoggedOut()
    {
        removeAll();
        add(loginMessage, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private JPanel buildHeader()
    {
        JPanel header = RuneAlyticsUi.verticalPanel();

        JLabel title = RuneAlyticsUi.titleLabel("Duel Arena");
        JLabel subtitle = RuneAlyticsUi.subtitleLabel("RuneAlytics Match Linker");

        header.add(title);
        header.add(subtitle);

        return header;
    }

    private JPanel buildContent()
    {
        JPanel container = RuneAlyticsUi.verticalPanel();

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
        buttonRow.add(submitButton);
        buttonRow.add(Box.createHorizontalGlue());

        statusLabel.setAlignmentX(LEFT_ALIGNMENT);

        formCard.add(label);
        formCard.add(RuneAlyticsUi.vSpace(4));
        formCard.add(matchCodeField);
        formCard.add(RuneAlyticsUi.vSpace(6));
        formCard.add(buttonRow);
        formCard.add(RuneAlyticsUi.vSpace(6));
        formCard.add(statusLabel);

        JPanel apiCard = RuneAlyticsUi.apiResponseCard("API Response", resultArea);

        container.add(formCard);
        container.add(RuneAlyticsUi.vSpace(8));
        container.add(apiCard);

        return container;
    }

    private void submitMatchCode()
    {
        if (!runeAlyticsState.isLoggedIn())
        {
            showLoggedOut();
            return;
        }

        final String matchCode = matchCodeField.getText().trim();

        if (matchCode.isEmpty())
        {
            statusLabel.setText("Please enter a match code.");
            statusLabel.setForeground(Color.RED);
            return;
        }

        setLoadingState(true);
        resultArea.setText("");

        executorService.submit(() -> {
            String responseBody = null;
            String serverMessage = null;
            String errorMessage = null;
            boolean success = false;

            try
            {
                JsonObject payload = new JsonObject();
                payload.addProperty("match_code", matchCode);

                RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, payload.toString());
                Request request = new Request.Builder()
                        .url(config.apiUrl() + DUEL_ARENA_PATH)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    responseBody = response.body() != null
                            ? response.body().string()
                            : "";

                    serverMessage = RuneAlyticsJson.extractMessage(responseBody);

                    if (response.isSuccessful())
                    {
                        success = true;
                    }
                    else
                    {
                        errorMessage = (serverMessage != null && !serverMessage.isEmpty())
                                ? serverMessage
                                : "HTTP " + response.code();
                    }
                }
            }
            catch (IOException ex)
            {
                errorMessage = ex.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalResponseBody = responseBody != null ? responseBody : "";
            final String finalServerMessage = serverMessage;
            final String finalErrorMessage = errorMessage;

            SwingUtilities.invokeLater(() -> {
                if (finalSuccess)
                {
                    String displayMessage =
                            (finalServerMessage != null && !finalServerMessage.isEmpty())
                                    ? finalServerMessage
                                    : "Match loaded.";

                    statusLabel.setText(displayMessage);
                    statusLabel.setForeground(Color.GREEN);

                    if (!finalResponseBody.isEmpty())
                    {
                        resultArea.setText(
                                "Message: " + (finalServerMessage != null ? finalServerMessage : "(none)") +
                                        "\n\nRaw response:\n" + finalResponseBody
                        );
                    }
                    else
                    {
                        resultArea.setText("Message: " + displayMessage);
                    }
                }
                else
                {
                    String displayMessage;
                    if (finalServerMessage != null && !finalServerMessage.isEmpty())
                    {
                        displayMessage = finalServerMessage;
                    }
                    else if (finalErrorMessage != null && !finalErrorMessage.isEmpty())
                    {
                        displayMessage = "Request failed. " + finalErrorMessage;
                    }
                    else
                    {
                        displayMessage = "Request failed. Please check the match code and try again.";
                    }

                    statusLabel.setText(displayMessage);
                    statusLabel.setForeground(Color.RED);

                    if (!finalResponseBody.isEmpty())
                    {
                        resultArea.setText(
                                "Error message: " + displayMessage +
                                        "\n\nRaw response:\n" + finalResponseBody
                        );
                    }
                    else
                    {
                        resultArea.setText("Error message: " + displayMessage);
                    }
                }

                setLoadingState(false);
            });
        });
    }

    private void setLoadingState(boolean loading)
    {
        submitButton.setEnabled(!loading);
        matchCodeField.setEnabled(!loading);
        if (loading)
        {
            statusLabel.setText("Loading match...");
            statusLabel.setForeground(ColorScheme.TEXT_COLOR);
        }
    }
}
