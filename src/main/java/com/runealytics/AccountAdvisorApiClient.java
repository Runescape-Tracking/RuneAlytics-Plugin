package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.runealytics.RuneAlyticsHttp.JSON;

/**
 * RuneAlytics Account Advisor API for the Advisor tab (website:
 * App\Modules\AccountAdvisor\Http\Controllers\PluginAdvisorController).
 *
 *   GET  {apiUrl}/plugin/advisor?horizon=               overview (account, summary, coverage, latest plan)
 *   POST {apiUrl}/plugin/advisor/plans                  generate (an identical pending plan is reused)
 *   GET  {apiUrl}/plugin/advisor/plans/{id}             plan (poll while "building")
 *   GET  {apiUrl}/plugin/advisor/plans/{id}/progress    steps re-checked against current synced data
 *   POST {apiUrl}/plugin/advisor/track                  {activity_id, tracked}
 *   POST {apiUrl}/plugin/advisor/steps/ack              {plan_id, step_id, done} (planning checks only)
 *
 * Authenticated with the verified account's plugin token (the same Bearer
 * token every other plugin endpoint uses). Every call blocks: run it on the
 * plugin executor, never on the client thread or the EDT. Results are parsed
 * into snapshot objects that are not modified after parsing.
 */
@Singleton
public class AccountAdvisorApiClient
{
    private final OkHttpClient      httpClient;
    private final Gson              gson;
    private final RunealyticsConfig config;
    private final RuneAlyticsState  state;

    @Inject
    public AccountAdvisorApiClient(OkHttpClient httpClient, RunealyticsConfig config, RuneAlyticsState state, Gson gson)
    {
        this.config = config;
        this.state  = state;
        this.gson   = gson;
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Snapshots
    // ═════════════════════════════════════════════════════════════════════════

    /** Header numbers. Null = unknown (shown as "—"), never replaced by 0. */
    public static final class Summary
    {
        public final Integer activeGoals;
        public final Integer stepsDone;
        public final Integer stepsTotal;
        public final Integer recommendations;
        public final String  updatedAt;

        Summary(Integer activeGoals, Integer stepsDone, Integer stepsTotal, Integer recommendations, String updatedAt)
        {
            this.activeGoals = activeGoals;
            this.stepsDone = stepsDone;
            this.stepsTotal = stepsTotal;
            this.recommendations = recommendations;
            this.updatedAt = updatedAt;
        }
    }

    /** One input the advisor used: key stats|quests|bank|budget|goals, state ok|stale|missing. */
    public static final class CoverageItem
    {
        public final String key;
        public final String state;
        public final String label;

        CoverageItem(String key, String state, String label)
        {
            this.key = key;
            this.state = state;
            this.label = label;
        }
    }

    public static final class Coverage
    {
        public final String syncedAt;                 // newest of stats / bank sync; null if never
        public final List<CoverageItem> items;

        Coverage(String syncedAt, List<CoverageItem> items)
        {
            this.syncedAt = syncedAt;
            this.items = Collections.unmodifiableList(items);
        }
    }

    /**
     * kind: requirement | check | goal; state: done | todo | blocked | unknown;
     * source: observed (synced data) | reported (owner's report on the site) |
     * manual (ticked planning check) | null (not known). Only ackable steps
     * (planning checks) can be ticked by hand.
     */
    public static final class Step
    {
        public final String id;
        public final String kind;
        public final String label;
        public final String state;
        public final String source;
        public final String detail;
        public final boolean ackable;

        Step(String id, String kind, String label, String state, String source, String detail, boolean ackable)
        {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.state = state;
            this.source = source;
            this.detail = detail;
            this.ackable = ackable;
        }

        public boolean isDone()
        {
            return "done".equals(state);
        }
    }

    /** Per-recommendation progress (also what a progress refresh returns). */
    public static final class Progress
    {
        public final String activityId;
        public final int done;
        public final int total;
        public final boolean ready;
        public final boolean tracked;
        public final List<Step> steps;

        Progress(String activityId, int done, int total, boolean ready, boolean tracked, List<Step> steps)
        {
            this.activityId = activityId;
            this.done = done;
            this.total = total;
            this.ready = ready;
            this.tracked = tracked;
            this.steps = Collections.unmodifiableList(steps);
        }

        /** The first step that still needs doing, for "Next step:". */
        public Step nextStep()
        {
            for (Step s : steps)
            {
                if (!s.isDone())
                {
                    return s;
                }
            }
            return null;
        }

        Progress withTracked(boolean t)
        {
            return new Progress(activityId, done, total, ready, t, steps);
        }
    }

    public static final class Recommendation
    {
        public final String key;          // plan-specific (p{plan}r{n})
        public final String activityId;   // stable identity across plans
        public final String title;
        public final String summary;
        public final String why;
        public final String status;       // eligible | conditional | blocked
        public final String category;
        public final String priority;     // high | medium | low (derived on the website)
        public final Integer durationMinutes;
        public final boolean pvpRisk;
        public final boolean isNew;       // not in this account's previous plan
        public final String linkLabel;
        public final String linkUrl;
        public final Progress progress;   // null when the plan isn't ready

        Recommendation(String key, String activityId, String title, String summary, String why, String status, String category,
                       String priority, Integer durationMinutes, boolean pvpRisk, boolean isNew, String linkLabel, String linkUrl, Progress progress)
        {
            this.key = key;
            this.activityId = activityId;
            this.title = title;
            this.summary = summary;
            this.why = why;
            this.status = status;
            this.category = category;
            this.priority = priority;
            this.durationMinutes = durationMinutes;
            this.pvpRisk = pvpRisk;
            this.isNew = isNew;
            this.linkLabel = linkLabel;
            this.linkUrl = linkUrl;
            this.progress = progress;
        }

        Recommendation withProgress(Progress p)
        {
            return new Recommendation(key, activityId, title, summary, why, status, category, priority, durationMinutes,
                    pvpRisk, isNew, linkLabel, linkUrl, p);
        }
    }

    public static final class Plan
    {
        public final int id;
        public final String status;       // pending | ready | failed
        public final String stage;        // building | ready | failed (what the website reports)
        public final String horizon;
        public final String origin;
        public final boolean stale;
        public final String error;
        public final String generatedAt;
        public final String headline;
        public final String summaryText;
        public final String webUrl;
        public final List<Recommendation> recommendations;
        public final List<String> missingData;

        Plan(int id, String status, String stage, String horizon, String origin, boolean stale, String error, String generatedAt,
             String headline, String summaryText, String webUrl, List<Recommendation> recommendations, List<String> missingData)
        {
            this.id = id;
            this.status = status;
            this.stage = stage;
            this.horizon = horizon;
            this.origin = origin;
            this.stale = stale;
            this.error = error;
            this.generatedAt = generatedAt;
            this.headline = headline;
            this.summaryText = summaryText;
            this.webUrl = webUrl;
            this.recommendations = Collections.unmodifiableList(recommendations);
            this.missingData = Collections.unmodifiableList(missingData);
        }

        public boolean isPending() { return "pending".equals(status); }
        public boolean isFailed()  { return "failed".equals(status); }
        public boolean isReady()   { return "ready".equals(status); }

        /** A copy with progress replaced by activity id (targeted card updates). */
        Plan withProgress(Map<String, Progress> byKey)
        {
            List<Recommendation> recs = new ArrayList<>();
            for (Recommendation r : recommendations)
            {
                Progress p = byKey.get(r.key);
                recs.add(p != null ? r.withProgress(p) : r);
            }
            return new Plan(id, status, stage, horizon, origin, stale, error, generatedAt, headline, summaryText, webUrl, recs, missingData);
        }
    }

    public static final class Overview
    {
        public final int accountId;
        public final String accountName;
        public final String horizon;
        public final int defaultMinutes;
        public final List<String> focusOptions;
        public final List<String> focus;
        public final Summary summary;
        public final Coverage coverage;
        public final Plan plan;               // null: nothing generated yet for this horizon
        public final Integer regeneratingId;  // a refresh the website is building
        public final String webUrl;

        Overview(int accountId, String accountName, String horizon, int defaultMinutes, List<String> focusOptions, List<String> focus,
                 Summary summary, Coverage coverage, Plan plan, Integer regeneratingId, String webUrl)
        {
            this.accountId = accountId;
            this.accountName = accountName;
            this.horizon = horizon;
            this.defaultMinutes = defaultMinutes;
            this.focusOptions = Collections.unmodifiableList(focusOptions);
            this.focus = Collections.unmodifiableList(focus);
            this.summary = summary;
            this.coverage = coverage;
            this.plan = plan;
            this.regeneratingId = regeneratingId;
            this.webUrl = webUrl;
        }
    }

    /** Plan + the summary computed with it. */
    public static final class PlanResult
    {
        public final Plan plan;
        public final Summary summary;

        PlanResult(Plan plan, Summary summary)
        {
            this.plan = plan;
            this.summary = summary;
        }
    }

    public static final class ProgressUpdate
    {
        public final int planId;
        public final Map<String, Progress> byKey;   // recommendation key → progress
        public final Summary summary;
        public final Coverage coverage;

        ProgressUpdate(int planId, Map<String, Progress> byKey, Summary summary, Coverage coverage)
        {
            this.planId = planId;
            this.byKey = Collections.unmodifiableMap(byKey);
            this.summary = summary;
            this.coverage = coverage;
        }
    }

    /** A request the website refused, with its own message (shown as-is). */
    public static final class ApiException extends IOException
    {
        public final int status;

        ApiException(int status, String message)
        {
            super(message);
            this.status = status;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Calls (blocking)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The website's Account Advisor page for {@code account} (the OSRS username,
     * which is how the site selects the account), optionally opened on {@code planId}.
     */
    public String websiteUrl(String account, Integer planId)
    {
        return buildWebsiteUrl(config.apiUrl(), account, planId);
    }

    /**
     * {@code <site>/account-advisor?account=<osrs name>[&plan=<id>]}, where {@code <site>}
     * is the configured API URL without its trailing {@code /api}. The name is
     * percent-encoded with spaces as {@code %20} (e.g. {@code rune%20alytics}).
     */
    static String buildWebsiteUrl(String apiUrl, String account, Integer planId)
    {
        if (account == null || account.trim().isEmpty()) return null;
        String base = apiUrl == null || apiUrl.trim().isEmpty() ? "https://runealytics.com" : apiUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/api")) base = base.substring(0, base.length() - 4);
        HttpUrl site = HttpUrl.parse(base);
        if (site == null) return null;
        HttpUrl.Builder b = site.newBuilder().addPathSegment("account-advisor")
                .addQueryParameter("account", account.trim());
        if (planId != null) b.addQueryParameter("plan", String.valueOf(planId));
        return b.build().toString();
    }

    public Overview overview(String horizon) throws IOException
    {
        HttpUrl base = HttpUrl.parse(config.apiUrl() + "/plugin/advisor");
        if (base == null)
        {
            throw new IOException("Invalid API URL.");
        }
        HttpUrl.Builder b = base.newBuilder();
        if (horizon != null)
        {
            b.addQueryParameter("horizon", horizon);
        }
        return parseOverview(execute(authed(new Request.Builder().url(b.build()).get())));
    }

    public PlanResult generate(String horizon, Integer minutes, List<String> focus) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("horizon", horizon);
        if (minutes != null)
        {
            body.addProperty("minutes", minutes);
        }
        JsonArray f = new JsonArray();
        for (String s : focus)
        {
            f.add(s);
        }
        body.add("focus", f);
        body.addProperty("refresh", true);
        JsonObject o = execute(authed(new Request.Builder()
                .url(config.apiUrl() + "/plugin/advisor/plans")
                .post(RequestBody.create(JSON, gson.toJson(body)))));
        return new PlanResult(parsePlan(obj(o, "plan")), parseSummary(obj(o, "summary")));
    }

    public PlanResult plan(int id) throws IOException
    {
        JsonObject o = execute(authed(new Request.Builder().url(config.apiUrl() + "/plugin/advisor/plans/" + id).get()));
        return new PlanResult(parsePlan(obj(o, "plan")), parseSummary(obj(o, "summary")));
    }

    public ProgressUpdate progress(int planId) throws IOException
    {
        return parseProgressUpdate(execute(authed(new Request.Builder()
                .url(config.apiUrl() + "/plugin/advisor/plans/" + planId + "/progress").get())));
    }

    public void track(String activityId, boolean tracked) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("activity_id", activityId);
        body.addProperty("tracked", tracked);
        execute(authed(new Request.Builder().url(config.apiUrl() + "/plugin/advisor/track")
                .post(RequestBody.create(JSON, gson.toJson(body)))));
    }

    public ProgressUpdate ack(int planId, String stepId, boolean done) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("plan_id", planId);
        body.addProperty("step_id", stepId);
        body.addProperty("done", done);
        return parseProgressUpdate(execute(authed(new Request.Builder().url(config.apiUrl() + "/plugin/advisor/steps/ack")
                .post(RequestBody.create(JSON, gson.toJson(body))))));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Plumbing
    // ═════════════════════════════════════════════════════════════════════════

    private Request authed(Request.Builder rb) throws IOException
    {
        String token = state.getVerificationCode();
        if (token == null || token.isEmpty())
        {
            throw new ApiException(401, "Verify your account on the Settings tab first.");
        }
        return rb.addHeader("Authorization", "Bearer " + token).addHeader("Accept", "application/json").build();
    }

    private JsonObject execute(Request request) throws IOException
    {
        try (Response response = httpClient.newCall(request).execute())
        {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
            {
                String msg = RuneAlyticsJson.extractMessage(text);
                if (response.code() == 429)
                {
                    msg = "Too many requests — wait a minute and try again.";
                }
                else if (response.code() == 401)
                {
                    msg = "Your RuneAlytics link has expired — verify again on the Settings tab.";
                }
                throw new ApiException(response.code(), msg != null && !msg.isEmpty()
                        ? msg : "RuneAlytics couldn't load your advice (HTTP " + response.code() + ").");
            }
            JsonObject o = gson.fromJson(text, JsonObject.class);
            if (o == null)
            {
                throw new IOException("Empty response from RuneAlytics.");
            }
            return o;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Parsing (package-private for tests)
    // ═════════════════════════════════════════════════════════════════════════

    static Overview parseOverview(JsonObject o) throws IOException
    {
        JsonObject account = obj(o, "account");
        JsonObject plan = obj(o, "plan");
        JsonObject regen = obj(o, "regenerating");
        return new Overview(
                account != null ? intOr(account, "id", 0) : 0,
                account != null ? str(account, "username") : null,
                str(o, "horizon"),
                intOr(o, "default_minutes", 60),
                strings(arr(o, "focus_options")),
                strings(arr(o, "focus")),
                parseSummary(obj(o, "summary")),
                parseCoverage(obj(o, "coverage")),
                plan != null ? parsePlan(plan) : null,
                regen != null ? integer(regen, "id") : null,
                str(o, "web_url"));
    }

    static Summary parseSummary(JsonObject o)
    {
        if (o == null)
        {
            return new Summary(null, null, null, null, null);
        }
        return new Summary(integer(o, "active_goals"), integer(o, "steps_done"), integer(o, "steps_total"),
                integer(o, "recommendations"), str(o, "updated_at"));
    }

    static Coverage parseCoverage(JsonObject o)
    {
        List<CoverageItem> items = new ArrayList<>();
        JsonArray a = arr(o, "items");
        if (a != null)
        {
            for (JsonElement e : a)
            {
                if (e.isJsonObject())
                {
                    JsonObject i = e.getAsJsonObject();
                    items.add(new CoverageItem(str(i, "key"), str(i, "state"), str(i, "label")));
                }
            }
        }
        return new Coverage(o != null ? str(o, "synced_at") : null, items);
    }

    static Plan parsePlan(JsonObject o) throws IOException
    {
        if (o == null)
        {
            throw new IOException("RuneAlytics returned no plan.");
        }
        List<Recommendation> recs = new ArrayList<>();
        JsonArray ra = arr(o, "recommendations");
        if (ra != null)
        {
            for (JsonElement e : ra)
            {
                if (!e.isJsonObject())
                {
                    continue;
                }
                JsonObject r = e.getAsJsonObject();
                String title = str(r, "title");
                String key = str(r, "key");
                if (title == null || key == null)
                {
                    continue;
                }
                JsonObject link = obj(r, "link");
                recs.add(new Recommendation(key, str(r, "activity_id"), title, str(r, "summary"), str(r, "why"),
                        str(r, "status"), str(r, "category"), str(r, "priority"), integer(r, "duration_minutes"),
                        bool(r, "pvp_risk"), bool(r, "is_new"),
                        link != null ? str(link, "label") : null, link != null ? str(link, "url") : null,
                        obj(r, "progress") != null ? parseProgress(r) : null));
            }
        }
        JsonObject summary = obj(o, "summary");
        return new Plan(intOr(o, "id", 0), str(o, "status"), str(o, "stage"), str(o, "horizon"), str(o, "origin"),
                bool(o, "stale"), str(o, "error"), str(o, "generated_at"),
                summary != null ? str(summary, "headline") : null, summary != null ? str(summary, "text") : null,
                str(o, "web_url"), recs, strings(arr(o, "missing_data")));
    }

    /** Reads {activity_id, progress:{done,total}, steps:[…], ready, tracked} from a recommendation or progress entry. */
    static Progress parseProgress(JsonObject r)
    {
        JsonObject p = obj(r, "progress");
        List<Step> steps = new ArrayList<>();
        JsonArray sa = arr(r, "steps");
        if (sa != null)
        {
            for (JsonElement e : sa)
            {
                if (e.isJsonObject())
                {
                    JsonObject s = e.getAsJsonObject();
                    if (str(s, "id") != null)
                    {
                        steps.add(new Step(str(s, "id"), str(s, "kind"), str(s, "label"), str(s, "state"),
                                str(s, "source"), str(s, "detail"), bool(s, "ackable")));
                    }
                }
            }
        }
        return new Progress(str(r, "activity_id"), p != null ? intOr(p, "done", 0) : 0, p != null ? intOr(p, "total", 0) : 0,
                bool(r, "ready"), bool(r, "tracked"), steps);
    }

    static ProgressUpdate parseProgressUpdate(JsonObject o)
    {
        Map<String, Progress> byKey = new LinkedHashMap<>();
        JsonObject recs = obj(o, "recommendations");
        if (recs != null)
        {
            for (Map.Entry<String, JsonElement> e : recs.entrySet())
            {
                if (e.getValue().isJsonObject())
                {
                    byKey.put(e.getKey(), parseProgress(e.getValue().getAsJsonObject()));
                }
            }
        }
        return new ProgressUpdate(intOr(o, "plan_id", 0), byKey, parseSummary(obj(o, "summary")), parseCoverage(obj(o, "coverage")));
    }

    private static List<String> strings(JsonArray a)
    {
        List<String> out = new ArrayList<>();
        if (a != null)
        {
            for (JsonElement e : a)
            {
                if (e.isJsonPrimitive() && !e.getAsString().isEmpty())
                {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    private static JsonObject obj(JsonObject o, String k)
    {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray arr(JsonObject o, String k)
    {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }

    private static String str(JsonObject o, String k)
    {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static boolean bool(JsonObject o, String k)
    {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static Integer integer(JsonObject o, String k)
    {
        try
        {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static int intOr(JsonObject o, String k, int fallback)
    {
        Integer v = integer(o, k);
        return v != null ? v : fallback;
    }
}
