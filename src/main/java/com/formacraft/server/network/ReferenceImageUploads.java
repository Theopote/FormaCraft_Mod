package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.ReferenceInput;
import com.formacraft.common.network.ReferenceImageChunk;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transient multi-image upload state keyed by {@code (owner, token)}.
 * Accessed on the server thread.
 */
final class ReferenceImageUploads {
    /** Soft global cap across all players. */
    private static final int MAX_PENDING_GLOBAL = 64;
    /** Concurrent in-flight images per player (multi-ref builds). */
    static final int MAX_PENDING_PER_PLAYER = 4;
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Map<Key, Upload> pending = new HashMap<>();

    private record Key(UUID owner, UUID token) {
        Key {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(token, "token");
        }
    }

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
        if (owner == null || chunk == null || chunk.token() == null) return;
        expire();
        int length = chunk.length();
        int offset = chunk.offset();
        byte[] data = chunk.data();
        if (length <= 0 || length > ReferenceImageChunk.MAX_BYTES || offset < 0
                || data == null || data.length == 0 || data.length > ReferenceImageChunk.CHUNK_BYTES
                || (long) offset + data.length > length) return;

        Key key = new Key(owner, chunk.token());
        Upload upload = pending.get(key);
        if (offset == 0) {
            if (upload == null) {
                if (countForOwner(owner) >= MAX_PENDING_PER_PLAYER) return;
                if (pending.size() >= MAX_PENDING_GLOBAL) return;
            }
            upload = new Upload(chunk.token(), length);
            pending.put(key, upload);
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
            UUID token = parseToken(reference.getContent());
            if (token == null) return false;
            Key key = new Key(owner, token);
            Upload upload = pending.get(key);
            if (upload == null || upload.offset != upload.bytes.length) return false;
            pending.remove(key);
            String mime = ReferenceImageChunk.mimeType(upload.bytes);
            if (mime == null) return false;
            resolved.add(new ReferenceInput("image_base64",
                    "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(upload.bytes), null));
        }
        request.setReferences(resolved);
        return true;
    }

    /** Drop every in-flight upload for a player (disconnect). */
    void clear(UUID owner) {
        if (owner == null) return;
        pending.keySet().removeIf(k -> owner.equals(k.owner()));
    }

    void clearAll() {
        pending.clear();
    }

    /** Test helper. */
    int pendingCount() {
        expire();
        return pending.size();
    }

    /** Test helper. */
    int pendingCountFor(UUID owner) {
        expire();
        return countForOwner(owner);
    }

    private int countForOwner(UUID owner) {
        int n = 0;
        for (Key k : pending.keySet()) {
            if (owner.equals(k.owner())) n++;
        }
        return n;
    }

    private static UUID parseToken(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void expire() {
        long now = System.nanoTime();
        Iterator<Map.Entry<Key, Upload>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Upload upload = it.next().getValue();
            if (upload == null || now - upload.started >= TTL_NANOS) {
                it.remove();
            }
        }
    }
}
