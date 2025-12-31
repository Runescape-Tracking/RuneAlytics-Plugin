package com.runealytics;

public final class RuneAlyticsJson
{
    private RuneAlyticsJson() {}

    public static String escape(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * Generic helper to pull a string field out of a simple JSON object without full parsing.
     * e.g. extractStringField("{\"foo\":\"bar\"}", "foo") -> "bar"
     */
    public static String extractStringField(String json, String fieldName)
    {
        if (json == null || json.isEmpty() || fieldName == null || fieldName.isEmpty())
        {
            return null;
        }

        String key = "\"" + fieldName + "\"";
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

    public static String extractMessage(String json)
    {
        return extractStringField(json, "message");
    }
}
