package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Edge cases for server error-message extraction. The parser must never throw
 * and must only return a value for a JSON object carrying a primitive
 * {@code "message"} field.
 */
public class RuneAlyticsJsonTest
{
    @Test
    public void extractMessage_nullOrEmpty_returnsNull()
    {
        assertNull(RuneAlyticsJson.extractMessage(null));
        assertNull(RuneAlyticsJson.extractMessage(""));
    }

    @Test
    public void extractMessage_objectWithStringMessage_returnsIt()
    {
        assertEquals("Verification failed", RuneAlyticsJson.extractMessage(
                "{\"message\":\"Verification failed\"}"));
    }

    @Test
    public void extractMessage_handlesEscapedQuotesInValue()
    {
        assertEquals("Say \"hi\"", RuneAlyticsJson.extractMessage(
                "{\"message\":\"Say \\\"hi\\\"\"}"));
    }

    @Test
    public void extractMessage_preservesUnicode()
    {
        assertEquals("failed \u2013 \uD83D\uDE00", RuneAlyticsJson.extractMessage(
                "{\"message\":\"failed \u2013 \uD83D\uDE00\"}"));
    }

    @Test
    public void extractMessage_numericOrBooleanMessage_isStringified()
    {
        assertEquals("123", RuneAlyticsJson.extractMessage("{\"message\":123}"));
        assertEquals("true", RuneAlyticsJson.extractMessage("{\"message\":true}"));
    }

    @Test
    public void extractMessage_nonObjectJson_returnsNull()
    {
        assertNull(RuneAlyticsJson.extractMessage("[1,2,3]"));
        assertNull(RuneAlyticsJson.extractMessage("\"just a string\""));
        assertNull(RuneAlyticsJson.extractMessage("42"));
    }

    @Test
    public void extractMessage_objectWithoutMessage_returnsNull()
    {
        assertNull(RuneAlyticsJson.extractMessage("{}"));
        assertNull(RuneAlyticsJson.extractMessage("{\"error\":\"nope\"}"));
    }

    @Test
    public void extractMessage_messageNullOrNonPrimitive_returnsNull()
    {
        assertNull(RuneAlyticsJson.extractMessage("{\"message\":null}"));
        assertNull(RuneAlyticsJson.extractMessage("{\"message\":{\"nested\":1}}"));
        assertNull(RuneAlyticsJson.extractMessage("{\"message\":[\"a\"]}"));
    }

    @Test
    public void extractMessage_malformedJson_returnsNullWithoutThrowing()
    {
        assertNull(RuneAlyticsJson.extractMessage("{not valid"));
        assertNull(RuneAlyticsJson.extractMessage("{\"message\":}"));
    }
}
