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
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nJwAAAAASUVORK5CYII=";

    @Test
    void onlyTheOwnerCanConsumeACompleteUploadOnce() {
        var uploads = new ReferenceImageUploads();
        var owner = UUID.randomUUID();
        var token = UUID.randomUUID();
        byte[] bytes = Base64.getDecoder().decode(PNG_B64);
        var request = new FormaRequest();
        var reference = new ReferenceInput(ReferenceImageChunk.REFERENCE_TYPE, token.toString(), null);
        request.setReferences(List.of(reference));
        uploads.accept(owner, new ReferenceImageChunk(token, bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        assertFalse(uploads.resolve(owner, request));
        uploads.accept(owner, new ReferenceImageChunk(token, bytes.length, 12, Arrays.copyOfRange(bytes, 12, bytes.length)));
        assertFalse(uploads.resolve(UUID.randomUUID(), request));
        assertTrue(uploads.resolve(owner, request));
        assertEquals("data:image/png;base64," + PNG_B64, request.getReferences().getFirst().getContent());
        request.setReferences(List.of(reference));
        assertFalse(uploads.resolve(owner, request));
    }

    @Test
    void twoImagesDoNotOverwriteEachOther() {
        var uploads = new ReferenceImageUploads();
        var owner = UUID.randomUUID();
        byte[] bytes = Base64.getDecoder().decode(PNG_B64);
        UUID token1 = UUID.randomUUID();
        UUID token2 = UUID.randomUUID();

        // Interleave first chunks — previously offset==0 for token2 would wipe token1.
        uploads.accept(owner, new ReferenceImageChunk(token1, bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        uploads.accept(owner, new ReferenceImageChunk(token2, bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        assertEquals(2, uploads.pendingCountFor(owner));
        uploads.accept(owner, new ReferenceImageChunk(token1, bytes.length, 12, Arrays.copyOfRange(bytes, 12, bytes.length)));
        uploads.accept(owner, new ReferenceImageChunk(token2, bytes.length, 12, Arrays.copyOfRange(bytes, 12, bytes.length)));

        var request = new FormaRequest();
        request.setReferences(List.of(
                new ReferenceInput(ReferenceImageChunk.REFERENCE_TYPE, token1.toString(), null),
                new ReferenceInput(ReferenceImageChunk.REFERENCE_TYPE, token2.toString(), null)));
        assertTrue(uploads.resolve(owner, request));
        assertEquals(2, request.getReferences().size());
        assertTrue(request.getReferences().get(0).getContent().startsWith("data:image/png;base64,"));
        assertTrue(request.getReferences().get(1).getContent().startsWith("data:image/png;base64,"));
        assertEquals(0, uploads.pendingCountFor(owner));
    }

    @Test
    void perPlayerCapBlocksFifthConcurrentUpload() {
        var uploads = new ReferenceImageUploads();
        var owner = UUID.randomUUID();
        byte[] stub = new byte[24];
        for (int i = 0; i < ReferenceImageUploads.MAX_PENDING_PER_PLAYER; i++) {
            uploads.accept(owner, new ReferenceImageChunk(
                    UUID.randomUUID(), stub.length, 0, Arrays.copyOfRange(stub, 0, 12)));
        }
        assertEquals(ReferenceImageUploads.MAX_PENDING_PER_PLAYER, uploads.pendingCountFor(owner));
        uploads.accept(owner, new ReferenceImageChunk(
                UUID.randomUUID(), stub.length, 0, Arrays.copyOfRange(stub, 0, 12)));
        assertEquals(ReferenceImageUploads.MAX_PENDING_PER_PLAYER, uploads.pendingCountFor(owner));
    }

    @Test
    void clearRemovesAllPendingForOwner() {
        var uploads = new ReferenceImageUploads();
        var owner = UUID.randomUUID();
        byte[] bytes = new byte[24];
        uploads.accept(owner, new ReferenceImageChunk(UUID.randomUUID(), bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        uploads.accept(owner, new ReferenceImageChunk(UUID.randomUUID(), bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        uploads.accept(UUID.randomUUID(), new ReferenceImageChunk(UUID.randomUUID(), bytes.length, 0, Arrays.copyOfRange(bytes, 0, 12)));
        assertEquals(2, uploads.pendingCountFor(owner));
        assertEquals(3, uploads.pendingCount());
        uploads.clear(owner);
        assertEquals(0, uploads.pendingCountFor(owner));
        assertEquals(1, uploads.pendingCount());
        uploads.clearAll();
        assertEquals(0, uploads.pendingCount());
    }
}
