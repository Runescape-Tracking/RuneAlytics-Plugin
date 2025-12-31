package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;

@Singleton
public class RuneAlyticsVerificationPanel extends RuneAlyticsPanelBase
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String VERIFY_URL = "https://runealytics.com/api/verify-runelite";

    private final OkHttpClient httpClient;
    private final Client client;
    private final RuneAlyticsState runeAlyticsState;

    private final JTextField codeField = RuneAlyticsUi.inputField();
    private final JButton verifyButton = RuneAlyticsUi.primaryButton("Verify Account");
    private final JLabel statusLabel = RuneAlyticsUi.statusLabel();
    private final JTextArea apiResponseArea = RuneAlyticsUi.apiResponseArea();

    private boolean verifying = false;

    @Inject
    public RuneAlyticsVerificationPanel(
            OkHttpClient httpClient,
            Client client,
            RuneAlyticsState runeAlyticsState
    )
    {
        this.httpClient = httpClient;
        this.client = client;
        this.runeAlyticsState = runeAlyticsState;

        buildUi();
        wireEvents();

        statusLabel.setText("Enter the link code from RuneAlytics.com.");
        updateUi();
    }

    // ========= UI BUILD =========

    private void buildUi()
    {
        // Top title / subtitle use your base panel helpers
        addSectionTitle("RuneAlytics Account Linking");
        addSubtitle("Connect your RuneLite client with your RuneAlytics account.");

        // Instructions block
        JLabel instructions = RuneAlyticsUi.bodyLabel(
                "<html>" +
                        "1. Log into <b>RuneAlytics.com</b>.<br>" +
                        "2. Go to <b>Account → Link RuneLite</b>.<br>" +
                        "3. Paste your link / verification code below." +
                        "</html>"
        );
        add(instructions);
        add(RuneAlyticsUi.vSpace(8));

        // Input + button on one row
        JPanel formRow = RuneAlyticsUi.formRow(codeField, verifyButton);
        add(formRow);
        add(RuneAlyticsUi.vSpace(6));

        // Status line
        add(statusLabel);
        add(RuneAlyticsUi.vSpace(8));

        // API response card (new style)
        JPanel apiCard = RuneAlyticsUi.apiResponseCard("Server Response", apiResponseArea);
        add(apiCard);

        // Push content up / allow stretch
        add(Box.createVerticalGlue());
    }

    private void wireEvents()
    {
        verifyButton.addActionListener(e -> verifyAccount());
        codeField.addActionListener(e -> verifyAccount());
    }

    // ========= STATE / UI =========

    private boolean isLoggedIn()
    {
        return client.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;
    }

    public void refreshLoginState()
    {
        updateUi();
    }

    private void updateUi()
    {
        boolean loggedIn = isLoggedIn();

        if (!loggedIn)
        {
            setControlsEnabled(false);
            statusLabel.setText("You must be logged into RuneScape in RuneLite to link your account.");
            return;
        }

        boolean isVerified = runeAlyticsState.isVerified();

        // Once verified: hide inputs
        codeField.setVisible(!isVerified);
        verifyButton.setVisible(!isVerified);

        setControlsEnabled(!verifying && !isVerified);

        if (verifying)
        {
            statusLabel.setText("Verifying with RuneAlytics...");
        }
        else if (isVerified && !verifying)
        {
            // If you want a nicer post-verified message:
            statusLabel.setText("Your account has been successfully verified!");
        }
    }

    private void setControlsEnabled(boolean enabled)
    {
        codeField.setEnabled(enabled);
        verifyButton.setEnabled(enabled);
        // Keep response area readable even when disabled
        apiResponseArea.setEnabled(true);
    }

    // ========= VERIFY FLOW =========

    private void verifyAccount()
    {
        if (!isLoggedIn())
        {
            runeAlyticsState.setVerified(false);
            verifying = false;
            updateUi();
            return;
        }

        String code = codeField.getText().trim();
        if (code.isEmpty())
        {
            statusLabel.setText("Enter the code from RuneAlytics.com.");
            apiResponseArea.setText("");
            return;
        }

        verifying = true;
        updateUi();

        new Thread(() -> {
            boolean success = false;
            String serverMessage = null;
            String rawResponse = null;
            String errorMessage = null;

            try
            {
                String rsn = client.getLocalPlayer() != null
                        ? client.getLocalPlayer().getName()
                        : "";
                if (rsn == null) rsn = "";

                String jsonBody =
                        "{"
                                + "\"verification_code\":\"" + escapeJson(code) + "\","
                                + "\"osrs_rsn\":\"" + escapeJson(rsn) + "\""
                                + "}";

                RequestBody body = RequestBody.create(JSON, jsonBody);
                Request request = new Request.Builder()
                        .url(VERIFY_URL)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    rawResponse = response.body() != null
                            ? response.body().string()
                            : "";

                    serverMessage = !rawResponse.isEmpty()
                            ? extractMessageFromJson(rawResponse)
                            : null;

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
            final String finalServerMessage = serverMessage;
            final String finalErrorMessage = errorMessage;
            final String finalRawResponse = rawResponse;

            SwingUtilities.invokeLater(() -> {
                verifying = false;

                if (finalSuccess)
                {
                    runeAlyticsState.setVerified(true);

                    String displayMessage =
                            (finalServerMessage != null && !finalServerMessage.isEmpty())
                                    ? finalServerMessage
                                    : "Verification completed successfully.";

                    statusLabel.setText(displayMessage);

                    String safeRaw = finalRawResponse != null ? finalRawResponse : "";
                    if (!safeRaw.isEmpty())
                    {
                        apiResponseArea.setText(
                                "Message: " + (finalServerMessage != null ? finalServerMessage : "(none)") +
                                        "\n\nRaw response:\n" + safeRaw
                        );
                    }
                    else
                    {
                        apiResponseArea.setText("Message: " + displayMessage);
                    }
                }
                else
                {
                    runeAlyticsState.setVerified(false);

                    String displayMessage;
                    if (finalServerMessage != null && !finalServerMessage.isEmpty())
                    {
                        displayMessage = finalServerMessage;
                    }
                    else if (finalErrorMessage != null && !finalErrorMessage.isEmpty())
                    {
                        displayMessage = "Verification failed. " + finalErrorMessage;
                    }
                    else
                    {
                        displayMessage = "Verification failed. Check the code and try again.";
                    }

                    statusLabel.setText(displayMessage);

                    String safeRaw = finalRawResponse != null ? finalRawResponse : "";
                    if (!safeRaw.isEmpty())
                    {
                        apiResponseArea.setText(
                                "Error message: " + displayMessage +
                                        "\n\nRaw response:\n" + safeRaw
                        );
                    }
                    else
                    {
                        apiResponseArea.setText("Error message: " + displayMessage);
                    }
                }

                updateUi();
            });
        }).start();
    }

    // Same simple helper as before – you can move this to a RuneAlyticsApi helper if you want
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

    private String escapeJson(String value)
    {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
