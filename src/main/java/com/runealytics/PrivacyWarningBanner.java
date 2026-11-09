package com.runealytics;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Sidebar box above the RuneAlytics tabs for {@link PrivacyWarningManager#banner()}:
 * <ul>
 *   <li><b>Public</b>: "Your online status is public" — <b>Hide</b> (saves Private),
 *       <b>Dismiss</b> (this session), <b>Remember dismissal</b> (this account, for good;
 *       the top-right eye keeps the state visible instead).</li>
 *   <li><b>Wilderness</b>: "Account visibility" — Hide for this trip / Make private.</li>
 *   <li><b>Hidden for this Wilderness trip</b> — Restore public.</li>
 *   <li><b>Applying…</b> while the website update is pending, <b>Hidden</b> once it
 *       confirmed, or a failure with Retry.</li>
 * </ul>
 * EDT only.
 */
public class PrivacyWarningBanner extends JPanel
{
    private static final Color BG = new Color(48, 18, 18);
    private static final Color BG_HIDDEN = new Color(34, 36, 40);
    private static final Color BORDER = new Color(200, 60, 60);
    private static final Color BORDER_HIDDEN = new Color(100, 104, 112);
    private static final Color AMBER = PrivacyEyeIcon.AMBER;
    private static final Color TEXT = new Color(235, 225, 225);
    private static final Color LINK = new Color(255, 200, 110);
    private static final Color GREEN = new Color(36, 110, 58);
    private static final Color GREEN_BORDER = new Color(80, 170, 100);
    private static final Color NEUTRAL = new Color(40, 44, 60);
    private static final Color NEUTRAL_BORDER = new Color(90, 96, 120);
    /** CSS wrap widths (Swing HTML draws CSS px about 4/3 larger) so the box fits the 225 px sidebar. */
    private static final int TITLE_WRAP_PX = 115;
    private static final int BODY_WRAP_PX = 140;

    private final PrivacyWarningManager privacy;
    private final ImageIcon eye = new ImageIcon(PrivacyEyeIcon.create(22));
    private final ImageIcon crossedEye = new ImageIcon(PrivacyEyeIcon.create(22, true));
    private final JPanel inner = new JPanel();
    private final JLabel icon = new JLabel();
    private final JLabel title = new JLabel();
    private final JLabel body = new JLabel();
    private final JCheckBox remember = new JCheckBox("Remember dismissal");
    private final JLabel controls = new JLabel("Privacy controls…");
    private final JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
    private final JButton primary;
    private final JButton secondary;
    private PrivacyWarningManager.Banner shown;

    @Inject
    public PrivacyWarningBanner(PrivacyWarningManager privacy)
    {
        this.privacy = privacy;
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(6, 6, 2, 6));

        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(true);
        add(inner, BorderLayout.CENTER);

        JPanel head = new JPanel(new BorderLayout(6, 0))
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        icon.setVerticalAlignment(SwingConstants.TOP);
        head.setOpaque(false);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(icon, BorderLayout.WEST);
        title.setFont(new Font("Calibri", Font.BOLD, 13));
        head.add(title, BorderLayout.CENTER);
        inner.add(head);
        inner.add(Box.createVerticalStrut(3));

        body.setFont(new Font("Calibri", Font.PLAIN, 12));
        body.setForeground(TEXT);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(body);
        inner.add(Box.createVerticalStrut(5));

        remember.setFont(new Font("Calibri", Font.BOLD, 12));
        remember.setForeground(TEXT);
        remember.setOpaque(false);
        remember.setFocusPainted(false);
        remember.setAlignmentX(Component.LEFT_ALIGNMENT);
        remember.setToolTipText("Don't show this box again for this account (an eye stays at the top right of the game)");
        inner.add(remember);

        controls.setFont(new Font("Calibri", Font.BOLD, 12));
        controls.setForeground(LINK);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        controls.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                privacy.openPrivacySettings();
            }
        });
        inner.add(controls);
        inner.add(Box.createVerticalStrut(6));

        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        primary = button();
        secondary = button();
        primary.addActionListener(e -> onPrimary());
        secondary.addActionListener(e -> onSecondary());
        inner.add(buttons);

        privacy.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        setVisible(false);
        refresh();
    }

    /** Matches the box to the current privacy state. EDT. */
    public void refresh()
    {
        PrivacyWarningManager.Banner state = privacy.banner();
        if (state != shown)
        {
            shown = state;
            apply(state);
        }
        boolean show = state != PrivacyWarningManager.Banner.NONE;
        if (show != isVisible())
        {
            setVisible(show);
            if (getParent() != null)
            {
                getParent().revalidate();
                getParent().repaint();
            }
        }
    }

    /** Current state (tests). */
    PrivacyWarningManager.Banner shownState()
    {
        return shown;
    }

    JButton primaryButton()
    {
        return primary;
    }

    JButton secondaryButton()
    {
        return secondary;
    }

    JCheckBox rememberBox()
    {
        return remember;
    }

    private void apply(PrivacyWarningManager.Banner state)
    {
        boolean hidden = false;
        String t = "";
        String b = "";
        String p = null;
        String s = null;
        boolean showRemember = false;
        boolean showControls = false;
        switch (state)
        {
            case PUBLIC:
                t = "Your online status is public";
                b = "Other players can see when you're online.";
                p = "Hide";
                s = "Dismiss";
                showRemember = true;
                break;
            case WILDERNESS:
                t = "Account visibility";
                b = "Your activity is public in the Wilderness.";
                p = "Hide for this trip";
                s = "Make private";
                showControls = true;
                break;
            case TRIP_HIDDEN:
                hidden = true;
                t = "Hidden for this Wilderness trip";
                b = "Public status resumes when you leave the Wilderness.";
                p = "Restore public";
                showControls = true;
                break;
            case APPLYING:
                t = "Applying…";
                b = "Updating your status on RuneAlytics.";
                break;
            case HIDDEN:
                hidden = true;
                t = "Hidden";
                b = "Your online status is private on RuneAlytics.";
                break;
            case FAILED:
                t = "Couldn't update RuneAlytics";
                b = "Your status may still be public. Check your connection and retry.";
                p = "Retry";
                s = "Dismiss";
                break;
            default:
                break;
        }
        inner.setBackground(hidden ? BG_HIDDEN : BG);
        inner.setBorder(new CompoundBorder(new LineBorder(hidden ? BORDER_HIDDEN : BORDER, 1, true),
                new EmptyBorder(7, 8, 8, 8)));
        icon.setIcon(hidden ? crossedEye : eye);
        title.setText("<html><body style='width:" + TITLE_WRAP_PX + "px'>" + t + "</body></html>");
        title.setForeground(hidden ? Color.WHITE : AMBER);
        body.setText("<html><body style='width:" + BODY_WRAP_PX + "px'>" + b + "</body></html>");
        remember.setVisible(showRemember);
        remember.setSelected(false);
        controls.setVisible(showControls);

        style(primary, p, true);
        style(secondary, s, false);
        buttons.removeAll();
        int count = (p != null ? 1 : 0) + (s != null ? 1 : 0);
        // Two short labels side by side; long ones ("Hide for this trip") stacked so they fit.
        boolean stack = count == 2 && p.length() + s.length() > 16;
        buttons.setLayout(stack ? new GridLayout(2, 1, 0, 5) : new GridLayout(1, Math.max(1, count), 6, 0));
        if (p != null) buttons.add(primary);
        if (s != null) buttons.add(secondary);
        buttons.setVisible(p != null || s != null);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        inner.revalidate();
        inner.repaint();
    }

    private void onPrimary()
    {
        if (shown == null) return;
        switch (shown)
        {
            case PUBLIC:
                privacy.makePrivate();
                break;
            case WILDERNESS:
                privacy.hideForTrip();
                break;
            case TRIP_HIDDEN:
                privacy.restorePublic();
                break;
            case FAILED:
                privacy.retry();
                break;
            default:
                break;
        }
    }

    private void onSecondary()
    {
        if (shown == null) return;
        switch (shown)
        {
            case PUBLIC:
                privacy.dismiss(remember.isSelected());
                break;
            case WILDERNESS:
                privacy.makePrivate();
                break;
            case FAILED:
                privacy.dismiss(false);
                break;
            default:
                break;
        }
    }

    private static JButton button()
    {
        JButton b = new JButton(" ");
        b.setFont(new Font("Calibri", Font.BOLD, 12));
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        return b;
    }

    private static void style(JButton b, String text, boolean primary)
    {
        b.setVisible(text != null);
        if (text == null) return;
        b.setText(text);
        boolean green = primary && !"Retry".equals(text);
        Color bg = green ? GREEN : NEUTRAL;
        Color border = green ? GREEN_BORDER : NEUTRAL_BORDER;
        b.setBackground(bg);
        b.setBorder(new CompoundBorder(new LineBorder(border, 1, true), AccountAdvisorPanel.centredPadding(b, 5, 6)));
    }
}
