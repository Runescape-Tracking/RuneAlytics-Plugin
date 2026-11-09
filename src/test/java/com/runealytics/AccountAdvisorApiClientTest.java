package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Advisor tab's parsing of the website payloads, and its display helpers. */
public class AccountAdvisorApiClientTest
{
    private static final Gson GSON = new Gson();

    private static final String OVERVIEW = "{"
            + "\"account\":{\"id\":3,\"username\":\"rune alytics\",\"verified\":true},"
            + "\"horizon\":\"today\",\"focus_options\":[\"questing\",\"money\",\"bossing\",\"skilling\",\"collection_log\"],\"focus\":[],"
            + "\"default_minutes\":60,"
            + "\"summary\":{\"active_goals\":3,\"steps_done\":2,\"steps_total\":9,\"recommendations\":2,\"updated_at\":\"2026-11-08T10:00:00+00:00\"},"
            + "\"coverage\":{\"synced_at\":\"2026-11-08T09:58:00+00:00\",\"items\":["
            + "{\"key\":\"stats\",\"state\":\"ok\",\"label\":\"Stats synced\"},{\"key\":\"bank\",\"state\":\"missing\",\"label\":\"Bank unavailable\"}]},"
            + "\"plan\":{\"id\":12,\"status\":\"ready\",\"stage\":\"ready\",\"horizon\":\"today\",\"origin\":\"plugin\",\"stale\":false,"
            + "\"generated_at\":\"2026-11-08T10:00:00+00:00\",\"summary\":{\"headline\":\"Your 60-minute plan\",\"text\":\"x\"},"
            + "\"recommendations\":[{"
            + "\"key\":\"p12r0\",\"activity_id\":\"osrs.quest.the_grand_tree\",\"title\":\"Complete The Grand Tree\",\"summary\":\"Gnome quest.\","
            + "\"why\":\"Tied to your quest goal.\",\"status\":\"conditional\",\"category\":\"questing\",\"priority\":\"high\","
            + "\"duration_minutes\":90,\"pvp_risk\":false,\"is_new\":true,\"link\":{\"label\":\"Quest guide\",\"url\":\"https://x\"},"
            + "\"progress\":{\"done\":2,\"total\":4,\"basis\":\"live\"},\"ready\":false,\"tracked\":true,\"steps\":["
            + "{\"id\":\"osrs.quest.the_grand_tree#skill:agility\",\"kind\":\"requirement\",\"label\":\"Agility 25\",\"state\":\"done\",\"source\":\"observed\",\"detail\":null,\"ackable\":false},"
            + "{\"id\":\"osrs.quest.the_grand_tree#membership:membership\",\"kind\":\"requirement\",\"label\":\"Membership\","
            + "\"state\":\"unknown\",\"source\":null,\"detail\":\"Membership isn't recorded.\",\"ackable\":false},"
            + "{\"id\":\"osrs.quest.the_grand_tree#check:bring_food\",\"kind\":\"check\",\"label\":\"Check: Bring food\",\"state\":\"done\",\"source\":\"manual\",\"detail\":null,\"ackable\":true},"
            + "{\"id\":\"osrs.quest.the_grand_tree#complete\",\"kind\":\"goal\",\"label\":\"Complete The Grand Tree\",\"state\":\"todo\",\"source\":null,\"detail\":null,\"ackable\":false}"
            + "]},{\"key\":\"p12r1\",\"title\":\"No id here\"},{\"title\":null,\"key\":\"p12r2\"}],"
            + "\"missing_data\":[\"No bank has been synced.\"],\"web_url\":\"https://runealytics.com/account-advisor?account=3&plan=12\"},"
            + "\"regenerating\":null,\"web_url\":\"https://runealytics.com/account-advisor?account=3\"}";

    @Test
    public void parsesTheOverviewTheWebsiteSends() throws Exception
    {
        AccountAdvisorApiClient.Overview o = AccountAdvisorApiClient.parseOverview(GSON.fromJson(OVERVIEW, JsonObject.class));
        assertEquals("rune alytics", o.accountName);
        assertEquals(5, o.focusOptions.size());
        assertEquals(Integer.valueOf(3), o.summary.activeGoals);
        assertEquals(Integer.valueOf(9), o.summary.stepsTotal);
        assertEquals("2026-11-08T09:58:00+00:00", o.coverage.syncedAt);
        assertEquals("Bank unavailable", o.coverage.items.get(1).label);
        assertNull(o.regeneratingId);

        AccountAdvisorApiClient.Plan p = o.plan;
        assertTrue(p.isReady());
        assertEquals("entries without a title are skipped", 2, p.recommendations.size());
        AccountAdvisorApiClient.Recommendation r = p.recommendations.get(0);
        assertEquals("osrs.quest.the_grand_tree", r.activityId);
        assertEquals("high", r.priority);
        assertTrue(r.isNew);
        assertEquals(2, r.progress.done);
        assertTrue(r.progress.tracked);
        assertFalse(r.progress.ready);
        assertEquals("next step skips done ones", "Membership", r.progress.nextStep().label);
        assertTrue(r.progress.steps.get(2).ackable);
        assertNull("a recommendation without progress stays null", p.recommendations.get(1).progress);
    }

    @Test
    public void unknownSummaryStaysNullNotZero()
    {
        AccountAdvisorApiClient.Summary s = AccountAdvisorApiClient.parseSummary(GSON.fromJson(
                "{\"active_goals\":0,\"steps_done\":null,\"steps_total\":null,\"recommendations\":null,\"updated_at\":null}", JsonObject.class));
        assertEquals(Integer.valueOf(0), s.activeGoals);
        assertNull(s.stepsDone);
        assertNull(s.recommendations);
        assertNull(AccountAdvisorApiClient.parseSummary(null).activeGoals);
    }

    @Test
    public void progressUpdateReplacesOnlyThatRecommendation() throws Exception
    {
        AccountAdvisorApiClient.Plan p = AccountAdvisorApiClient.parseOverview(GSON.fromJson(OVERVIEW, JsonObject.class)).plan;
        AccountAdvisorApiClient.ProgressUpdate u = AccountAdvisorApiClient.parseProgressUpdate(GSON.fromJson(
                "{\"plan_id\":12,\"recommendations\":{\"p12r0\":{\"activity_id\":\"osrs.quest.the_grand_tree\",\"progress\":{\"done\":3,\"total\":4},"
                        + "\"ready\":false,\"tracked\":false,\"steps\":[]}},\"summary\":{\"steps_done\":3,\"steps_total\":9},\"coverage\":{\"items\":[]}}",
                JsonObject.class));
        assertEquals(12, u.planId);
        AccountAdvisorApiClient.Plan updated = p.withProgress(u.byKey);
        assertEquals(3, updated.recommendations.get(0).progress.done);
        assertFalse(updated.recommendations.get(0).progress.tracked);
        assertEquals("same title / identity", p.recommendations.get(0).activityId, updated.recommendations.get(0).activityId);
        assertNull(updated.recommendations.get(1).progress);
        assertEquals("the original snapshot is untouched", 2, p.recommendations.get(0).progress.done);
    }

    @Test
    public void pendingAndFailedPlans() throws Exception
    {
        AccountAdvisorApiClient.Plan pending = AccountAdvisorApiClient.parsePlan(GSON.fromJson(
                "{\"id\":5,\"status\":\"pending\",\"stage\":\"building\",\"recommendations\":[]}", JsonObject.class));
        assertTrue(pending.isPending());
        assertEquals("building", pending.stage);
        AccountAdvisorApiClient.Plan failed = AccountAdvisorApiClient.parsePlan(GSON.fromJson(
                "{\"id\":6,\"status\":\"failed\",\"stage\":\"failed\",\"error\":\"Try again.\"}", JsonObject.class));
        assertTrue(failed.isFailed());
        assertEquals("Try again.", failed.error);
    }

    @Test
    public void displayHelpersDistinguishWhereProgressCameFrom() throws Exception
    {
        AccountAdvisorApiClient.Plan p = AccountAdvisorApiClient.parseOverview(GSON.fromJson(OVERVIEW, JsonObject.class)).plan;
        java.util.List<AccountAdvisorApiClient.Step> s = p.recommendations.get(0).progress.steps;
        assertEquals("Synced from your account", AccountAdvisorPanel.sourceText(s.get(0)));
        assertEquals("Not known yet", AccountAdvisorPanel.sourceText(s.get(1)));
        assertEquals("Ticked by you", AccountAdvisorPanel.sourceText(s.get(2)));
        assertEquals("✔", AccountAdvisorPanel.stepGlyph(s.get(0)));
        assertEquals("?", AccountAdvisorPanel.stepGlyph(s.get(1)));
        assertEquals("○", AccountAdvisorPanel.stepGlyph(s.get(3)));
        assertEquals("1 h 30 min", AccountAdvisorPanel.duration(90));
        assertEquals("Collection log", AccountAdvisorPanel.categoryLabel("collection_log"));
        assertNotNull(AccountAdvisorPanel.categoryIcon(null));
        assertNull(AccountAdvisorPanel.ago(null));
        assertNull(AccountAdvisorPanel.ago("garbage"));
    }

    @Test
    public void websiteLinkUsesTheOsrsUsername()
    {
        assertEquals("https://runealytics.com/account-advisor?account=rune%20alytics&plan=39",
                AccountAdvisorApiClient.buildWebsiteUrl("https://runealytics.com/api", "rune alytics", 39));
        assertEquals("https://runealytics.com/account-advisor?account=rune%20alytics",
                AccountAdvisorApiClient.buildWebsiteUrl("https://runealytics.com/api/", "rune alytics", null));
        assertEquals("http://localhost:8000/account-advisor?account=Iron%20Guy&plan=2",
                AccountAdvisorApiClient.buildWebsiteUrl("http://localhost:8000/api", "Iron Guy", 2));
        assertNull(AccountAdvisorApiClient.buildWebsiteUrl("https://runealytics.com/api", null, 39));
        assertNull(AccountAdvisorApiClient.buildWebsiteUrl("https://runealytics.com/api", "  ", 39));
    }

    @Test
    public void linkWarningNamesTheSite()
    {
        assertEquals("oldschool.runescape.wiki", RuneAlyticsUi.siteNameOf("https://oldschool.runescape.wiki/w/Priest_in_Peril"));
        assertEquals("runealytics.com", RuneAlyticsUi.siteNameOf("https://www.runealytics.com/x"));
    }
}
