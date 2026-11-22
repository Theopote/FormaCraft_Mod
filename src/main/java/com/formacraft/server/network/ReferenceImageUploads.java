package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.ReferenceInput;
import com.formacraft.common.network.ReferenceImageChunk;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Transient state accessed on the server thread under a per-player owner key. */
final class ReferenceImageUploads {
    private static final int MAX_PENDING = 16;
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final Map<UUID, Upload> pending = new HashMap<>();

    private static final class Upload {
        private final UUID token;
        private final byte[] bytes;
        private final long started = System.nanoTime();
        private int offset;

        private Upload(UUID token, int length) {
            this.token = token;
            bytes = new byte[length];
        }
    }

    void accept(UUID owner, ReferenceImageChunk chunk) {
        expire();
        int length = chunk.length();
        int offset = chunk.offset();
        byte[] data = chunk.data();
        if (length <= 0 || length > ReferenceImageChunk.MAX_BYTES || offset < 0
                || data.length == 0 || data.length > ReferenceImageChunk.CHUNK_BYTES
                || (long) offset + data.length > length) return;
        Upload upload = pending.get(owner);
        if (offset == 0) {
            if (upload == null && pending.size() >= MAX_PENDING) return;
            upload = new Upload(chunk.token(), length);
            pending.put(owner, upload);
        }
        if (upload == null || !upload.token.equals(chunk.token())
                || upload.bytes.length != length || upload.offset != offset) return;
        System.arraycopy(data, 0, upload.bytes, offset, data.length);
        upload.offset += data.length;
    }

    boolean resolve(UUID owner, FormaRequest request) {
        expire();
        var references = request.getReferences();
        if (references == null) return true;
        var resolved = new ArrayList<ReferenceInput>(references.size());
        for (ReferenceInput reference : references) {
            if (reference == null || !ReferenceImageChunk.REFERENCE_TYPE.equals(reference.getType())) {
                resolved.add(reference);
                continue;
            }
            Upload upload = pending.get(owner);
            if (upload == null || !upload.token.toString().equals(reference.getContent())
                    || upload.offset != upload.bytes.length) return false;
            pending.remove(owner);
            String mime = ReferenceImageChunk.mimeType(upload.bytes);
            if (mime == null) return false;
            resolved.add(new ReferenceInput("image_base64",
                    "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(upload.bytes), null));
        }
        request.setReferences(resolved);
        return true;
    }

    private void expire() {
        long now = System.nanoTime();
        pending.values().removeIf(upload -> now - upload.started >= TTL_NANOS);
    }

}
