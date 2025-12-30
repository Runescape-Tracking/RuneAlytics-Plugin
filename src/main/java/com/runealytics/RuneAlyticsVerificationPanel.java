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
    private static final String VERIFY_URL = "https://runealytics.com/api/verify-runelite";

    private final OkHttpClient httpClient;
    private final Client client;

    private final JTextField codeField = new JTextField();
    private final JButton verifyButton = new JButton("Verify Account");
    private final JLabel statusLabel = new JLabel(" ");

    // NEW: API Response area
    private final JTextArea apiResponseArea = new JTextArea(5, 30);

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
        statusLabel.setText("Enter the link code from RuneAlytics.com.");
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

        // NEW: API response area setup
        apiResponseArea.setEditable(false);
        apiResponseArea.setLineWrap(true);
        apiResponseArea.setWrapStyleWord(true);
        apiResponseArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        apiResponseArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        apiResponseArea.setForeground(ColorScheme.TEXT_COLOR);
        apiResponseArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JScrollPane apiScrollPane = new JScrollPane(apiResponseArea);
        apiScrollPane.setAlignmentX(LEFT_ALIGNMENT);
        apiScrollPane.setBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter(), 1),
                        "API Response",
                        0, 0,
                        statusLabel.getFont().deriveFont(Font.PLAIN, 11f),
                        ColorScheme.TEXT_COLOR
                )
        );

        add(title);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(instructions);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(codeField);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(verifyButton);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(statusLabel);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(apiScrollPane); // NEW
    }

    private void wireEvents()
    {
        verifyButton.addActionListener(e -> verifyAccount());

        // NEW: press Enter in the code field to verify
        codeField.addActionListener(e -> verifyAccount());
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

        // Logged in and not yet verified
        codeField.setEnabled(!verifying);
        verifyButton.setEnabled(!verifying);

        if (verifying)
        {
            statusLabel.setText("Verifying...");
        }
        // IMPORTANT: if not verifying, do NOT override statusLabel here.
        // This lets our success/failure message from verifyAccount() stick.
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
            statusLabel.setText("Enter the code from RuneAlytics.com.");
            apiResponseArea.setText(""); // clear any old API response
            return;
        }

        verifying = true;
        updateUi();

        new Thread(() -> {
            boolean success = false;
            String serverMessage = null;
            String errorMessage = null;
            String rawResponse = null;

            try
            {
                String rsn = client.getLocalPlayer() != null
                        ? client.getLocalPlayer().getName()
                        : "";

                assert rsn != null;
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

                    // Try to read "message" from JSON on *all* responses
                    serverMessage = !rawResponse.isEmpty()
                            ? extractMessageFromJson(rawResponse)
                            : null;

                    if (response.isSuccessful())
                    {
                        success = true;
                    }
                    else
                    {
                        // Prefer server "message" for errors; fall back to HTTP code
                        errorMessage = serverMessage != null && !serverMessage.isEmpty()
                                ? serverMessage
                                : "HTTP " + response.code();
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
            String finalRawResponse = rawResponse;

            SwingUtilities.invokeLater(() -> {
                verifying = false;

                if (finalSuccess)
                {
                    verified = true;

                    // Prefer API "message" on success
                    statusLabel.setText(
                            finalServerMessage != null && !finalServerMessage.isEmpty()
                                    ? finalServerMessage
                                    : "Account verified! You can now use RuneAlytics features."
                    );

                    if (!finalRawResponse.isEmpty())
                    {
                        apiResponseArea.setText(
                                "Message: " + (finalServerMessage != null ? finalServerMessage : "(none)") +
                                        "\n\nRaw response:\n" + finalRawResponse
                        );
                    }
                    else
                    {
                        apiResponseArea.setText(
                                "Message: " + "(none)"
                        );
                    }
                }
                else
                {
                    verified = false;

                    // Prefer API "message" on failure too
                    String labelText;
                    if (finalServerMessage != null && !finalServerMessage.isEmpty())
                    {
                        labelText = finalServerMessage;
                    }
                    else if (finalErrorMessage != null && !finalErrorMessage.isEmpty())
                    {
                        labelText = "Verification failed. " + finalErrorMessage;
                    }
                    else
                    {
                        labelText = "Verification failed. Check the code and try again.";
                    }

                    statusLabel.setText(labelText);

                    if (finalRawResponse != null && !finalRawResponse.isEmpty())
                    {
                        apiResponseArea.setText(
                                "Error: " + finalErrorMessage +
                                        "\n\nRaw response:\n" + finalRawResponse
                        );
                    }
                    else
                    {
                        apiResponseArea.setText(
                                "Error: " + (finalErrorMessage != null ? finalErrorMessage : "(unknown)")
                        );
                    }
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
