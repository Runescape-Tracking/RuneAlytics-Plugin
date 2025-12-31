package com.runealytics;

import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;

@Singleton
public class DuelArenaMatchPanel extends JPanel
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String API_URL = "https://runealytics.com/api/duel_arena";

    private final OkHttpClient httpClient;

    // Standardized UI components
    private final JTextField matchCodeField = RuneAlyticsUi.inputField();
    private final JButton submitButton = RuneAlyticsUi.primaryButton("Load match");
    private final JLabel statusLabel = RuneAlyticsUi.statusLabel();
    private final JTextArea resultArea = RuneAlyticsUi.apiResponseArea();

    // Panels we’ll swap in/out
    private final JPanel headerPanel;
    private final JPanel contentPanel;
    private final JLabel loginMessage;

    private boolean loggedIn = false;

    @Inject
    public DuelArenaMatchPanel(OkHttpClient httpClient, Client client)
    {
        this.httpClient = httpClient;

        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        // Build once, reuse
        this.headerPanel = buildHeader();
        this.contentPanel = buildContent();

        this.loginMessage = new JLabel("Please log in.", SwingConstants.CENTER);
        loginMessage.setForeground(ColorScheme.TEXT_COLOR);
        loginMessage.setFont(loginMessage.getFont().deriveFont(Font.BOLD, 14f));

        // Start in logged-out view; plugin will call setLoggedIn(...) later
        showLoggedOut();

        submitButton.addActionListener(e -> submitMatchCode());
    }

    // Called from plugin: duelArenaMatchPanel.setLoggedIn(isLoggedIn);
    public void setLoggedIn(boolean loggedIn)
    {
        this.loggedIn = loggedIn;

        if (loggedIn)
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

    // --- UI building ---

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

        // --- Form "card"
        JPanel formCard = RuneAlyticsUi.cardPanel();

        JLabel label = RuneAlyticsUi.bodyLabel("Match Code:");
        label.setAlignmentX(LEFT_ALIGNMENT);

        // Ensure field stretches horizontally
        matchCodeField.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, matchCodeField.getPreferredSize().height)
        );

        // Button row centered
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

        // --- API Response card using shared UI
        JPanel apiCard = RuneAlyticsUi.apiResponseCard("API Response", resultArea);

        container.add(formCard);
        container.add(RuneAlyticsUi.vSpace(8));
        container.add(apiCard);

        return container;
    }

    // --- Logic ---

    private void submitMatchCode()
    {
        // Safety guard: if somehow called while logged out, bail
        if (!loggedIn)
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

        new Thread(() -> {
            String responseBody = null;
            String serverMessage = null;
            String errorMessage = null;
            boolean success = false;

            try
            {
                String jsonBody = "{\"match_code\":\"" + escapeJson(matchCode) + "\"}";

                RequestBody body = RequestBody.create(JSON, jsonBody);
                Request request = new Request.Builder()
                        .url(API_URL)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    responseBody = response.body() != null
                            ? response.body().string()
                            : "";

                    serverMessage = extractMessageFromJson(responseBody);

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
            final String finalResponseBody = responseBody;
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

                    if (finalResponseBody != null && !finalResponseBody.isEmpty())
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
        }).start();
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

    private String escapeJson(String value)
    {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Same simple JSON "message" extractor you used in the verification panel.
     * This keeps status text aligned to your backend response.
     */
    private String extractMessageFromJson(String json)
    {
        if (json == null || json.isEmpty())
        {
            return null;
        }

        String key = "\"message\"";
        int idx = json.indexOf(key);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx == -1) return null;

        int firstQuote = json.indexOf('"', colonIdx + 1);
        if (firstQuote == -1) return null;

        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote == -1) return null;

        return json.substring(firstQuote + 1, secondQuote);
    }
}
