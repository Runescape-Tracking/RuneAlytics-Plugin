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

    private final JTextField matchCodeField = new JTextField();
    private final JButton submitButton = new JButton("Load match");
    private final JLabel statusLabel = new JLabel("");
    private final JTextArea resultArea = new JTextArea();

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

        styleMatchCodeField();

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

    private void styleMatchCodeField()
    {
        matchCodeField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        matchCodeField.setForeground(ColorScheme.TEXT_COLOR);
        matchCodeField.setCaretColor(ColorScheme.TEXT_COLOR);
        matchCodeField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter(), 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
        ));
        matchCodeField.setOpaque(true);
    }

    private JPanel buildHeader()
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        header.setOpaque(false);

        JLabel title = new JLabel("Duel Arena");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(ColorScheme.TEXT_COLOR);

        JLabel subtitle = new JLabel("RuneAlytics Match Linker");
        subtitle.setForeground(ColorScheme.TEXT_COLOR);

        header.add(title);
        header.add(subtitle);

        return header;
    }

    private JPanel buildContent()
    {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setOpaque(false);

        // --- Form "card"
        JPanel formCard = new JPanel();
        formCard.setLayout(new BoxLayout(formCard, BoxLayout.Y_AXIS));
        formCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        formCard.setOpaque(true);
        formCard.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        formCard.setAlignmentX(LEFT_ALIGNMENT);

        // Label
        JLabel label = new JLabel("Match Code:");
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setForeground(ColorScheme.TEXT_COLOR);

        // Input Box
        matchCodeField.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, matchCodeField.getPreferredSize().height)
        );
        matchCodeField.setAlignmentX(LEFT_ALIGNMENT);

        // Button Row - Centered
        JPanel buttonRow = new JPanel();
        buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS));
        buttonRow.setOpaque(false);
        buttonRow.setAlignmentX(LEFT_ALIGNMENT);
        buttonRow.add(Box.createHorizontalGlue());
        buttonRow.add(submitButton);
        buttonRow.add(Box.createHorizontalGlue());

        // Status label under button
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setForeground(ColorScheme.TEXT_COLOR);

        formCard.add(label);
        formCard.add(Box.createRigidArea(new Dimension(0, 4)));
        formCard.add(matchCodeField);
        formCard.add(Box.createRigidArea(new Dimension(0, 6)));
        formCard.add(buttonRow);
        formCard.add(Box.createRigidArea(new Dimension(0, 6)));
        formCard.add(statusLabel);

        // --- API Response
        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        resultArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        resultArea.setForeground(ColorScheme.TEXT_COLOR);
        resultArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JScrollPane scroll = new JScrollPane(resultArea);
        scroll.setBorder(BorderFactory.createTitledBorder("API response"));
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.setOpaque(false);
        scroll.setPreferredSize(new Dimension(100, 200));
        scroll.setAlignmentX(LEFT_ALIGNMENT);

        container.add(formCard);
        container.add(Box.createRigidArea(new Dimension(0, 8)));
        container.add(scroll);

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
                    final String responseBody = response.body() != null
                            ? response.body().string()
                            : "";

                    SwingUtilities.invokeLater(() -> {
                        if (!response.isSuccessful())
                        {
                            statusLabel.setText("Error: HTTP " + response.code());
                            statusLabel.setForeground(Color.RED);
                        }
                        else
                        {
                            statusLabel.setText("Match loaded.");
                            statusLabel.setForeground(Color.GREEN);
                        }
                        resultArea.setText(responseBody);
                        setLoadingState(false);
                    });
                }
            }
            catch (IOException ex)
            {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    resultArea.setText("");
                    setLoadingState(false);
                });
            }
        }).start();
    }

    private void setLoadingState(boolean loading)
    {
        submitButton.setEnabled(!loading);
        matchCodeField.setEnabled(!loading);
        if (loading)
        {
            statusLabel.setText("Loading match...");
        }
    }

    private String escapeJson(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}