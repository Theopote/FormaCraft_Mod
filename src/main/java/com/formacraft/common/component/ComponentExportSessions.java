package com.formacraft.common.component;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;

/** Pull-based bounded immutable snapshots. Paths never cross the protocol. */
public final class ComponentExportSessions {
    public static final int CHUNK_BYTES = 20 * 1024;
    private record Session(UUID token, byte[] bytes, int offset, long touched) {}
    public record Chunk(int length, int offset, byte[] data) {
        public Chunk { data = data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final LongSupplier clock;
    public ComponentExportSessions() { this(System::nanoTime); }
    public ComponentExportSessions(LongSupplier clock) { this.clock = clock; }
    public synchronized void begin(UUID player, UUID token, ComponentPackageIO.Verified source) throws IOException {
        expire();
        if (player == null || token == null || source == null) throw new IOException("无效导出请求");
        byte[] bytes = source.toBytes();
        long reserved = sessions.entrySet().stream().filter(e -> !e.getKey().equals(player)).mapToLong(e -> e.getValue().bytes.length).sum();
        if (sessions.size() - (sessions.containsKey(player) ? 1 : 0) >= 4 || reserved + bytes.length > 32L * 1024 * 1024)
            throw new IOException("导出队列繁忙，请稍后重试");
        sessions.put(player, new Session(token, bytes, 0, clock.getAsLong()));
    }
    public synchronized Chunk next(UUID player, UUID token, int offset) throws IOException {
        expire(); var session = sessions.get(player);
        if (session == null || !session.token.equals(token)) throw new IOException("导出会话不可用");
        if (offset != session.offset) { sessions.remove(player); throw new IOException("导出分块不连续"); }
        int end = Math.min(session.bytes.length, offset + CHUNK_BYTES);
        var chunk = new Chunk(session.bytes.length, offset, Arrays.copyOfRange(session.bytes, offset, end));
        if (end == session.bytes.length) sessions.remove(player);
        else sessions.put(player, new Session(token, session.bytes, end, clock.getAsLong()));
        return chunk;
    }
    public synchronized void cancel(UUID player, UUID token) {
        var session = sessions.get(player); if (session != null && session.token.equals(token)) sessions.remove(player);
    }
    public synchronized void disconnect(UUID player) { sessions.remove(player); }
    public synchronized void clear() { sessions.clear(); }
    public synchronized void expire() { long now = clock.getAsLong(); sessions.values().removeIf(s -> now - s.touched >= 120_000_000_000L); }
}
