package com.runealytics;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
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
    private static final String VERIFY_PATH  = "/verify-runelite";
    private static final String CONFIG_GROUP = "runealytics";

    private final OkHttpClient             httpClient;
    private final Client                   client;
    private final RuneAlyticsState         runeAlyticsState;
    private final RunealyticsConfig        config;
    private final ScheduledExecutorService executorService;
    private final ConfigManager            configManager;

    private final JTextField codeField       = RuneAlyticsUi.inputField();
    private final JButton    verifyButton    = RuneAlyticsUi.primaryButton("Verify Account");
    private final JPanel     formRow         = RuneAlyticsUi.formRow(codeField, verifyButton);
    private final JLabel     statusLabel     = RuneAlyticsUi.statusLabel();
    private final JTextArea  apiResponseArea = RuneAlyticsUi.apiResponseArea();

    private boolean  verifying = false;
    private Runnable verificationStatusListener;

    @Inject
    public RuneAlyticsVerificationPanel(
            OkHttpClient             httpClient,
            Client                   client,
            RuneAlyticsState         runeAlyticsState,
            RunealyticsConfig        config,
            ScheduledExecutorService executorService,
            ConfigManager            configManager)
    {
        this.httpClient       = httpClient;
        this.client           = client;
        this.runeAlyticsState = runeAlyticsState;
        this.config           = config;
        this.executorService  = executorService;
        this.configManager    = configManager;

        buildUi();
        wireEvents();
        statusLabel.setText("Enter the link code from RuneAlytics.com.");
        updateUi();
    }

    // ── Per-account token storage (keyed by lowercase RSN) ──────────────────

    /** Saves an auth token for a specific account. */
    public void saveAccountToken(String rsn, String token)
    {
        configManager.setConfiguration(CONFIG_GROUP, accountKey(rsn), token);
    }

    /** Returns the stored token for a specific account, or null if none. */
    public String loadAccountToken(String rsn)
    {
        String token = configManager.getConfiguration(CONFIG_GROUP, accountKey(rsn));
        return (token == null || token.isEmpty()) ? null : token;
    }

    /** Removes the stored token for a specific account. */
    public void clearAccountToken(String rsn)
    {
        configManager.setConfiguration(CONFIG_GROUP, accountKey(rsn), "");
    }

    private static String accountKey(String rsn)
    {
        return "token_" + rsn.toLowerCase().replace(' ', '_');
    }

    // ── UI ───────────────────────────────────────────────────────────────────

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
        add(formRow);
        add(RuneAlyticsUi.vSpace(6));
        add(statusLabel);
        add(RuneAlyticsUi.vSpace(8));
        add(RuneAlyticsUi.apiResponseCard("Server Response", apiResponseArea));
        add(Box.createVerticalGlue());
    }

    private void wireEvents()
    {
        verifyButton.addActionListener(e -> verifyAccount());
        codeField.addActionListener(e -> verifyAccount());
    }

    public void refreshLoginState()
    {
        SwingUtilities.invokeLater(this::updateUi);
    }

    public void setVerificationStatusListener(Runnable listener)
    {
        this.verificationStatusListener = listener;
    }

    private void updateUi()
    {
        if (!runeAlyticsState.isLoggedIn())
        {
            setControlsEnabled(false);
            statusLabel.setText("Log into RuneScape first to link your account.");
            return;
        }

        if (verifying)
        {
            setControlsEnabled(false);
            statusLabel.setText("Verifying with RuneAlytics...");
            return;
        }

        // Button is ALWAYS enabled when logged in — users can re-link at any time
        setControlsEnabled(true);

        if (runeAlyticsState.isVerified())
        {
            String rsn = runeAlyticsState.getVerifiedUsername();
            statusLabel.setText("Linked" + (rsn != null ? " as " + rsn : "")
                    + ". Enter a new code to re-link.");
        }
        else
        {
            statusLabel.setText("Enter the link code from RuneAlytics.com.");
        }
    }

    private void setControlsEnabled(boolean enabled)
    {
        codeField.setEnabled(enabled);
        verifyButton.setEnabled(enabled);
        apiResponseArea.setEnabled(true);
    }

    // ── Verification request ─────────────────────────────────────────────────

    private void verifyAccount()
    {
        if (!runeAlyticsState.isLoggedIn()
                || client.getGameState() != GameState.LOGGED_IN
                || client.getLocalPlayer() == null)
        {
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

        final String rsn = client.getLocalPlayer().getName() != null
                ? client.getLocalPlayer().getName() : "";

        executorService.submit(() -> {
            boolean success      = false;
            String  serverMessage = null;
            String  rawResponse   = null;
            String  errorMessage  = null;

            try
            {
                JsonObject payload = new JsonObject();
                payload.addProperty("verification_code", code);
                payload.addProperty("osrs_rsn", rsn);

                RequestBody body    = RequestBody.create(RuneAlyticsHttp.JSON, payload.toString());
                Request     request = new Request.Builder()
                        .url(config.apiUrl() + VERIFY_PATH)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    rawResponse   = response.body() != null ? response.body().string() : "";
                    serverMessage = RuneAlyticsJson.extractMessage(rawResponse);

                    // Only HTTP status matters — 2xx = success
                    success = response.isSuccessful();
                    if (!success)
                        errorMessage = (serverMessage != null && !serverMessage.isEmpty())
                                ? serverMessage : "HTTP " + response.code();
                }
            }
            catch (IOException ex)
            {
                errorMessage = ex.getMessage();
            }

            final boolean finalSuccess       = success;
            final String  finalServerMessage = serverMessage;
            final String  finalErrorMessage  = errorMessage;
            final String  finalRawResponse   = rawResponse != null ? rawResponse : "";

            SwingUtilities.invokeLater(() -> {
                verifying = false;

                if (finalSuccess)
                {
                    // Store token keyed to this RSN — completely separate from every other account
                    saveAccountToken(rsn, code);

                    runeAlyticsState.setVerified(true);
                    runeAlyticsState.setVerifiedUsername(rsn);
                    runeAlyticsState.setVerificationCode(code);

                    String msg = (finalServerMessage != null && !finalServerMessage.isEmpty())
                            ? finalServerMessage : "Verification completed successfully.";
                    statusLabel.setText(msg);
                    apiResponseArea.setText(finalRawResponse.isEmpty() ? msg
                            : "Message: " + msg + "\n\nRaw response:\n" + finalRawResponse);
                }
                else
                {
                    // Clear any stored token for this account on failure
                    if (!rsn.isEmpty()) clearAccountToken(rsn);

                    runeAlyticsState.setVerified(false);
                    runeAlyticsState.setVerifiedUsername(null);
                    runeAlyticsState.setVerificationCode(null);

                    String msg;
                    if (finalServerMessage != null && !finalServerMessage.isEmpty())
                        msg = finalServerMessage;
                    else if (finalErrorMessage != null && !finalErrorMessage.isEmpty())
                        msg = "Verification failed. " + finalErrorMessage;
                    else
                        msg = "Verification failed. Check the code and try again.";

                    statusLabel.setText(msg);
                    apiResponseArea.setText(finalRawResponse.isEmpty() ? msg
                            : "Error: " + msg + "\n\nRaw response:\n" + finalRawResponse);
                }

                notifyVerificationStatusChange();
                updateUi();
            });
        });
    }

    private void notifyVerificationStatusChange()
    {
        if (verificationStatusListener != null)
            verificationStatusListener.run();
    }

    @Override
    public void onDataRefresh()
    {
        SwingUtilities.invokeLater(this::updateUi);
    }
}
