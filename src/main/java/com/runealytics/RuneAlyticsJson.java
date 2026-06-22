package com.runealytics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RuneAlyticsJson
{
    private RuneAlyticsJson() {}

    /**
     * Extracts the top-level {@code "message"} string from a JSON object, or
     * {@code null} if the input isn't a JSON object with a string message.
     * Uses a real JSON parser so escaped quotes inside the value are handled
     * correctly (unlike naive string scanning).
     */
    public static String extractMessage(String json)
    {
        if (json == null || json.isEmpty())
        {
            return null;
        }
        try
        {
            JsonElement el = new JsonParser().parse(json);
            if (el.isJsonObject())
            {
                JsonObject obj = el.getAsJsonObject();
                JsonElement msg = obj.get("message");
                if (msg != null && msg.isJsonPrimitive())
                {
                    return msg.getAsString();
                }
            }
        }
        catch (RuntimeException ignored)
        {
            // Not valid JSON / unexpected shape — fall through to null.
        }
        return null;
    }
}
