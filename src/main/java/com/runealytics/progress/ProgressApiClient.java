package com.runealytics.progress;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Supplier;

import static com.runealytics.RuneAlyticsHttp.JSON;

/**
 * Sends one progress batch to the website. Blocking; call only from a background
 * executor thread. Uses the same bearer token as every other plugin endpoint.
 * Never logs the token or the payload body.
 */
public class ProgressApiClient
{
    private static final Logger log = LoggerFactory.getLogger(ProgressApiClient.class);

    /** Relative to {@code RunealyticsConfig#apiUrl()} (which already ends in {@code /api}). */
    public static final String PROGRESS_PATH = "/plugin/v1/accounts/me/progress";

    private final OkHttpClient httpClient;
    private final Supplier<String> apiUrl;

    public ProgressApiClient(OkHttpClient httpClient, Supplier<String> apiUrl)
    {
        this.httpClient = httpClient;
        this.apiUrl = apiUrl;
    }

    public ProgressOutbox.Outcome send(ProgressOutbox.Batch batch)
    {
        String token = batch.getToken();
        if (token == null || token.isEmpty()) return ProgressOutbox.Outcome.AUTH_FAILED;

        Request request = new Request.Builder()
                .url(apiUrl.get() + PROGRESS_PATH)
                .post(RequestBody.create(JSON, batch.getBody()))
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .addHeader("Idempotency-Key", batch.getBatchId())
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            ProgressOutbox.Outcome outcome = classify(response.code());
            log.debug("[Progress] POST {} kind={} seq={} bytes={} transitions={} -> HTTP {} ({})",
                    PROGRESS_PATH, batch.getKind(), batch.getSequence(), batch.getBody().length(),
                    batch.getTransitionCount(), response.code(), outcome);
            return outcome;
        }
        catch (IOException e)
        {
            log.debug("[Progress] network failure (seq={}): {}", batch.getSequence(), e.getMessage());
            return ProgressOutbox.Outcome.RETRY;
        }
    }

    /** Maps an HTTP status code to a delivery outcome. */
    static ProgressOutbox.Outcome classify(int code)
    {
        if (code >= 200 && code < 300) return ProgressOutbox.Outcome.ACK;
        if (code == 401) return ProgressOutbox.Outcome.AUTH_FAILED;
        if (code == 404 || code == 405 || code == 410) return ProgressOutbox.Outcome.NOT_DEPLOYED;
        if (code == 408 || code == 425 || code == 429 || code >= 500) return ProgressOutbox.Outcome.RETRY;
        return ProgressOutbox.Outcome.REJECTED;
    }
}
