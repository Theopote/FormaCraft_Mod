package com.formacraft.common.component;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Atomic catalog snapshots: no partial directory is ever published by the receiver. */
public final class ComponentCatalogTransfer {
    private ComponentCatalogTransfer() {}
    public static final int CHUNK_BYTES = 20 * 1024, MAX_BYTES = 16 * 1024 * 1024;
    public record Chunk(long revision, int length, int offset, byte[] data) {
        public Chunk { data = data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    public static byte[] encode(String json) throws IOException {
        if (json == null || json.length() > MAX_BYTES) throw new IOException("目录过大");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("目录大小无效");
        return bytes;
    }
    public static Chunk chunk(long revision, byte[] bytes, int offset) {
        return new Chunk(revision,bytes.length,offset,Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CHUNK_BYTES)));
    }
    public static final class Receiver {
        private final LongSupplier clock;
        private long revision, touched;
        private int length;
        private ByteArrayOutputStream bytes;
        public Receiver(LongSupplier clock) { this.clock = clock; }
        public String accept(Chunk chunk) throws IOException {
            if (chunk.revision() <= 0 || chunk.revision() < revision) return null;
            if (chunk.revision() == revision && bytes == null) return null;
            if (chunk.revision() > revision) {
                if (chunk.offset() != 0) throw new IOException("目录起始分块缺失");
                revision = chunk.revision(); bytes = new ByteArrayOutputStream(); length = chunk.length();
            }
            byte[] data = chunk.data();
            if (length <= 0 || length > MAX_BYTES || chunk.length() != length || chunk.offset() != bytes.size()
                    || data.length == 0 || data.length > CHUNK_BYTES || (long)bytes.size()+data.length > length) {
                bytes = null; throw new IOException("目录分块无效");
            }
            bytes.writeBytes(data); touched = clock.getAsLong();
            if (bytes.size() < length) return null;
            byte[] complete = bytes.toByteArray(); bytes = null;
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(complete)).toString();
        }
        public boolean active() { return bytes != null; }
        public boolean expire() {
            if (active() && clock.getAsLong()-touched >= 120_000_000_000L) { bytes = null; return true; }
            return false;
        }
        public void clear() { bytes = null; revision = 0; length = 0; }
    }
}
