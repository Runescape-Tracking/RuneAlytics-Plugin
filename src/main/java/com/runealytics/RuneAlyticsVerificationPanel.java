package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
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
public class RuneAlyticsVerificationPanel extends JPanel
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String VERIFY_URL = "https://runealytics.com/api/verify-runelite";

    private final OkHttpClient httpClient;
    private final Client client;
    private final RuneAlyticsState runeAlyticsState;

    private final JTextField codeField = new JTextField();
    private final JButton verifyButton = new JButton("Verify Account");
    private final JLabel statusLabel = new JLabel(" ");

    private final JTextArea apiResponseArea = new JTextArea(5, 30);

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

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        buildUi();
        wireEvents();

        statusLabel.setText("Enter the link code from RuneAlytics.com.");
        updateUi();
    }

    // ========= UI BUILD =========

    private void buildUi()
    {
        // Header
        JLabel title = new JLabel("RuneAlytics Account Linking");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(ColorScheme.TEXT_COLOR);
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Connect your RuneLite client with your RuneAlytics account.");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 11f));
        subtitle.setForeground(ColorScheme.TEXT_COLOR);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);

        add(title);
        add(Box.createRigidArea(new Dimension(0, 2)));
        add(subtitle);
        add(Box.createRigidArea(new Dimension(0, 8)));

        // Instructions
        JLabel instructions = new JLabel(
                "<html>" +
                        "1. Log into <b>RuneAlytics.com</b>.<br>" +
                        "2. Go to <b>Account → Link RuneLite</b>.<br>" +
                        "3. Paste your link / verification code below." +
                        "</html>"
        );
        instructions.setForeground(ColorScheme.TEXT_COLOR);
        instructions.setAlignmentX(LEFT_ALIGNMENT);

        add(instructions);
        add(Box.createRigidArea(new Dimension(0, 8)));

        // Code field + button in a form panel
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.X_AXIS));
        formPanel.setOpaque(false);

        codeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, codeField.getPreferredSize().height));
        codeField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        codeField.setForeground(ColorScheme.TEXT_COLOR);
        codeField.setCaretColor(ColorScheme.TEXT_COLOR);
        codeField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter(), 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
        ));

        verifyButton.setMargin(new Insets(2, 8, 2, 8));

        formPanel.add(codeField);
        formPanel.add(Box.createRigidArea(new Dimension(6, 0)));
        formPanel.add(verifyButton);

        formPanel.setAlignmentX(LEFT_ALIGNMENT);
        add(formPanel);
        add(Box.createRigidArea(new Dimension(0, 6)));

        // Status label
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setForeground(ColorScheme.TEXT_COLOR);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));

        add(statusLabel);
        add(Box.createRigidArea(new Dimension(0, 8)));

        // API response area (for debugging / transparency)
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
                        "Server Response",
                        0, 0,
                        statusLabel.getFont().deriveFont(Font.PLAIN, 11f),
                        ColorScheme.TEXT_COLOR
                )
        );

        add(apiScrollPane);
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

    /** Called externally when login state changes. */
    public void refreshLoginState()
    {
        updateUi();
    }

    private void updateUi()
    {
        boolean loggedIn = isLoggedIn();

        if (!loggedIn)
        {
            setVerificationControlsEnabled(false);
            statusLabel.setText("You must be logged into RuneScape in RuneLite to link your account.");
            return;
        }

        boolean isVerified = runeAlyticsState.isVerified();

        // Hide form once verified, but keep the last server message visible
        codeField.setVisible(!isVerified);
        verifyButton.setVisible(!isVerified);

        setVerificationControlsEnabled(!verifying && !isVerified);

        // Keep statusLabel text as-is unless we’re actively verifying
        if (verifying)
        {
            statusLabel.setText("Verifying with RuneAlytics...");
        }
    }

    private void setVerificationControlsEnabled(boolean enabled)
    {
        codeField.setEnabled(enabled);
        verifyButton.setEnabled(enabled);
        apiResponseArea.setEnabled(true); // always readable
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

                if (rsn == null)
                {
                    rsn = "";
                }

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
                        // Success is based on HTTP 2xx — message comes from server
                        success = true;
                    }
                    else
                    {
                        // Prefer server message even on error
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

                    // Always prefer the message from the JSON response
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

                    // Again, always prefer the server's 'message' if present
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

    /**
     * Very simple "message" extractor: looks for `"message":"..."`
     * and returns the inner text. If not found or malformed, returns null.
     */
    private String extractMessageFromJson(String json)
    {
        if (json == null || json.isEmpty())
        {
            return null;
        }

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
        if (value == null)
        {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}