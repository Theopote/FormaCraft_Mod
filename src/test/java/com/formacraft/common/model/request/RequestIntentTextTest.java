package com.formacraft.common.model.request;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestIntentTextTest {
    @Test
    void explicitMessageWinsWithoutTrimming() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage(" user text ");
        req.setChatHistory(List.of("history"));
        req.setRequestText("request");
        assertEquals(" user text ", RequestIntentText.resolve(req));
    }

    @Test
    void lastHistoryEntryThenRequestFallback() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage(" ");
        req.setChatHistory(List.of("first", "last"));
        req.setRequestText("request");
        assertEquals("last", RequestIntentText.resolve(req));
        req.setChatHistory(List.of("first", " "));
        assertEquals("request", RequestIntentText.resolve(req));
    }

    @Test
    void missingInputIsEmpty() {
        assertEquals("", RequestIntentText.resolve(null));
        assertEquals("", RequestIntentText.resolve(new FormaRequest()));
    }
}
