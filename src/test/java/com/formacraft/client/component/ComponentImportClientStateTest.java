package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.network.ComponentImportNetworking;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ComponentImportClientStateTest {
    private ComponentImportNetworking.Response preview(UUID token) {
        return new ComponentImportNetworking.Response(token, ComponentImportNetworking.Reply.PREVIEW, JsonUtil.toJson(
                new ComponentImportSessions.Preview("Window", 1, 1, 1, 1, "Alice", "Test", ComponentPackageIO.TrainingPermission.DENIED, "alice", "1", false)));
    }
    private void upload(ComponentImportClientState state) {
        assertTrue(state.fileReady(state.token(), new byte[] {1, 2, 3}));
        while (state.nextUploadRequest() != null) { }
        assertEquals(ComponentImportClientState.Phase.VERIFYING, state.phase());
    }
    @Test void chunksAreBoundedOrderedAndUseAnIsolatedFileSnapshot() {
        var state = new ComponentImportClientState(() -> 0L); var token = state.begin();
        byte[] original = new byte[ComponentImportSessions.CHUNK_BYTES + 15]; Arrays.fill(original, (byte) 7);
        assertTrue(state.fileReady(token, original)); Arrays.fill(original, (byte) 9);
        var received = new ByteArrayOutputStream(); ComponentImportNetworking.Request request; boolean sawPreview = false;
        while ((request = state.nextUploadRequest()) != null) {
            assertEquals(token, request.token());
            if (request.operation() == ComponentImportNetworking.Operation.PREVIEW) { sawPreview = true; continue; }
            assertEquals(received.size(), request.offset()); assertTrue(request.data().length <= ComponentImportSessions.CHUNK_BYTES);
            received.writeBytes(request.data());
        }
        assertTrue(sawPreview); assertEquals(original.length, received.size()); assertEquals(7, received.toByteArray()[0]);
        assertEquals(100, state.progress()); assertNull(state.confirm());
    }
    @Test void previewMustPrecedeConfirmationAndCommitCanOnlyBeSentOnce() {
        var state = new ComponentImportClientState(() -> 0L); var token = state.begin(); upload(state);
        var installed = new ComponentImportNetworking.Response(token, ComponentImportNetworking.Reply.INSTALLED,
                JsonUtil.toJson(new ComponentImportSessions.Installed("asset_test", "Existing window", ComponentPackageInstallations.ImportStatus.REUSED)));
        state.accept(installed); assertEquals(ComponentImportClientState.Phase.VERIFYING, state.phase());
        state.accept(preview(token)); assertFalse(state.preview().global());
        assertNotNull(state.confirm()); assertNull(state.confirm()); state.accept(installed);
        assertEquals(ComponentImportClientState.Phase.DONE, state.phase()); assertEquals("asset_test", state.installed().assetId());
    }
    @Test void replacingOrCancellingARequestInvalidatesAsyncFilesAndLateReplies() {
        var state = new ComponentImportClientState(() -> 0L); var old = state.begin(); var current = state.begin();
        assertFalse(state.fileReady(old, new byte[] {1})); state.accept(preview(old));
        assertEquals(ComponentImportClientState.Phase.READING, state.phase());
        upload(state); state.clear(); state.accept(preview(current)); assertEquals(ComponentImportClientState.Phase.IDLE, state.phase());
    }
    @Test void timeoutAndMalformedPreviewReleasePendingUploadAndPermitRetry() {
        var clock = new AtomicLong(); var state = new ComponentImportClientState(clock::get); state.begin(); upload(state);
        clock.set(120_000_000_000L); assertTrue(state.expire()); assertEquals(ComponentImportClientState.Phase.ERROR, state.phase());
        var token = state.begin(); upload(state);
        state.accept(new ComponentImportNetworking.Response(token, ComponentImportNetworking.Reply.PREVIEW, "{}"));
        assertEquals(ComponentImportClientState.Phase.ERROR, state.phase()); assertNull(state.preview());
    }
    @Test void emptyFileIsRejectedBeforeUpload() {
        var state = new ComponentImportClientState(() -> 0L); var token = state.begin();
        assertFalse(state.fileReady(token, new byte[0])); assertNull(state.nextUploadRequest()); assertEquals(ComponentImportClientState.Phase.ERROR, state.phase());
    }
}
