package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.ReferenceInput;
import com.formacraft.common.network.ReferenceImageChunk;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceImageUploadsTest {
    @Test void onlyTheOwnerCanConsumeACompleteUploadOnce() {
        var uploads = new ReferenceImageUploads();
        var owner = UUID.randomUUID();
        var token = UUID.randomUUID();
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nJwAAAAASUVORK5CYII=";
        byte[] bytes = Base64.getDecoder().decode(png);
        var request = new FormaRequest();
        var reference = new ReferenceInput(ReferenceImageChunk.REFERENCE_TYPE, token.toString(), null);
        request.setReferences(List.of(reference));
        uploads.accept(owner, new ReferenceImageChunk(token, bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        assertFalse(uploads.resolve(owner, request));
        uploads.accept(owner, new ReferenceImageChunk(token, bytes.length, 12, Arrays.copyOfRange(bytes, 12, bytes.length)));
        assertFalse(uploads.resolve(UUID.randomUUID(), request));
        assertTrue(uploads.resolve(owner, request));
        assertEquals("data:image/png;base64," + png, request.getReferences().getFirst().getContent());
        request.setReferences(List.of(reference));
        assertFalse(uploads.resolve(owner, request));
    }

    @Test void clearRemovesPendingUploadAndClearAllEmptiesQueue() {
        var uploads = new ReferenceImageUploads();
        var owner = UUID.randomUUID();
        var token = UUID.randomUUID();
        byte[] bytes = new byte[24];
        uploads.accept(owner, new ReferenceImageChunk(token, bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        assertEquals(1, uploads.pendingCount());
        uploads.clear(owner);
        assertEquals(0, uploads.pendingCount());
        uploads.accept(owner, new ReferenceImageChunk(token, bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        uploads.accept(UUID.randomUUID(), new ReferenceImageChunk(UUID.randomUUID(), bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        assertEquals(2, uploads.pendingCount());
        uploads.clearAll();
        assertEquals(0, uploads.pendingCount());
    }
}
