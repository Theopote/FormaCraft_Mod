package com.formacraft.client.component;

import com.formacraft.common.component.ComponentSourceView;
import com.formacraft.common.component.ComponentPackageIO;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ClientComponentSourceStateTest {
    private String page(String id, int index) {
        return JsonUtil.toJson(new ComponentSourceView.Page(id, index, 2, index == 0, "Window", "alice", "1", "Alice", "Test",
                ComponentPackageIO.TrainingPermission.DENIED, "test-ref", null));
    }
    @Test void staleRepliesCannotReplaceCurrentSelectionOrSourcePage() {
        var state = new ClientComponentSourceState(() -> 0L);
        var old = state.begin("first", 0); var current = state.begin("second", 1);
        state.accept(old, page("first", 0)); assertTrue(state.loading()); assertNull(state.page());
        state.accept(current, page("second", 1)); assertFalse(state.loading()); assertEquals("second", state.page().assetId());
    }
    @Test void malformedOrMismatchedRepliesCannotDisplayOtherSources() {
        var state = new ClientComponentSourceState(() -> 0L);
        var token = state.begin("first", 0); state.accept(token, page("another", 0));
        assertNull(state.page()); assertNotNull(state.error());
        token = state.begin("first", 0); state.accept(token, "invalid JSON");
        assertNull(state.page()); assertNotNull(state.error());
    }
    @Test void timeoutAllowsRetryAndLateResponseIsIgnored() {
        var clock = new AtomicLong(); var state = new ClientComponentSourceState(clock::get);
        var token = state.begin("first", 0); clock.set(10_000_000_000L);
        assertFalse(state.loading()); assertNotNull(state.error());
        state.accept(token, page("first", 0)); assertNull(state.page());
        token = state.begin("first", 0); state.accept(token, page("first", 0));
        assertNull(state.error()); assertNotNull(state.page());
    }
    @Test void disconnectOrCatalogResetInvalidatesPendingReplies() {
        var state = new ClientComponentSourceState(() -> 0L); var token = state.begin("first", 0);
        state.clear(); state.accept(token, page("first", 0));
        assertNull(state.assetId()); assertNull(state.page()); assertFalse(state.loading());
    }
}
