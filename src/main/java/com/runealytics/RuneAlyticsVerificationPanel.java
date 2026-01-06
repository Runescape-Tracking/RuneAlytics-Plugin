package com.runealytics;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

@Singleton
public class RuneAlyticsVerificationPanel extends RuneAlyticsPanelBase
{
    private static final String VERIFY_PATH = "/verify-runelite";

    private final OkHttpClient httpClient;
    private final Client client;
    private final RuneAlyticsState runeAlyticsState;
    private final RunealyticsConfig config;
    private final ScheduledExecutorService executorService;

    private final JTextField codeField = RuneAlyticsUi.inputField();
    private final JButton verifyButton = RuneAlyticsUi.primaryButton("Verify Account");
    private final JLabel statusLabel = RuneAlyticsUi.statusLabel();
    private final JTextArea apiResponseArea = RuneAlyticsUi.apiResponseArea();

    private boolean verifying = false;

    @Inject
    public RuneAlyticsVerificationPanel(
            OkHttpClient httpClient,
            Client client,
            RuneAlyticsState runeAlyticsState,
            RunealyticsConfig config,
            ScheduledExecutorService executorService
    )
    {
        this.httpClient = httpClient;
        this.client = client;
        this.runeAlyticsState = runeAlyticsState;
        this.config = config;
        this.executorService = executorService;

        buildUi();
        wireEvents();

        statusLabel.setText("Enter the link code from RuneAlytics.com.");
        updateUi();
    }

    private void buildUi()
    {
        addSectionTitle("RuneAlytics Account Linking");
        addSubtitle("Connect your RuneLite client with your RuneAlytics account.");

        JLabel instructions = RuneAlyticsUi.bodyLabel(
                "<html>" +
                        "1. Log into <b>RuneAlytics.com</b>.<br>" +
                        "2. Go to <b>Account → Link RuneLite</b>.<br>" +
                        "3. Paste your link / verification code below." +
                        "</html>"
        );
        add(instructions);
        add(RuneAlyticsUi.vSpace(8));

        JPanel formRow = RuneAlyticsUi.formRow(codeField, verifyButton);
        add(formRow);
        add(RuneAlyticsUi.vSpace(6));

        add(statusLabel);
        add(RuneAlyticsUi.vSpace(8));

        JPanel apiCard = RuneAlyticsUi.apiResponseCard("Server Response", apiResponseArea);
        add(apiCard);

        add(Box.createVerticalGlue());
    }

    private void wireEvents()
    {
        verifyButton.addActionListener(e -> verifyAccount());
        codeField.addActionListener(e -> verifyAccount());
    }

    public void refreshLoginState()
    {
        updateUi();
    }

    private void updateUi()
    {
        final boolean loggedIn = runeAlyticsState.isLoggedIn();
        final boolean isVerified = runeAlyticsState.isVerified();

        // Always show the controls; just toggle enabled/disabled state
        codeField.setVisible(true);
        verifyButton.setVisible(true);

        if (!loggedIn)
        {
            // Not logged in → disabled
            setControlsEnabled(false);
            statusLabel.setText("You must be logged into RuneScape in RuneLite to link your account.");
            return;
        }

        if (verifying)
        {
            // In-flight request → disabled
            setControlsEnabled(false);
            statusLabel.setText("Verifying with RuneAlytics...");
            return;
        }

        if (isVerified)
        {
            // Verified → visible but disabled
            setControlsEnabled(false);
            statusLabel.setText("Your account has been successfully verified!");
            return;
        }

        // Logged in, not verified, not currently verifying → enable controls
        setControlsEnabled(true);
        statusLabel.setText("Enter the link code from RuneAlytics.com.");
    }

    private void setControlsEnabled(boolean enabled)
    {
        codeField.setEnabled(enabled);
        verifyButton.setEnabled(enabled);
        // Response area stays readable even when "disabled"
        apiResponseArea.setEnabled(true);
    }

    private void verifyAccount()
    {
        // If already verified, do nothing
        if (runeAlyticsState.isVerified())
        {
            statusLabel.setText("Your account is already verified.");
            return;
        }

        if (!runeAlyticsState.isLoggedIn()
                || client.getGameState() != GameState.LOGGED_IN
                || client.getLocalPlayer() == null)
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

        executorService.submit(() -> {
            boolean success = false;
            String serverMessage = null;
            String rawResponse = null;
            String errorMessage = null;

            String rsn = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : "";
            if (rsn == null)
            {
                rsn = "";
            }

            try
            {
                JsonObject payload = new JsonObject();
                payload.addProperty("verification_code", code);
                payload.addProperty("osrs_rsn", rsn);

                RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, payload.toString());
                Request request = new Request.Builder()
                        .url(config.apiUrl() + VERIFY_PATH)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    rawResponse = response.body() != null
                            ? response.body().string()
                            : "";

                    serverMessage = RuneAlyticsJson.extractMessage(rawResponse);

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
            final String finalRawResponse = rawResponse != null ? rawResponse : "";
            final String finalRsn = rsn;

            SwingUtilities.invokeLater(() -> {
                verifying = false;

                if (finalSuccess)
                {
                    runeAlyticsState.setVerified(true);
                    runeAlyticsState.setVerifiedUsername(finalRsn);

                    String verificationCode = RuneAlyticsJson.extractStringField(finalRawResponse, "verification_code");
                    runeAlyticsState.setVerificationCode(verificationCode);

                    if (verificationCode != null && !verificationCode.isEmpty())
                    {
                        config.authToken(verificationCode);
                    }

                    String displayMessage =
                            (finalServerMessage != null && !finalServerMessage.isEmpty())
                                    ? finalServerMessage
                                    : "Verification completed successfully.";

                    statusLabel.setText(displayMessage);

                    if (!finalRawResponse.isEmpty())
                    {
                        apiResponseArea.setText(
                                "Message: " + (finalServerMessage != null ? finalServerMessage : "(none)") +
                                        "\n\nRaw response:\n" + finalRawResponse
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
                    runeAlyticsState.setVerifiedUsername(null);
                    runeAlyticsState.setVerificationCode(null);
                    config.authToken("");

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

                    if (!finalRawResponse.isEmpty())
                    {
                        apiResponseArea.setText(
                                "Error message: " + displayMessage +
                                        "\n\nRaw response:\n" + finalRawResponse
                        );
                    }
                    else
                    {
                        apiResponseArea.setText("Error message: " + displayMessage);
                    }
                }

                updateUi();
            });
        });
    }
}
