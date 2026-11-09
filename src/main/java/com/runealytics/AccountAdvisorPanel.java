package com.runealytics;

import com.runealytics.AccountAdvisorApiClient.Coverage;
import com.runealytics.AccountAdvisorApiClient.CoverageItem;
import com.runealytics.AccountAdvisorApiClient.Overview;
import com.runealytics.AccountAdvisorApiClient.Plan;
import com.runealytics.AccountAdvisorApiClient.PlanResult;
import com.runealytics.AccountAdvisorApiClient.Progress;
import com.runealytics.AccountAdvisorApiClient.ProgressUpdate;
import com.runealytics.AccountAdvisorApiClient.Recommendation;
import com.runealytics.AccountAdvisorApiClient.Step;
import com.runealytics.AccountAdvisorApiClient.Summary;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Account Advisor tab ("Advisor").
 *
 * Layout (BorderLayout, top to bottom):
 *   NORTH  standard tab header (RuneAlyticsUi.buildStandardHeaderPanel) holding
 *          the account/status row, the two-column summary, [Generate advice]
 *          [Open website], Focus, and the [All][Ready now][In progress] filters
 *   CENTER CardLayout: recommendation list | selected-plan details — each its
 *          own vertical JScrollPane (no nesting), so Back keeps your place
 *   SOUTH  connection / freshness footer
 *
 * Data comes from the website's Account Advisor (AccountAdvisorApiClient).
 * Requests run on the plugin executor; Swing changes happen on the EDT. Every
 * response is checked against a request generation AND the account it was
 * made for, so an old answer can never land under a newly logged-in account.
 * Cards are keyed by stable activity id and updated in place on progress
 * refreshes; the list is only rebuilt when a different plan arrives.
 */
@Slf4j
@Singleton
public class AccountAdvisorPanel extends RuneAlyticsPanelBase
{
    // ── theme (existing plugin colours first; spec fallbacks where none exists) ──
    private static final Color BG          = ColorScheme.DARK_GRAY_COLOR;
    private static final Color HEADER_BG   = RuneAlyticsUi.TAB_HEADER_BG;
    private static final Color CARD_BG     = new Color(27, 27, 28);    // MatchmakingPanel CARD_BG
    private static final Color RAISED      = new Color(39, 43, 47);
    private static final Color BORDER      = new Color(70, 70, 74);    // MatchmakingPanel CARD_BORDER
    private static final Color TEXT        = new Color(240, 240, 237);
    private static final Color MUTED       = new Color(168, 172, 174);
    private static final Color GOLD        = new Color(200, 180, 80);  // Loot Tracker value colour
    private static final Color GREEN       = new Color(72, 199, 116);  // MatchmakingPanel COL_GREEN
    private static final Color NEW_BG      = new Color(15, 40, 20);    // Loot Tracker highlight (SKILLING_HEADER_HL)
    private static final Color PURPLE      = new Color(184, 138, 224);
    private static final Color ERROR       = new Color(234, 146, 146);
    private static final Color AMBER       = new Color(222, 170, 60);
    private static final Color FILTER_INACTIVE = new Color(38, 38, 38);
    private static final Color FILTER_BORDER   = new Color(70, 70, 70);

    private static final Font TITLE      = new Font("Calibri", Font.BOLD, 13);
    private static final Font BOLD12     = new Font("Calibri", Font.BOLD, 12);
    private static final Font PLAIN12    = new Font("Calibri", Font.PLAIN, 12);
    private static final Font PLAIN11    = new Font("Calibri", Font.PLAIN, 11);
    private static final Font BOLD11     = new Font("Calibri", Font.BOLD, 11);
    private static final Font META       = new Font("Calibri", Font.PLAIN, 10);
    private static final Font ICON_FONT  = new Font("Dialog", Font.PLAIN, 20);

    private static final String CONFIG_GROUP = "runealytics";
    private static final String SEEN_KEY_PREFIX = "advisorSeen_";
    private static final int SEEN_MAX = 300;
    private static final long[] POLL_BACKOFF_MS = {1500, 2000, 3000, 4000, 5000};
    private static final int POLL_MAX = 30;                      // ~2.5 min, then stop and offer Refresh

    private static final Map<String, String> FOCUS_LABELS = new LinkedHashMap<>();
    static
    {
        FOCUS_LABELS.put("questing", "Questing");
        FOCUS_LABELS.put("money", "Money making");
        FOCUS_LABELS.put("bossing", "Bossing");
        FOCUS_LABELS.put("skilling", "Skilling");
        FOCUS_LABELS.put("collection_log", "Collection log");
    }

    private enum Filter { ALL, READY, IN_PROGRESS }

    // ── dependencies ──────────────────────────────────────────────────────────
    private final AccountAdvisorApiClient api;
    private final RuneAlyticsState state;
    private final ScheduledExecutorService executor;
    private final ConfigManager configManager;
    private volatile Runnable openSettings = () -> { };

    // ── NORTH widgets ─────────────────────────────────────────────────────────
    private final JLabel accountName = new JLabel(" ");
    private final JLabel verifiedBadge = new JLabel(" ");
    private final JButton headerWebsite = iconButton("↗", "Open your Account Advisor on runealytics.com");
    private final JButton headerSettings = iconButton("⚙", "RuneAlytics settings");
    private final JLabel goalsValue = valueLabel();
    private final JLabel stepsValue = valueLabel();
    private final JLabel recsValue = valueLabel();
    private final JLabel updatedValue = valueLabel();
    private final JButton generateButton = actionButton("✦ Generate advice", GREEN);
    private final JButton websiteButton = actionButton("↗ Open website", BORDER);
    private final JComboBox<String> focusBox = new JComboBox<>();
    private final JButton filterAll = filterPill("All");
    private final JButton filterReady = filterPill("Ready now");
    private final JButton filterProgress = filterPill("In progress");

    // ── CENTER ────────────────────────────────────────────────────────────────
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final ScrollableContentPanel listContent = new ScrollableContentPanel();
    private final ScrollableContentPanel detailContent = new ScrollableContentPanel();
    private final JPanel genPanel = vertical();
    private final JLabel listTitle = new JLabel("Your next upgrades");
    private final JLabel listCount = new JLabel("");
    private final JLabel keptNote = pill("Current plan remains available", BORDER, MUTED);
    private final JPanel cardsPanel = vertical();
    private final JPanel emptyPanel = vertical();
    private final JPanel coveragePanel = vertical();

    // ── SOUTH ─────────────────────────────────────────────────────────────────
    private final JLabel footerDot = new JLabel("●");
    private final JLabel footerText = new JLabel(" ");

    // ── state (EDT-owned unless noted) ────────────────────────────────────────
    private final AtomicInteger generation = new AtomicInteger();          // any thread
    private final Map<String, RecCard> cards = new LinkedHashMap<>();      // activity id → card
    private Plan plan;
    private Summary summary;
    private Coverage coverage;
    private String horizon;
    private int defaultMinutes = 60;
    private String webUrl;
    private String account;          // lower-cased RSN the shown data belongs to
    private Filter filter = Filter.ALL;
    private String detailKey;        // activity id of the open detail view
    private boolean generating;
    private String genError;
    private Integer buildingPlanId;
    private ScheduledFuture<?> poll;
    private long lastOkAt;
    private boolean lastFailed;
    private Set<String> seen = new LinkedHashSet<>();
    private boolean suppressFocusEvents;

    @Inject
    public AccountAdvisorPanel(AccountAdvisorApiClient api, RuneAlyticsState state, ScheduledExecutorService executor, ConfigManager configManager)
    {
        this.api = api;
        this.state = state;
        this.executor = executor;
        this.configManager = configManager;
        buildUi();
        refreshLoginState();
    }

    /** Called by the plugin so the header ⚙ can switch to the Settings tab. */
    public void setOpenSettings(Runnable r)
    {
        openSettings = r != null ? r : () -> { };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Layout
    // ═════════════════════════════════════════════════════════════════════════

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setBackground(BG);

        JPanel header = RuneAlyticsUi.buildStandardHeaderPanel("Account Advisor");
        header.add(RuneAlyticsUi.vSpace(8));
        header.add(buildAccountRow());
        header.add(RuneAlyticsUi.vSpace(8));
        header.add(buildSummary());
        header.add(RuneAlyticsUi.vSpace(8));
        header.add(buildControls());
        header.add(RuneAlyticsUi.vSpace(6));
        header.add(buildFocusRow());
        header.add(RuneAlyticsUi.vSpace(6));
        header.add(separator());
        header.add(RuneAlyticsUi.vSpace(6));
        header.add(buildFilterRow());
        add(header, BorderLayout.NORTH);

        // List view.
        listContent.setLayout(new BoxLayout(listContent, BoxLayout.Y_AXIS));
        listContent.setBackground(BG);
        listContent.setBorder(new EmptyBorder(8, 8, 8, 8));
        listContent.add(genPanel);
        JPanel titleRow = new JPanel(new BorderLayout(6, 0));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        listTitle.setFont(new Font("Calibri", Font.BOLD, 14));
        listTitle.setForeground(GOLD);
        listCount.setFont(BOLD11);
        listCount.setForeground(TEXT);
        titleRow.add(listTitle, BorderLayout.WEST);
        titleRow.add(listCount, BorderLayout.EAST);
        limitHeight(titleRow);
        listContent.add(titleRow);
        listContent.add(RuneAlyticsUi.vSpace(4));
        keptNote.setAlignmentX(LEFT_ALIGNMENT);
        keptNote.setVisible(false);
        listContent.add(keptNote);
        listContent.add(cardsPanel);
        listContent.add(emptyPanel);
        listContent.add(coveragePanel);

        detailContent.setLayout(new BoxLayout(detailContent, BoxLayout.Y_AXIS));
        detailContent.setBackground(BG);
        detailContent.setBorder(new EmptyBorder(8, 8, 8, 8));

        center.setBackground(BG);
        center.add(scroll(listContent), "list");
        center.add(scroll(detailContent), "detail");
        add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.setBackground(HEADER_BG);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, new Color(55, 55, 55)), new EmptyBorder(5, 8, 5, 8)));
        footerDot.setFont(META);
        footerText.setFont(META);
        footerText.setForeground(MUTED);
        footer.add(footerDot, BorderLayout.WEST);
        footer.add(footerText, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        // Actions.
        generateButton.addActionListener(e -> generate());
        websiteButton.addActionListener(e -> openWebsite());
        headerWebsite.addActionListener(e -> openWebsite());
        headerSettings.addActionListener(e -> openSettings.run());
        filterAll.addActionListener(e -> setFilter(Filter.ALL));
        filterReady.addActionListener(e -> setFilter(Filter.READY));
        filterProgress.addActionListener(e -> setFilter(Filter.IN_PROGRESS));
        applyFilterColors();
    }

    private JPanel buildAccountRow()
    {
        JPanel block = vertical();
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel icon = new JLabel("🧭");
        icon.setFont(new Font("Dialog", Font.PLAIN, 16));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        accountName.setFont(BOLD12);
        accountName.setForeground(TEXT);
        accountName.setToolTipText("The account logged in to RuneLite — advice is always for this account");
        verifiedBadge.setFont(META);
        left.add(icon);
        left.add(accountName);
        left.add(verifiedBadge);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(headerWebsite);
        right.add(headerSettings);

        row.add(left, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        limitHeight(row);
        block.add(row);

        JLabel by = new JLabel("Advice generated by RuneAlytics");
        by.setFont(META);
        by.setForeground(MUTED);
        by.setAlignmentX(LEFT_ALIGNMENT);
        block.add(by);
        return block;
    }

    /** Two equal columns, aligned label/value rows, a subtle divider — like the Loot Tracker's stats row. */
    private JPanel buildSummary()
    {
        JPanel grid = new JPanel(new GridLayout(1, 2, 0, 0));
        grid.setBackground(CARD_BG);
        grid.setBorder(new CompoundBorder(new LineBorder(BORDER, 1), new EmptyBorder(6, 8, 6, 8)));
        grid.setAlignmentX(LEFT_ALIGNMENT);

        JPanel left = statColumn(new String[]{"Active goals", "Steps completed"}, new JLabel[]{goalsValue, stepsValue});
        JPanel right = statColumn(new String[]{"Recommendations", "Updated"}, new JLabel[]{recsValue, updatedValue});
        left.setBorder(new EmptyBorder(0, 0, 0, 6));
        right.setBorder(new CompoundBorder(new MatteBorder(0, 1, 0, 0, new Color(60, 60, 64)), new EmptyBorder(0, 6, 0, 0)));
        grid.add(left);
        grid.add(right);
        limitHeight(grid);
        return grid;
    }

    /** One column: label above value for each stat (aligned, never truncated in a narrow sidebar). */
    private JPanel statColumn(String[] labels, JLabel[] values)
    {
        JPanel col = new JPanel(new GridBagLayout());
        col.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labels.length; i++)
        {
            JLabel l = new JLabel(labels[i]);
            l.setFont(META);
            l.setForeground(MUTED);
            l.setToolTipText(labels[i]);
            c.gridy = i * 2;
            c.insets = new Insets(i == 0 ? 0 : 4, 0, 0, 0);
            col.add(l, c);
            c.gridy = i * 2 + 1;
            c.insets = new Insets(0, 0, 0, 0);
            col.add(values[i], c);
        }
        return col;
    }

    private JPanel buildControls()
    {
        generateButton.setToolTipText("Ask RuneAlytics for a new set of recommendations from your synced data");
        websiteButton.setToolTipText("Open this account's Account Advisor on runealytics.com");
        return buttonPair(generateButton, websiteButton);
    }

    private JPanel buildFocusRow()
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel l = new JLabel("Focus:");
        l.setFont(PLAIN12);
        l.setForeground(GOLD);
        focusBox.setFont(PLAIN12);
        focusBox.setFocusable(false);
        focusBox.setBackground(FILTER_INACTIVE);
        focusBox.setForeground(TEXT);
        focusBox.setToolTipText("What the next Generate advice should focus on");
        setFocusOptions(new ArrayList<>(FOCUS_LABELS.keySet()), Collections.emptyList());
        row.add(l, BorderLayout.WEST);
        row.add(focusBox, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return row;
    }

    private JPanel buildFilterRow()
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(filterAll);
        row.add(filterReady);
        row.add(filterProgress);
        filterReady.setToolTipText("Every known requirement is met");
        filterProgress.setToolTipText("Tracked, or some steps done");
        limitHeight(row);
        return row;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Login / account lifecycle (called by the plugin)
    // ═════════════════════════════════════════════════════════════════════════

    public void refreshLoginState()
    {
        SwingUtilities.invokeLater(() -> {
            String now = state.isLoggedIn() && state.isVerified() && state.getVerifiedUsername() != null
                    ? state.getVerifiedUsername().toLowerCase() : null;
            if (now == null)
            {
                clearForAccount(null);
                accountName.setText(state.isLoggedIn() && state.getVerifiedUsername() == null ? "Not linked" : "Not logged in");
                verifiedBadge.setText(state.isLoggedIn() ? "  ✕ not verified" : "");
                verifiedBadge.setForeground(ERROR);
                setControls(false);
                showEmpty(state.isLoggedIn() ? "Verify this account on the Settings tab to get recommendations."
                        : "Log in to RuneLite to see your recommendations.");
                setFooter(MUTED, state.isLoggedIn() ? "Not verified" : "Not logged in");
                return;
            }
            accountName.setText(state.getVerifiedUsername());
            verifiedBadge.setText("  ✓ verified");
            verifiedBadge.setForeground(GREEN);
            if (!now.equals(account))
            {
                clearForAccount(now);      // a different account: drop everything shown for the old one
                loadOverview();
            }
            else
            {
                setControls(true);
            }
        });
    }

    /** Plugin shutdown: stop polling, invalidate callbacks. */
    public void shutDown()
    {
        generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            cancelPoll();
            clearForAccount(null);
        });
    }

    private void clearForAccount(String newAccount)
    {
        generation.incrementAndGet();          // any response in flight is for the old account: drop it
        cancelPoll();
        account = newAccount;
        plan = null;
        summary = null;
        coverage = null;
        webUrl = null;
        generating = false;
        genError = null;
        buildingPlanId = null;
        detailKey = null;
        lastOkAt = 0;
        lastFailed = false;
        cards.clear();
        cardsPanel.removeAll();
        emptyPanel.removeAll();
        coveragePanel.removeAll();
        seen = newAccount != null ? loadSeen(newAccount) : new LinkedHashSet<>();
        renderSummary();
        renderGenPanel();
        centerCards.show(center, "list");
        revalidate();
        repaint();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Requests
    // ═════════════════════════════════════════════════════════════════════════

    private void loadOverview()
    {
        final String acct = account;
        if (acct == null)
        {
            return;
        }
        final int gen = generation.incrementAndGet();
        setControls(false);
        setFooter(MUTED, "Loading your advice…");
        executor.execute(() -> {
            try
            {
                Overview o = api.overview(null);
                onEdt(gen, acct, () -> {
                    horizon = o.horizon;
                    defaultMinutes = o.defaultMinutes;
                    webUrl = o.webUrl;
                    setFocusOptions(o.focusOptions, o.focus);
                    coverage = o.coverage;
                    summary = o.summary;
                    markOk();
                    if (o.plan != null && o.plan.isPending())
                    {
                        startBuilding(o.plan.id, gen, acct);
                    }
                    else if (o.plan != null)
                    {
                        applyPlan(o.plan, o.summary);
                        if (o.regeneratingId != null)
                        {
                            startBuilding(o.regeneratingId, gen, acct);   // website is refreshing a stale plan
                        }
                    }
                    else
                    {
                        renderSummary();
                        showEmpty("No recommendations yet — press Generate advice.");
                    }
                    renderCoverage();
                    if (!generating)
                    {
                        setControls(true);
                    }
                });
            }
            catch (Exception ex)
            {
                onEdt(gen, acct, () -> fail("load", ex));
            }
        });
    }

    /** Generate advice: one request at a time; the website also reuses an identical pending plan. */
    private void generate()
    {
        final String acct = account;
        if (acct == null || generating)
        {
            return;
        }
        final int gen = generation.incrementAndGet();
        final List<String> focus = selectedFocus();
        final String h = horizon != null ? horizon : "today";
        final int minutes = defaultMinutes;
        generating = true;
        genError = null;
        buildingPlanId = null;
        renderGenPanel();
        setControls(true);
        setFooter(PURPLE, "Requested just now · Waiting for website");
        executor.execute(() -> {
            try
            {
                PlanResult r = api.generate(h, minutes, focus);
                onEdt(gen, acct, () -> {
                    markOk();
                    if (r.plan.isPending())
                    {
                        startBuilding(r.plan.id, gen, acct);
                    }
                    else
                    {
                        finishGeneration(r);
                    }
                });
            }
            catch (Exception ex)
            {
                onEdt(gen, acct, () -> fail("generate", ex));
            }
        });
    }

    private void startBuilding(int planId, int gen, String acct)
    {
        generating = true;
        buildingPlanId = planId;
        genError = null;
        renderGenPanel();
        setControls(true);
        schedulePoll(planId, gen, acct, 0);
    }

    /** Bounded polling with backoff while the website builds the plan; stops on ready / failed / account change. */
    private void schedulePoll(int planId, int gen, String acct, int attempt)
    {
        cancelPoll();
        if (attempt >= POLL_MAX)
        {
            generating = false;
            genError = "The website is taking longer than usual. Press Retry to check again.";
            renderGenPanel();
            setControls(true);
            return;
        }
        long delay = POLL_BACKOFF_MS[Math.min(attempt, POLL_BACKOFF_MS.length - 1)];
        poll = executor.schedule(() -> {
            try
            {
                PlanResult r = api.plan(planId);
                onEdt(gen, acct, () -> {
                    markOk();
                    if (r.plan.isPending())
                    {
                        schedulePoll(planId, gen, acct, attempt + 1);
                    }
                    else
                    {
                        finishGeneration(r);
                    }
                });
            }
            catch (Exception ex)
            {
                onEdt(gen, acct, () -> fail("generate", ex));
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void finishGeneration(PlanResult r)
    {
        generating = false;
        buildingPlanId = null;
        if (r.plan.isFailed())
        {
            genError = r.plan.error != null ? r.plan.error : "The plan couldn't be built.";
            renderGenPanel();          // the previous plan stays on screen
            setControls(true);
            return;
        }
        genError = null;
        applyPlan(r.plan, r.summary);
        renderGenPanel();
        setControls(true);
    }

    /** Refresh progress: re-check the current plan's steps; cards update in place (no new plan). */
    private void refreshProgress()
    {
        final String acct = account;
        final Plan p = plan;
        if (acct == null || p == null || !p.isReady())
        {
            return;
        }
        final int gen = generation.get();
        setFooter(MUTED, "Checking progress…");
        executor.execute(() -> {
            try
            {
                ProgressUpdate u = api.progress(p.id);
                onEdt(gen, acct, () -> applyProgress(u));
            }
            catch (Exception ex)
            {
                onEdt(gen, acct, () -> fail("progress", ex));
            }
        });
    }

    private void toggleTrack(Recommendation r)
    {
        final String acct = account;
        if (acct == null || r.progress == null || r.activityId == null)
        {
            return;
        }
        final boolean target = !r.progress.tracked;
        final int gen = generation.get();
        replaceProgress(r.key, r.progress.withTracked(target));      // optimistic; reverted on failure
        executor.execute(() -> {
            try
            {
                api.track(r.activityId, target);
                onEdt(gen, acct, this::markOk);
            }
            catch (Exception ex)
            {
                onEdt(gen, acct, () -> {
                    Recommendation cur = find(r.key);
                    if (cur != null && cur.progress != null)
                    {
                        replaceProgress(r.key, cur.progress.withTracked(!target));
                    }
                    fail("track", ex);
                });
            }
        });
    }

    private void ackStep(Step s, boolean done)
    {
        final String acct = account;
        final Plan p = plan;
        if (acct == null || p == null || !s.ackable)
        {
            return;
        }
        final int gen = generation.get();
        executor.execute(() -> {
            try
            {
                ProgressUpdate u = api.ack(p.id, s.id, done);
                onEdt(gen, acct, () -> applyProgress(u));
            }
            catch (Exception ex)
            {
                onEdt(gen, acct, () -> {
                    fail("ack", ex);
                    renderDetail();        // restore the checkbox to the server's state
                });
            }
        });
    }

    /** Run on the EDT only if no newer request started and the same account is still shown. */
    private void onEdt(int gen, String acct, Runnable r)
    {
        SwingUtilities.invokeLater(() -> {
            if (gen == generation.get() && acct != null && acct.equals(account))
            {
                r.run();
            }
        });
    }

    private void fail(String what, Exception ex)
    {
        log.debug("[Advisor] {} failed: {}", what, ex.getMessage());
        String msg = ex instanceof AccountAdvisorApiClient.ApiException ? ex.getMessage()
                : "Couldn't reach RuneAlytics. Check your connection.";
        lastFailed = true;
        if ("generate".equals(what))
        {
            generating = false;
            genError = msg;
            renderGenPanel();
        }
        else if ("load".equals(what) && plan == null)
        {
            showEmpty(msg);
        }
        setControls(account != null);
        setFooter(ERROR, msg);
    }

    private void cancelPoll()
    {
        if (poll != null)
        {
            poll.cancel(false);
            poll = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Applying data
    // ═════════════════════════════════════════════════════════════════════════

    private void applyPlan(Plan p, Summary s)
    {
        boolean samePlan = plan != null && plan.id == p.id;
        plan = p;
        if (p.webUrl != null)
        {
            webUrl = p.webUrl;
        }
        if (s != null)
        {
            summary = s;
        }
        renderSummary();
        if (samePlan)
        {
            for (Recommendation r : p.recommendations)
            {
                RecCard c = cards.get(r.activityId);
                if (c != null)
                {
                    c.update(r);
                }
            }
        }
        else
        {
            rebuildCards();
        }
        applyFilter();
        if (detailKey != null)
        {
            if (find(detailKey) == null && findByActivity(detailKey) == null)
            {
                closeDetail();
            }
            else
            {
                renderDetail();
            }
        }
    }

    private void applyProgress(ProgressUpdate u)
    {
        if (plan == null || u.planId != plan.id)
        {
            return;
        }
        markOk();
        plan = plan.withProgress(u.byKey);
        summary = u.summary;
        coverage = u.coverage;
        renderSummary();
        for (Recommendation r : plan.recommendations)
        {
            RecCard c = cards.get(r.activityId);
            if (c != null)
            {
                c.update(r);                      // targeted update, no rebuild
            }
        }
        applyFilter();
        renderCoverage();
        if (detailKey != null)
        {
            renderDetail();
        }
    }

    private void replaceProgress(String key, Progress p)
    {
        Map<String, Progress> m = new LinkedHashMap<>();
        m.put(key, p);
        plan = plan.withProgress(m);
        Recommendation r = find(key);
        if (r != null && cards.get(r.activityId) != null)
        {
            cards.get(r.activityId).update(r);
        }
        applyFilter();
        if (detailKey != null)
        {
            renderDetail();
        }
    }

    private void markOk()
    {
        lastOkAt = System.currentTimeMillis();
        lastFailed = false;
        renderFooter();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rendering — header, list, generation panel, coverage, footer
    // ═════════════════════════════════════════════════════════════════════════

    private void renderSummary()
    {
        Summary s = summary;
        goalsValue.setText(s != null && s.activeGoals != null ? String.valueOf(s.activeGoals) : "—");
        stepsValue.setText(s != null && s.stepsDone != null && s.stepsTotal != null ? s.stepsDone + "/" + s.stepsTotal : "—");
        recsValue.setText(s != null && s.recommendations != null ? String.valueOf(s.recommendations) : "—");
        String upd = s != null ? ago(s.updatedAt) : null;
        updatedValue.setText(upd != null ? upd : (plan == null ? "Not generated" : "—"));
        int n = plan != null ? plan.recommendations.size() : 0;
        listCount.setText(plan != null ? String.valueOf(n) : "");
    }

    private void rebuildCards()
    {
        cards.clear();
        cardsPanel.removeAll();
        if (plan != null)
        {
            for (Recommendation r : plan.recommendations)
            {
                String id = r.activityId != null ? r.activityId : r.key;
                RecCard c = new RecCard(r);
                cards.put(id, c);
                cardsPanel.add(c);
            }
        }
        emptyPanel.removeAll();
        if (plan != null && plan.recommendations.isEmpty())
        {
            emptyPanel.add(muted("No recommendations right now. Set a goal on the website, then Generate advice."));
        }
        cardsPanel.revalidate();
        cardsPanel.repaint();
    }

    private void applyFilter()
    {
        int shown = 0;
        for (Map.Entry<String, RecCard> e : cards.entrySet())
        {
            Recommendation r = e.getValue().rec;
            boolean show = filter == Filter.ALL
                    || (filter == Filter.READY && r.progress != null && r.progress.ready)
                    || (filter == Filter.IN_PROGRESS && r.progress != null && (r.progress.tracked || (r.progress.done > 0 && r.progress.done < r.progress.total)));
            e.getValue().setVisible(show);
            if (show)
            {
                shown++;
            }
        }
        emptyPanel.removeAll();
        if (plan != null && !cards.isEmpty() && shown == 0)
        {
            emptyPanel.add(muted(filter == Filter.READY ? "Nothing is ready now — check requirements in each plan."
                    : "Nothing in progress yet. Track a plan or complete a step."));
        }
        else if (plan != null && plan.recommendations.isEmpty())
        {
            emptyPanel.add(muted("No recommendations right now. Set a goal on the website, then Generate advice."));
        }
        listContent.revalidate();
        listContent.repaint();
    }

    private void showEmpty(String message)
    {
        cards.clear();
        cardsPanel.removeAll();
        emptyPanel.removeAll();
        emptyPanel.add(muted(message));
        renderSummary();
        listContent.revalidate();
        listContent.repaint();
    }

    /** Real stages only: request accepted → website building → ready / failed. */
    private void renderGenPanel()
    {
        genPanel.removeAll();
        keptNote.setVisible(false);
        if (generating || genError != null)
        {
            JPanel card = card(PURPLE);
            if (generating)
            {
                card.add(text("Preparing your recommendations", TITLE, TEXT));
                card.add(text("The website is reviewing your account.", PLAIN11, MUTED));
                card.add(RuneAlyticsUi.vSpace(6));
                card.add(stageRow(buildingPlanId != null ? "✔" : "…", buildingPlanId != null ? GREEN : PURPLE, "Request accepted", buildingPlanId != null));
                card.add(stageRow(buildingPlanId != null ? "…" : "○", buildingPlanId != null ? PURPLE : MUTED, "Building your plan", buildingPlanId != null));
                card.add(RuneAlyticsUi.vSpace(6));
                card.add(text("You can keep playing. This tab updates when your plan is ready.", META, MUTED));
            }
            else
            {
                card.add(text("Couldn't generate new advice", TITLE, ERROR));
                card.add(text(genError, PLAIN11, MUTED));
                card.add(RuneAlyticsUi.vSpace(6));
                JButton retry = actionButton("↻ Retry", BORDER);
                retry.addActionListener(e -> {
                    genError = null;
                    if (buildingPlanId != null)
                    {
                        startBuilding(buildingPlanId, generation.get(), account);
                    }
                    else
                    {
                        generate();
                    }
                });
                card.add(fullWidth(retry));
            }
            genPanel.add(card);
            genPanel.add(RuneAlyticsUi.vSpace(6));
            keptNote.setVisible(plan != null);
        }
        generateButton.setText(generating ? "✦ Generating…" : "✦ Generate advice");
        genPanel.revalidate();
        genPanel.repaint();
    }

    private void renderCoverage()
    {
        coveragePanel.removeAll();
        if (coverage == null || coverage.items.isEmpty())
        {
            coveragePanel.revalidate();
            return;
        }
        coveragePanel.add(RuneAlyticsUi.vSpace(6));
        coveragePanel.add(sectionTitle("Data coverage"));
        coveragePanel.add(coveragePills(coverage));
        if (plan != null && !plan.missingData.isEmpty())
        {
            coveragePanel.add(RuneAlyticsUi.vSpace(4));
            for (String m : plan.missingData)
            {
                coveragePanel.add(text("• " + m, META, MUTED));
            }
        }
        coveragePanel.revalidate();
        coveragePanel.repaint();
    }

    private void renderFooter()
    {
        String sync = coverage != null ? ago(coverage.syncedAt) : null;
        setFooter(GREEN, "Website connected" + (sync != null ? " · Sync received " + sync : ""));
    }

    private void setFooter(Color dot, String text)
    {
        footerDot.setForeground(dot);
        footerText.setText(text);
        footerText.setToolTipText(text);
    }

    private void setControls(boolean on)
    {
        generateButton.setEnabled(on && !generating);
        websiteButton.setEnabled(on && webUrl != null);
        headerWebsite.setEnabled(on && webUrl != null);
        focusBox.setEnabled(on && !generating);
        filterAll.setEnabled(on);
        filterReady.setEnabled(on);
        filterProgress.setEnabled(on);
    }

    private void setFilter(Filter f)
    {
        filter = f;
        applyFilterColors();
        applyFilter();
    }

    private void applyFilterColors()
    {
        stylePill(filterAll, filter == Filter.ALL);
        stylePill(filterReady, filter == Filter.READY);
        stylePill(filterProgress, filter == Filter.IN_PROGRESS);
    }

    private void setFocusOptions(List<String> options, List<String> selected)
    {
        suppressFocusEvents = true;
        focusBox.removeAllItems();
        focusBox.addItem("Balanced");
        for (String o : options)
        {
            focusBox.addItem(FOCUS_LABELS.getOrDefault(o, o));
        }
        if (selected != null && selected.size() == 1)
        {
            focusBox.setSelectedItem(FOCUS_LABELS.getOrDefault(selected.get(0), selected.get(0)));
        }
        suppressFocusEvents = false;
    }

    /** "Balanced" = no focus (the advisor's default mix); otherwise one supported focus key. */
    private List<String> selectedFocus()
    {
        Object sel = focusBox.getSelectedItem();
        if (sel == null || "Balanced".equals(sel))
        {
            return Collections.emptyList();
        }
        for (Map.Entry<String, String> e : FOCUS_LABELS.entrySet())
        {
            if (e.getValue().equals(sel))
            {
                return Collections.singletonList(e.getKey());
            }
        }
        return Collections.emptyList();
    }

    private void openWebsite()
    {
        if (webUrl != null)
        {
            LinkBrowser.browse(webUrl);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Detail view
    // ═════════════════════════════════════════════════════════════════════════

    private void openDetail(Recommendation r)
    {
        detailKey = r.activityId != null ? r.activityId : r.key;
        acknowledgeNew(r);
        renderDetail();
        centerCards.show(center, "detail");
    }

    private void closeDetail()
    {
        detailKey = null;
        centerCards.show(center, "list");
    }

    private void renderDetail()
    {
        Recommendation r = findByActivity(detailKey);
        if (r == null)
        {
            r = find(detailKey);
        }
        detailContent.removeAll();
        if (r == null || plan == null)
        {
            closeDetail();
            return;
        }
        final Recommendation rec = r;

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.setAlignmentX(LEFT_ALIGNMENT);
        JButton back = iconButton("‹", "Back to recommendations");
        back.addActionListener(e -> closeDetail());
        JLabel icon = new JLabel(categoryIcon(rec.category));
        icon.setFont(ICON_FONT);
        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        west.setOpaque(false);
        west.add(back);
        west.add(icon);
        top.add(west, BorderLayout.WEST);
        top.add(text(rec.title, TITLE, TEXT), BorderLayout.CENTER);
        detailContent.add(top);
        detailContent.add(RuneAlyticsUi.vSpace(4));
        detailContent.add(badges(rec));
        detailContent.add(RuneAlyticsUi.vSpace(8));

        JPanel why = card(BORDER);
        why.add(sectionTitle("Why this is recommended"));
        why.add(text(rec.why != null && !rec.why.isEmpty() ? rec.why : (rec.summary != null ? rec.summary : "—"), PLAIN11, TEXT));
        detailContent.add(why);
        detailContent.add(RuneAlyticsUi.vSpace(6));

        Progress p = rec.progress;
        if (p != null)
        {
            List<Step> reqs = new ArrayList<>();
            List<Step> planSteps = new ArrayList<>();
            for (Step s : p.steps)
            {
                ("requirement".equals(s.kind) ? reqs : planSteps).add(s);
            }
            JPanel stepsCard = card(BORDER);
            stepsCard.add(sectionTitle("Plan steps"));
            for (Step s : planSteps)
            {
                stepsCard.add(stepRow(s));
            }
            detailContent.add(stepsCard);
            detailContent.add(RuneAlyticsUi.vSpace(6));

            if (!reqs.isEmpty())
            {
                JPanel reqCard = card(BORDER);
                reqCard.add(sectionTitle("Requirements"));
                for (Step s : reqs)
                {
                    reqCard.add(stepRow(s));
                }
                detailContent.add(reqCard);
                detailContent.add(RuneAlyticsUi.vSpace(6));
            }

            JPanel prog = new JPanel(new BorderLayout(6, 0));
            prog.setOpaque(false);
            prog.setAlignmentX(LEFT_ALIGNMENT);
            JLabel pl = new JLabel("Progress: " + p.done + " of " + p.total);
            pl.setFont(BOLD12);
            pl.setForeground(GOLD);
            prog.add(pl, BorderLayout.WEST);
            prog.add(new ThinBar(p.done, p.total), BorderLayout.CENTER);
            limitHeight(prog);
            detailContent.add(prog);
            detailContent.add(text(legend(), META, MUTED));
            detailContent.add(RuneAlyticsUi.vSpace(8));
        }

        JButton track = actionButton(p != null && p.tracked ? "★ Tracking" : "☆ Track plan", p != null && p.tracked ? GREEN : BORDER);
        track.setToolTipText("Keep this plan in In progress (saved on RuneAlytics)");
        track.addActionListener(e -> toggleTrack(rec));
        track.setEnabled(p != null);
        JButton refresh = actionButton("↻ Refresh progress", BORDER);
        refresh.setToolTipText("Re-check these steps against your latest synced data (doesn't generate new advice)");
        refresh.addActionListener(e -> refreshProgress());
        detailContent.add(buttonPair(track, refresh));
        detailContent.add(RuneAlyticsUi.vSpace(6));
        JButton full = actionButton("View full analysis ↗", BORDER);
        full.addActionListener(e -> openWebsite());
        detailContent.add(fullWidth(full));
        if (rec.linkUrl != null)
        {
            detailContent.add(RuneAlyticsUi.vSpace(6));
            JButton link = actionButton((rec.linkLabel != null ? rec.linkLabel : "Open") + " ↗", BORDER);
            final String url = rec.linkUrl;
            link.addActionListener(e -> LinkBrowser.browse(url));
            detailContent.add(fullWidth(link));
        }
        if (coverage != null && !coverage.items.isEmpty())
        {
            detailContent.add(RuneAlyticsUi.vSpace(8));
            detailContent.add(sectionTitle("Data coverage"));
            detailContent.add(coveragePills(coverage));
            String sync = ago(coverage.syncedAt);
            detailContent.add(text(sync != null ? "Latest sync received " + sync + "." : "No synced data yet.", META, MUTED));
        }
        detailContent.revalidate();
        detailContent.repaint();
    }

    private JComponent stepRow(Step s)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(2, 0, 2, 0));
        JComponent lead;
        if (s.ackable)
        {
            JCheckBox box = new JCheckBox();
            box.setOpaque(false);
            box.setSelected(s.isDone());
            box.setToolTipText("A planning check you tick yourself — it never marks game progress done");
            box.addActionListener(e -> ackStep(s, box.isSelected()));
            lead = box;
        }
        else
        {
            JLabel ic = new JLabel(stepGlyph(s));
            ic.setFont(BOLD12);
            ic.setForeground(stepColor(s));
            ic.setPreferredSize(new Dimension(18, 16));
            ic.setToolTipText(sourceText(s));
            lead = ic;
        }
        JPanel body = vertical();
        body.add(text(s.label, s.isDone() ? PLAIN11 : BOLD11, s.isDone() ? MUTED : TEXT));
        String meta = sourceText(s) + (s.detail != null && !s.detail.isEmpty() ? " · " + s.detail : "");
        body.add(text(meta, META, "blocked".equals(s.state) ? ERROR : MUTED));
        row.add(lead, BorderLayout.WEST);
        row.add(body, BorderLayout.CENTER);
        return row;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Recommendation card (updated in place)
    // ═════════════════════════════════════════════════════════════════════════

    private final class RecCard extends JPanel
    {
        private Recommendation rec;
        private final JLabel icon = new JLabel();
        private final JTextArea title = text("", TITLE, TEXT);
        private final JButton newBadge = new JButton("NEW");
        private final JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JTextArea next = text("", PLAIN11, TEXT);
        private final JLabel progressLabel = new JLabel();
        private final ThinBar bar = new ThinBar(0, 0);
        private final JButton view = actionButton("View plan ›", BORDER);
        private final JButton track = actionButton("☆", BORDER);

        RecCard(Recommendation r)
        {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(LEFT_ALIGNMENT);
            setBackground(CARD_BG);

            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setOpaque(false);
            top.setAlignmentX(LEFT_ALIGNMENT);
            icon.setFont(ICON_FONT);
            icon.setVerticalAlignment(SwingConstants.TOP);
            JPanel titleCol = vertical();
            titleCol.add(title);
            titleCol.add(RuneAlyticsUi.vSpace(3));
            badgeRow.setOpaque(false);
            badgeRow.setAlignmentX(LEFT_ALIGNMENT);
            titleCol.add(badgeRow);
            newBadge.setFont(META);
            newBadge.setForeground(GREEN);
            newBadge.setBackground(NEW_BG);
            newBadge.setFocusPainted(false);
            newBadge.setBorder(new CompoundBorder(new LineBorder(GREEN, 1, true), new EmptyBorder(1, 4, 1, 4)));
            newBadge.setToolTipText("New since your last plan — click to mark as read");
            newBadge.addActionListener(e -> acknowledgeNew(rec));
            top.add(icon, BorderLayout.WEST);
            top.add(titleCol, BorderLayout.CENTER);
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            east.setOpaque(false);
            east.add(newBadge);
            top.add(east, BorderLayout.EAST);
            add(top);
            add(RuneAlyticsUi.vSpace(4));
            add(next);
            add(RuneAlyticsUi.vSpace(3));

            JPanel prog = new JPanel(new BorderLayout(6, 0));
            prog.setOpaque(false);
            prog.setAlignmentX(LEFT_ALIGNMENT);
            progressLabel.setFont(PLAIN11);
            progressLabel.setForeground(MUTED);
            prog.add(progressLabel, BorderLayout.WEST);
            prog.add(bar, BorderLayout.CENTER);
            limitHeight(prog);
            add(prog);
            add(RuneAlyticsUi.vSpace(6));

            JPanel actions = new JPanel(new BorderLayout(6, 0));
            actions.setOpaque(false);
            actions.setAlignmentX(LEFT_ALIGNMENT);
            view.addActionListener(e -> openDetail(rec));
            track.setPreferredSize(new Dimension(34, 26));
            track.addActionListener(e -> toggleTrack(rec));
            actions.add(view, BorderLayout.CENTER);
            actions.add(track, BorderLayout.EAST);
            actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            add(actions);

            update(r);
        }

        /** Refresh this card's content in place (progress refresh, tracking, read state). */
        void update(Recommendation r)
        {
            rec = r;
            icon.setText(categoryIcon(r.category));
            title.setText(r.title);
            badgeRow.removeAll();
            for (Component c : badges(r).getComponents())
            {
                badgeRow.add(c);
            }
            Progress p = r.progress;
            Step s = p != null ? p.nextStep() : null;
            next.setText(s != null ? "Next step: " + s.label : (r.summary != null ? r.summary : ""));
            next.setVisible(!next.getText().isEmpty());
            progressLabel.setText(p != null ? "Progress: " + p.done + "/" + p.total : "Progress: —");
            bar.set(p != null ? p.done : 0, p != null ? p.total : 0);
            boolean tracked = p != null && p.tracked;
            track.setText(tracked ? "★" : "☆");
            track.setForeground(tracked ? GOLD : TEXT);
            track.setToolTipText(tracked ? "Tracking — click to stop" : "Track this plan");
            track.setEnabled(p != null);
            boolean isNew = isUnread(r);
            newBadge.setVisible(isNew);
            setBackground(isNew ? NEW_BG : CARD_BG);
            setBorder(new CompoundBorder(new LineBorder(isNew ? GREEN : BORDER, 1), new EmptyBorder(8, 8, 8, 8)));
            badgeRow.revalidate();
            revalidate();
            repaint();
        }

        @Override
        public Dimension getMaximumSize()
        {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NEW / read state (separate from completion and selection)
    // ═════════════════════════════════════════════════════════════════════════

    private boolean isUnread(Recommendation r)
    {
        return r.isNew && plan != null && !seen.contains(plan.id + ":" + r.activityId);
    }

    private void acknowledgeNew(Recommendation r)
    {
        if (plan == null || !r.isNew)
        {
            return;
        }
        if (seen.add(plan.id + ":" + r.activityId))
        {
            saveSeen();
            RecCard c = cards.get(r.activityId);
            if (c != null)
            {
                c.update(c.rec);
            }
        }
    }

    private Set<String> loadSeen(String acct)
    {
        Set<String> out = new LinkedHashSet<>();
        String raw = configManager.getConfiguration(CONFIG_GROUP, SEEN_KEY_PREFIX + acct.replace(' ', '_'));
        if (raw != null && !raw.isEmpty())
        {
            out.addAll(Arrays.asList(raw.split(",")));
        }
        return out;
    }

    private void saveSeen()
    {
        if (account == null)
        {
            return;
        }
        List<String> list = new ArrayList<>(seen);
        if (list.size() > SEEN_MAX)
        {
            list = list.subList(list.size() - SEEN_MAX, list.size());   // bounded: keep the newest
            seen = new LinkedHashSet<>(list);
        }
        final String key = SEEN_KEY_PREFIX + account.replace(' ', '_');
        final String value = String.join(",", list);
        executor.execute(() -> configManager.setConfiguration(CONFIG_GROUP, key, value));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers (package-private ones are unit tested)
    // ═════════════════════════════════════════════════════════════════════════

    private Recommendation find(String key)
    {
        if (plan == null || key == null)
        {
            return null;
        }
        for (Recommendation r : plan.recommendations)
        {
            if (key.equals(r.key))
            {
                return r;
            }
        }
        return null;
    }

    private Recommendation findByActivity(String activityId)
    {
        if (plan == null || activityId == null)
        {
            return null;
        }
        for (Recommendation r : plan.recommendations)
        {
            if (activityId.equals(r.activityId))
            {
                return r;
            }
        }
        return null;
    }

    private JPanel badges(Recommendation r)
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        String pr = r.priority != null ? r.priority : "medium";
        row.add(pill(cap(pr), priorityColor(pr), TEXT));
        if (r.category != null)
        {
            row.add(pill(categoryLabel(r.category), new Color(55, 55, 60), MUTED));
        }
        if (r.progress != null && r.progress.ready)
        {
            row.add(pill("Ready now", new Color(30, 90, 50), TEXT));
        }
        if (r.pvpRisk)
        {
            row.add(pill("PvP risk", new Color(110, 40, 40), TEXT));
        }
        if (r.durationMinutes != null && r.durationMinutes > 0)
        {
            row.add(pill("~" + duration(r.durationMinutes), new Color(48, 48, 52), MUTED));
        }
        return row;
    }

    private JPanel coveragePills(Coverage c)
    {
        JPanel row = new JPanel(new WrapFlow(4, 3));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        for (CoverageItem i : c.items)
        {
            String glyph = "ok".equals(i.state) ? "✓ " : ("stale".equals(i.state) ? "! " : "– ");
            Color fg = "ok".equals(i.state) ? GREEN : ("stale".equals(i.state) ? AMBER : MUTED);
            row.add(pill(glyph + i.label, RAISED, fg));
        }
        return row;
    }

    private JPanel stageRow(String glyph, Color color, String label, boolean done)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel g = new JLabel(glyph);
        g.setFont(BOLD12);
        g.setForeground(color);
        g.setPreferredSize(new Dimension(16, 16));
        JLabel l = new JLabel(label);
        l.setFont(done ? PLAIN12 : BOLD12);
        l.setForeground(TEXT);
        row.add(g, BorderLayout.WEST);
        row.add(l, BorderLayout.CENTER);
        limitHeight(row);
        return row;
    }

    static String stepGlyph(Step s)
    {
        if ("done".equals(s.state))
        {
            return "reported".equals(s.source) ? "↺" : "✔";
        }
        if ("blocked".equals(s.state))
        {
            return "✕";
        }
        if ("unknown".equals(s.state))
        {
            return "?";
        }
        return "○";
    }

    private static Color stepColor(Step s)
    {
        switch (s.state == null ? "" : s.state)
        {
            case "done": return GREEN;
            case "blocked": return ERROR;
            case "unknown": return AMBER;
            default: return MUTED;
        }
    }

    /** Where a step's state comes from — observed, ticked by you, reported by you, or not known. */
    static String sourceText(Step s)
    {
        if ("blocked".equals(s.state))
        {
            return "Blocked";
        }
        if ("observed".equals(s.source))
        {
            return "done".equals(s.state) ? "Synced from your account" : "Checked from your synced data";
        }
        if ("manual".equals(s.source))
        {
            return "Ticked by you";
        }
        if ("reported".equals(s.source))
        {
            return "Reported by you on the website";
        }
        if ("unknown".equals(s.state))
        {
            return "Not known yet";
        }
        return s.ackable ? "Tick when you've done it" : "To do";
    }

    static String legend()
    {
        return "✔ synced · ↺ reported by you · ☑ ticked by you · ? not known · ✕ blocked";
    }

    static String categoryIcon(String category)
    {
        if (category == null)
        {
            return "🧭";
        }
        switch (category)
        {
            case "questing": return "📜";
            case "bossing": return "⚔";
            case "skilling": return "⛏";
            case "money": return "💰";
            case "collection_log": return "📖";
            case "preparation": return "🧭";
            default: return "✦";
        }
    }

    static String categoryLabel(String category)
    {
        if ("collection_log".equals(category))
        {
            return "Collection log";
        }
        if ("money".equals(category))
        {
            return "Money";
        }
        return cap(category);
    }

    private static Color priorityColor(String p)
    {
        switch (p)
        {
            case "high": return new Color(120, 50, 40);     // restrained red/amber
            case "low": return new Color(50, 60, 75);       // muted blue/gray
            default: return new Color(105, 85, 30);         // gold
        }
    }

    static String duration(int minutes)
    {
        if (minutes < 60)
        {
            return minutes + " min";
        }
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** "just now", "5m ago", "3h ago", "2d ago"; null when unknown. */
    static String ago(String iso)
    {
        if (iso == null)
        {
            return null;
        }
        try
        {
            long s = Duration.between(OffsetDateTime.parse(iso).toInstant(), Instant.now()).getSeconds();
            if (s < 60)
            {
                return "just now";
            }
            if (s < 3600)
            {
                return (s / 60) + "m ago";
            }
            if (s < 86400)
            {
                return (s / 3600) + "h ago";
            }
            return (s / 86400) + "d ago";
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static String cap(String s)
    {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
    }

    /** A wrapping, transparent, read-only text block that follows the available width. */
    private static JTextArea text(String s, Font font, Color color)
    {
        JTextArea t = new WrapText(s);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setFont(font);
        t.setForeground(color);
        t.setAlignmentX(LEFT_ALIGNMENT);
        return t;
    }

    private static JTextArea muted(String s)
    {
        JTextArea t = text(s, PLAIN11, MUTED);
        t.setBorder(new EmptyBorder(8, 2, 8, 2));
        return t;
    }

    private static JLabel sectionTitle(String s)
    {
        JLabel l = new JLabel(s);
        l.setFont(new Font("Calibri", Font.BOLD, 13));
        l.setForeground(GOLD);
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(0, 0, 4, 0));
        return l;
    }

    private static JLabel valueLabel()
    {
        JLabel l = new JLabel("—");
        l.setFont(BOLD12);
        l.setForeground(TEXT);
        return l;
    }

    private static JLabel pill(String s, Color bg, Color fg)
    {
        JLabel l = new JLabel(s);
        l.setFont(META);
        l.setForeground(fg);
        l.setOpaque(true);
        l.setBackground(bg);
        l.setBorder(new CompoundBorder(new LineBorder(bg.brighter(), 1, true), new EmptyBorder(0, 4, 0, 4)));
        return l;
    }

    private static JPanel card(Color border)
    {
        JPanel p = vertical();
        p.setOpaque(true);
        p.setBackground(CARD_BG);
        p.setBorder(new CompoundBorder(new LineBorder(border, 1), new EmptyBorder(8, 8, 8, 8)));
        return p;
    }

    private static JPanel vertical()
    {
        JPanel p = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JComponent separator()
    {
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(55, 55, 55));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(LEFT_ALIGNMENT);
        return sep;
    }

    private static JComponent fullWidth(JButton b)
    {
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return b;
    }

    private static void limitHeight(JComponent c)
    {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
    }

    private static JButton actionButton(String label, Color border)
    {
        JButton b = new JButton(label);
        b.setFont(BOLD11);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBackground(new Color(45, 45, 48));
        b.setForeground(TEXT);
        b.setBorder(new CompoundBorder(new LineBorder(border, 1, true), new EmptyBorder(5, 6, 5, 6)));
        return b;
    }

    private static JButton iconButton(String glyph, String tooltip)
    {
        JButton b = new JButton(glyph);
        b.setFont(new Font("Dialog", Font.PLAIN, 13));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setForeground(TEXT);
        b.setBorder(new EmptyBorder(2, 4, 2, 4));
        b.setToolTipText(tooltip);
        b.getAccessibleContext().setAccessibleName(tooltip);
        return b;
    }

    private static JButton filterPill(String label)
    {
        JButton b = new JButton(label);
        b.setFont(BOLD11);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static void stylePill(JButton b, boolean active)
    {
        b.setBackground(active ? new Color(30, 70, 40) : FILTER_INACTIVE);
        b.setForeground(active ? Color.WHITE : new Color(160, 160, 160));
        b.setBorder(new CompoundBorder(new LineBorder(active ? GREEN : FILTER_BORDER, 1, true), new EmptyBorder(2, 7, 2, 7)));
    }

    private static JScrollPane scroll(JComponent content)
    {
        JScrollPane s = new JScrollPane(content);
        s.setBorder(null);
        s.getViewport().setBackground(BG);
        s.getVerticalScrollBar().setUnitIncrement(16);
        s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        s.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        s.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        return s;
    }

    @Override
    public void onDataRefresh()
    {
        refreshProgress();
    }

    /**
     * JTextArea measures its wrapped height from its current width, which is 0
     * on the first layout pass. Re-validate whenever the width really changes so
     * long titles and explanations get their full height (no clipping).
     */
    private static final class WrapText extends JTextArea
    {
        WrapText(String s)
        {
            super(s);
            // Never let caret moves (setText) scroll the list: keep the reader's position.
            ((javax.swing.text.DefaultCaret) getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        }

        /** Read-only text never needs scrolling into view; ignoring this keeps the list where the reader left it. */
        @Override
        public void scrollRectToVisible(Rectangle r)
        {
        }

        @Override
        public void setBounds(int x, int y, int w, int h)
        {
            boolean widthChanged = w != getWidth();
            super.setBounds(x, y, w, h);
            if (widthChanged && w > 0)
            {
                SwingUtilities.invokeLater(() -> {
                    invalidate();
                    Container top = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
                    (top != null ? top : getParent()).revalidate();
                });
            }
        }

        @Override
        public Dimension getPreferredSize()
        {
            Dimension d = super.getPreferredSize();
            int w = getWidth();
            if (w > 0 && getUI() != null)
            {
                // Height of the text wrapped at the current width; never wider than we have.
                Insets in = getInsets();
                javax.swing.text.View v = getUI().getRootView(this);
                v.setSize(Math.max(1, w - in.left - in.right), Float.MAX_VALUE);
                int h = (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + in.top + in.bottom;
                d = new Dimension(w, h);
            }
            return d;
        }

        @Override
        public Dimension getMaximumSize()
        {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Two buttons side by side when both labels fit, stacked when the sidebar is too narrow. */
    private static JPanel buttonPair(JButton a, JButton b)
    {
        JPanel p = new JPanel()
        {
            // Widest label each button has shown (seeded with the initial, longest labels),
            // so "Generating…" doesn't flip the layout.
            private int maxA = a.getPreferredSize().width;
            private int maxB = b.getPreferredSize().width;

            private boolean sideBySide(int w)
            {
                if (w <= 0)
                {
                    return false;
                }
                maxA = Math.max(maxA, a.getPreferredSize().width);
                maxB = Math.max(maxB, b.getPreferredSize().width);
                int half = (w - 6) / 2;
                return maxA <= half && maxB <= half;
            }

            @Override
            public void doLayout()
            {
                int w = getWidth();
                int gap = 6;
                Dimension da = a.getPreferredSize();
                Dimension db = b.getPreferredSize();
                int half = (w - gap) / 2;
                if (sideBySide(w))
                {
                    int h = Math.max(da.height, db.height);
                    a.setBounds(0, 0, half, h);
                    b.setBounds(half + gap, 0, w - half - gap, h);
                }
                else
                {
                    a.setBounds(0, 0, w, da.height);
                    b.setBounds(0, da.height + 4, w, db.height);
                }
            }

            @Override
            public Dimension getPreferredSize()
            {
                Dimension da = a.getPreferredSize();
                Dimension db = b.getPreferredSize();
                int w = getWidth();
                if (w <= 0 && getParent() != null)
                {
                    Insets in = getParent().getInsets();
                    w = getParent().getWidth() - in.left - in.right;    // not laid out yet: use the space we'll get
                }
                boolean side = sideBySide(w);
                w = Math.max(w, 0);
                return new Dimension(w, side ? Math.max(da.height, db.height) : da.height + db.height + 4);
            }

            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(null);
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(a);
        p.add(b);
        return p;
    }

    /** 6 px progress bar. */
    private static final class ThinBar extends JComponent
    {
        private int done;
        private int total;

        ThinBar(int done, int total)
        {
            set(done, total);
            setPreferredSize(new Dimension(40, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        }

        void set(int d, int t)
        {
            done = d;
            total = t;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int y = Math.max(0, (getHeight() - 6) / 2);
            g2.setColor(new Color(60, 60, 64));
            g2.fillRoundRect(0, y, getWidth(), 6, 6, 6);
            if (total > 0 && done > 0)
            {
                g2.setColor(GREEN);
                g2.fillRoundRect(0, y, Math.max(6, (int) (getWidth() * Math.min(1.0, done / (double) total))), 6, 6, 6);
            }
            g2.dispose();
        }
    }

    /** FlowLayout that wraps to the container width (for pills). */
    private static final class WrapFlow extends FlowLayout
    {
        WrapFlow(int hgap, int vgap)
        {
            super(FlowLayout.LEFT, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target)
        {
            synchronized (target.getTreeLock())
            {
                int width = target.getWidth() > 0 ? target.getWidth() : 200;
                Insets in = target.getInsets();
                int max = width - in.left - in.right - getHgap() * 2;
                int x = 0;
                int rowH = 0;
                int h = 0;
                for (Component c : target.getComponents())
                {
                    if (!c.isVisible())
                    {
                        continue;
                    }
                    Dimension d = c.getPreferredSize();
                    if (x > 0 && x + d.width > max)
                    {
                        h += rowH + getVgap();
                        x = 0;
                        rowH = 0;
                    }
                    x += d.width + getHgap();
                    rowH = Math.max(rowH, d.height);
                }
                h += rowH + getVgap() * 2 + in.top + in.bottom;
                return new Dimension(width, h);
            }
        }
    }

    /** Matches the viewport width (no horizontal scrolling), keeps its natural height (scrolls). */
    private static final class ScrollableContentPanel extends JPanel implements Scrollable
    {
        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(16, visibleRect.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }
}
