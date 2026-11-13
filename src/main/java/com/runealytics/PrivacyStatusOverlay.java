package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-right of the game view (RuneLite puts TOP_RIGHT overlays beside the
 * minimap, never over it):
 * <ul>
 *   <li><b>Wilderness panel</b> ({@link PrivacyWarningManager#wildernessPanel()}):
 *       "Public online status / You're in the Wilderness" with <b>Hide for this trip</b>
 *       and <b>Make private</b>; then the crossed-out eye "Hidden for this trip" with
 *       <b>Restore public</b>; Applying / Hidden / failed states; close (X).</li>
 *   <li><b>Public status eye</b> after "Remember dismissal": click it for the privacy
 *       controls (opens RuneAlytics → Settings).</li>
 * </ul>
 * Buttons are left-clicked through {@link PrivacyOverlayMouseListener}; the same
 * actions are on the overlay's Shift+right-click menu. Nothing is drawn (and no
 * click is taken) when neither applies.
 *
 * <p>Singleton: the mouse listener must hit-test the instance that is actually
 * rendered (a second instance never gets bounds, so clicks would fall through).</p>
 */
@Singleton
public class PrivacyStatusOverlay extends Overlay
{
    private static final Color BOX_BG = new Color(28, 10, 10, 230);
    private static final Color BOX_BORDER = new Color(205, 60, 60);
    private static final Color HIDDEN_BORDER = new Color(110, 110, 120);
    private static final Color AMBER = PrivacyEyeIcon.AMBER;
    private static final Color MUTED = new Color(215, 205, 205);
    private static final Color BTN_BG = new Color(62, 24, 24);
    private static final Color BTN_HOVER = new Color(92, 36, 36);
    private static final Color BTN_GREEN = new Color(30, 92, 50);
    private static final Color BTN_GREEN_HOVER = new Color(40, 120, 66);
    private static final Color BTN_BORDER = new Color(150, 70, 70);
    private static final int PAD = 6;
    private static final int GAP = 6;
    private static final int BTN_H = 18;
    private static final String TARGET = "RuneAlytics";

    /** A clickable area, relative to the overlay's top-left. */
    static final class Hit
    {
        final Rectangle area;
        final Runnable action;

        Hit(Rectangle area, Runnable action)
        {
            this.area = area;
            this.action = action;
        }
    }

    private final Client client;
    private final PrivacyWarningManager privacy;
    private final BufferedImage eye = PrivacyEyeIcon.create(26);
    private final BufferedImage crossedEye = PrivacyEyeIcon.create(26, true);
    private final BufferedImage cornerEye = PrivacyEyeIcon.create(30);

    private volatile List<Hit> hits = Collections.emptyList();
    /** Which state the right-click entries were built for (client thread only). */
    private Object menuState;

    @Inject
    public PrivacyStatusOverlay(Client client, PrivacyWarningManager privacy)
    {
        this.client = client;
        this.privacy = privacy;
        setPosition(OverlayPosition.TOP_RIGHT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    /** Hit areas from the last frame (empty when nothing is drawn). */
    List<Hit> hits()
    {
        return hits;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        PrivacyWarningManager.WildPanel panel = privacy.wildernessPanel();
        Object state = panel != PrivacyWarningManager.WildPanel.NONE ? panel
                : privacy.showCornerEye() ? "CORNER" : null;
        if (state != menuState)
        {
            rebuildMenu(panel, state);
            menuState = state;
        }
        if (state == null)
        {
            hits = Collections.emptyList();
            return null;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        java.awt.Point mouse = mouseInOverlay();
        return panel != PrivacyWarningManager.WildPanel.NONE
                ? renderPanel(g, panel, mouse)
                : renderCornerEye(g, mouse);
    }

    private Dimension renderPanel(Graphics2D g, PrivacyWarningManager.WildPanel panel, java.awt.Point mouse)
    {
        Font bold = FontManager.getRunescapeBoldFont();
        Font small = FontManager.getRunescapeSmallFont();
        FontMetrics fb = g.getFontMetrics(bold);
        FontMetrics fs = g.getFontMetrics(small);

        String title;
        String line;
        String[] buttons;
        Runnable[] actions;
        boolean hidden = false;
        switch (panel)
        {
            case TRIP_HIDDEN:
                title = "Hidden for this trip";
                line = "Public status resumes when you leave";
                buttons = new String[]{"Restore public"};
                actions = new Runnable[]{privacy::restorePublic};
                hidden = true;
                break;
            case APPLYING:
                title = "Public online status";
                line = "Applying...";
                buttons = new String[0];
                actions = new Runnable[0];
                break;
            case HIDDEN:
                title = "Hidden";
                line = "Your online status is private";
                buttons = new String[0];
                actions = new Runnable[0];
                hidden = true;
                break;
            case FAILED:
                title = "Couldn't update RuneAlytics";
                line = "Your status may still be public";
                buttons = new String[]{"Retry"};
                actions = new Runnable[]{privacy::retry};
                break;
            default:
                title = "Public online status";
                line = "You're in the Wilderness";
                buttons = new String[]{"Hide for this trip", "Make private"};
                actions = new Runnable[]{privacy::hideForTrip, privacy::makePrivate};
                break;
        }

        BufferedImage icon = hidden ? crossedEye : eye;
        int closeSize = 11;
        int textX = PAD + icon.getWidth() + 6;
        int textW = Math.max(fb.stringWidth(title), fs.stringWidth(line));
        int btnW = 0;
        for (String b : buttons) btnW += fs.stringWidth(b) + 14;
        btnW += Math.max(0, buttons.length - 1) * GAP;
        int width = Math.max(textX + textW + GAP + closeSize, PAD + btnW) + PAD;
        int textH = fb.getHeight() + fs.getHeight();
        int top = PAD + Math.max(icon.getHeight(), textH);
        int height = top + (buttons.length > 0 ? GAP + BTN_H : 0) + PAD;

        g.setColor(BOX_BG);
        g.fillRect(0, 0, width, height);
        g.setColor(hidden ? HIDDEN_BORDER : BOX_BORDER);
        g.drawRect(0, 0, width - 1, height - 1);
        g.drawImage(icon, PAD, PAD + Math.max(0, (textH - icon.getHeight()) / 2), null);

        int y = PAD + fb.getAscent();
        g.setFont(bold);
        shadowed(g, title, textX, y, hidden ? Color.WHITE : AMBER);
        g.setFont(small);
        shadowed(g, line, textX, y + fb.getDescent() + fs.getAscent(), MUTED);

        List<Hit> next = new ArrayList<>();
        // Close (X)
        Rectangle close = new Rectangle(width - PAD - closeSize, PAD, closeSize, closeSize);
        boolean overClose = contains(close, mouse);
        g.setColor(overClose ? Color.WHITE : MUTED);
        g.drawLine(close.x + 2, close.y + 2, close.x + closeSize - 3, close.y + closeSize - 3);
        g.drawLine(close.x + closeSize - 3, close.y + 2, close.x + 2, close.y + closeSize - 3);
        next.add(new Hit(new Rectangle(close.x - 3, close.y - 3, closeSize + 6, closeSize + 6),
                privacy::closeWildernessPanel));

        int bx = PAD;
        int by = top + GAP;
        for (int i = 0; i < buttons.length; i++)
        {
            int w = fs.stringWidth(buttons[i]) + 14;
            if (buttons.length == 1) w = Math.max(w, width - PAD * 2);
            Rectangle r = new Rectangle(bx, by, w, BTN_H);
            boolean green = buttons[i].startsWith("Hide") || "Restore public".equals(buttons[i]);
            boolean over = contains(r, mouse);
            g.setColor(green ? (over ? BTN_GREEN_HOVER : BTN_GREEN) : (over ? BTN_HOVER : BTN_BG));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(green ? new Color(80, 170, 100) : BTN_BORDER);
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            int tx = r.x + (r.width - fs.stringWidth(buttons[i])) / 2;
            int ty = r.y + (r.height - fs.getHeight()) / 2 + fs.getAscent();
            shadowed(g, buttons[i], tx, ty, Color.WHITE);
            next.add(new Hit(r, actions[i]));
            bx += w + GAP;
        }
        // Clicks elsewhere on the box are swallowed (no walking under it).
        next.add(new Hit(new Rectangle(0, 0, width, height), null));
        hits = next;
        return new Dimension(width, height);
    }

    private Dimension renderCornerEye(Graphics2D g, java.awt.Point mouse)
    {
        Font bold = FontManager.getRunescapeBoldFont();
        Font small = FontManager.getRunescapeSmallFont();
        FontMetrics fb = g.getFontMetrics(bold);
        FontMetrics fs = g.getFontMetrics(small);
        String title = "Public status";
        String line = "Click for privacy controls";
        int textX = PAD + cornerEye.getWidth() + 6;
        int width = textX + Math.max(fb.stringWidth(title), fs.stringWidth(line)) + PAD;
        int textH = fb.getHeight() + fs.getHeight();
        int height = PAD * 2 + Math.max(cornerEye.getHeight(), textH);
        Rectangle box = new Rectangle(0, 0, width, height);
        boolean over = contains(box, mouse);

        g.setColor(over ? new Color(48, 18, 18, 235) : BOX_BG);
        g.fillRoundRect(0, 0, width, height, 8, 8);
        g.setColor(over ? AMBER : BOX_BORDER);
        g.drawRoundRect(0, 0, width - 1, height - 1, 8, 8);
        g.drawImage(cornerEye, PAD, (height - cornerEye.getHeight()) / 2, null);
        int y = (height - textH) / 2 + fb.getAscent();
        g.setFont(bold);
        shadowed(g, title, textX, y, AMBER);
        g.setFont(small);
        shadowed(g, line, textX, y + fb.getDescent() + fs.getAscent(), MUTED);

        hits = Collections.singletonList(new Hit(box, privacy::openPrivacySettings));
        return new Dimension(width, height);
    }

    /** Right-click entries matching what is on screen (client thread, from render). */
    private void rebuildMenu(PrivacyWarningManager.WildPanel panel, Object state)
    {
        getMenuEntries().clear();
        if (state == null) return;
        switch (panel)
        {
            case PUBLIC:
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Hide for this trip", TARGET, e -> privacy.hideForTrip());
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Make private", TARGET, e -> privacy.makePrivate());
                break;
            case TRIP_HIDDEN:
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Restore public", TARGET, e -> privacy.restorePublic());
                break;
            case FAILED:
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Retry", TARGET, e -> privacy.retry());
                break;
            case NONE:
                addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Make private", TARGET, e -> privacy.makePrivate());
                break;
            default:
                break;
        }
        addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Open privacy settings", TARGET, e -> privacy.openPrivacySettings());
    }

    private java.awt.Point mouseInOverlay()
    {
        net.runelite.api.Point m = client.getMouseCanvasPosition();
        Rectangle b = getBounds();
        if (m == null || b == null) return null;
        return new java.awt.Point(m.getX() - b.x, m.getY() - b.y);
    }

    private static boolean contains(Rectangle r, java.awt.Point p)
    {
        return p != null && r.contains(p);
    }

    private static void shadowed(Graphics2D g, String s, int x, int y, Color c)
    {
        g.setColor(Color.BLACK);
        g.drawString(s, x + 1, y + 1);
        g.setColor(c);
        g.drawString(s, x, y);
    }
}
