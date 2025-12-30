package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;

@Singleton
public class RuneAlyticsVerificationPanel extends JPanel
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String VERIFY_URL = "https://runealytics.com/api/link_client";

    private final OkHttpClient httpClient;
    private final Client client;

    private final JTextField codeField = new JTextField();
    private final JButton verifyButton = new JButton("Verify account");
    private final JLabel statusLabel = new JLabel(" ");

    private boolean verified = false;
    private boolean verifying = false;

    @Inject
    public RuneAlyticsVerificationPanel(OkHttpClient httpClient, Client client)
    {
        this.httpClient = httpClient;
        this.client = client;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        buildUi();
        wireEvents();

        // Initial state (handles "already logged in" case too)
        updateUi();
    }

    // ========= UI BUILD =========

    private void buildUi()
    {
        JLabel title = new JLabel("RuneAlytics Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(ColorScheme.TEXT_COLOR);
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel instructions = new JLabel(
                "<html>" +
                        "1. Log into <b>RuneAlytics.com</b>.<br>" +
                        "2. Go to <b>Account → Link RuneLite</b>.<br>" +
                        "3. Paste your link / verification code below." +
                        "</html>"
        );
        instructions.setForeground(ColorScheme.TEXT_COLOR);
        instructions.setAlignmentX(LEFT_ALIGNMENT);

        codeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, codeField.getPreferredSize().height));
        codeField.setAlignmentX(LEFT_ALIGNMENT);
        codeField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        codeField.setForeground(ColorScheme.TEXT_COLOR);
        codeField.setCaretColor(ColorScheme.TEXT_COLOR);
        codeField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter(), 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
        ));

        verifyButton.setAlignmentX(LEFT_ALIGNMENT);

        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setForeground(ColorScheme.TEXT_COLOR);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));

        add(title);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(instructions);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(codeField);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(verifyButton);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(statusLabel);
    }

    private void wireEvents()
    {
        verifyButton.addActionListener(e -> verifyAccount());
    }

    // ========= STATE / UI =========

    private boolean isLoggedIn()
    {
        return client.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;
    }

    /** Called from outside whenever login state might have changed */
    public void refreshLoginState()
    {
        updateUi();
    }

    private void updateUi()
    {
        boolean loggedIn = isLoggedIn();

        if (!loggedIn)
        {
            codeField.setEnabled(false);
            verifyButton.setEnabled(false);
            statusLabel.setText("You must be logged into RuneScape in RuneLite to link your account.");
            return;
        }

        if (verified)
        {
            codeField.setEnabled(false);
            verifyButton.setEnabled(false);

            final String rsn = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : "your account";

            statusLabel.setText("Account already verified for: " + rsn + ".");
            return;
        }

        codeField.setEnabled(!verifying);
        verifyButton.setEnabled(!verifying);

        if (verifying)
        {
            statusLabel.setText("Verifying...");
        }
        else
        {
            statusLabel.setText("Enter the link code from RuneAlytics.com.");
        }
    }

    // ========= VERIFY FLOW =========

    private void verifyAccount()
    {
        if (!isLoggedIn())
        {
            verified = false;
            verifying = false;
            updateUi();
            return;
        }

        String code = codeField.getText().trim();
        if (code.isEmpty())
        {
            statusLabel.setText("Enter the link code from the website.");
            return;
        }

        verifying = true;
        updateUi();

        new Thread(() -> {
            boolean success = false;
            String serverMessage = null;
            String errorMessage = null;

            try
            {
                String rsn = client.getLocalPlayer() != null
                        ? client.getLocalPlayer().getName()
                        : "";

                String jsonBody =
                        "{"
                                + "\"code\":\"" + escapeJson(code) + "\","
                                + "\"rsn\":\"" + escapeJson(rsn) + "\""
                                + "}";

                RequestBody body = RequestBody.create(JSON, jsonBody);
                Request request = new Request.Builder()
                        .url(VERIFY_URL)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    String responseBody = response.body() != null
                            ? response.body().string()
                            : "";

                    if (!response.isSuccessful())
                    {
                        errorMessage = "HTTP " + response.code();
                    }
                    else
                    {
                        serverMessage = extractMessageFromJson(responseBody);
                        success = true;
                        // TODO: persist via ConfigManager if you want
                    }
                }
            }
            catch (IOException ex)
            {
                errorMessage = ex.getMessage();
            }

            boolean finalSuccess = success;
            String finalServerMessage = serverMessage;
            String finalErrorMessage = errorMessage;

            SwingUtilities.invokeLater(() -> {
                verifying = false;
                if (finalSuccess)
                {
                    verified = true;
                    statusLabel.setText(
                            finalServerMessage != null
                                    ? finalServerMessage
                                    : "Account verified! You can now use RuneAlytics features."
                    );
                }
                else
                {
                    verified = false;
                    statusLabel.setText(
                            "Verification failed. " +
                                    (finalErrorMessage != null ? finalErrorMessage : "Check the code and try again.")
                    );
                }

                updateUi();
            });
        }).start();
    }

    private String extractMessageFromJson(String json)
    {
        String key = "\"message\"";
        int idx = json.indexOf(key);
        if (idx == -1)
        {
            return null;
        }

        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx == -1)
        {
            return null;
        }

        int firstQuote = json.indexOf('"', colonIdx + 1);
        if (firstQuote == -1)
        {
            return null;
        }

        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote == -1)
        {
            return null;
        }

        return json.substring(firstQuote + 1, secondQuote);
    }

    private String escapeJson(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
