package com.formacraft.common.component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, player-owned uploads. Preview is read-only; confirmation consumes a verified snapshot. */
public final class ComponentImportSessions {
    public static final int CHUNK_BYTES = 20 * 1024;
    private static final long TTL = 120_000_000_000L;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final LongSupplier clock;
    public record Preview(String name, int width, int height, int depth, int blocks, String author, String license,
                          ComponentPackageIO.TrainingPermission trainingPermission, String namespace, String version, boolean global) {}
    public record Installed(String assetId, String name, ComponentPackageInstallations.ImportStatus status) {}
    private static final class Session {
        UUID token; int length; long touched;
        ComponentStorage.SaveContext context;
        ByteArrayOutputStream bytes;
        ComponentPackageIO.Verified verified;
        boolean capture;
        byte[] thumbnail;
    }
    public ComponentImportSessions() { this(System::nanoTime); }
    public ComponentImportSessions(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public synchronized void begin(UUID player, UUID token, int length, ComponentStorage.SaveContext context) throws IOException {
        expire(); requirePlayer(player, context);
        if (token == null || length <= 0 || length > ComponentPackageIO.MAX_PACKAGE_BYTES) throw new IOException("构件包大小无效");
        long reserved = sessions.entrySet().stream().filter(e -> !e.getKey().equals(player)).mapToLong(e -> e.getValue().length).sum();
        int others = sessions.size() - (sessions.containsKey(player) ? 1 : 0);
        if (others >= 4 || reserved + length > 32L * 1024 * 1024) throw new IOException("导入队列繁忙，请稍后重试");
        var session = new Session(); session.token = token; session.length = length; session.context = context;
        session.touched = clock.getAsLong(); session.bytes = new ByteArrayOutputStream(Math.min(length, 65536));
        sessions.put(player, session);
    }
    public synchronized void append(UUID player, UUID token, int offset, byte[] data) throws IOException {
        var s = get(player, token);
        if (s.verified != null || data == null || data.length == 0 || data.length > CHUNK_BYTES
                || offset != s.bytes.size() || (long) offset + data.length > s.length) {
            sessions.remove(player); throw new IOException("构件包分块不连续或超出限制");
        }
        s.bytes.writeBytes(data); s.touched = clock.getAsLong();
    }
    public synchronized void beginCapture(UUID player, UUID token, int length, ComponentStorage.SaveContext context) throws IOException {
        begin(player, token, length, context); sessions.get(player).capture = true;
    }
    public synchronized Preview preview(UUID player, UUID token, ComponentPackageIO.Environment environment) throws IOException {
        var s = get(player, token);
        if (s.verified != null) throw new IOException("构件包已完成校验");
        if (s.bytes.size() != s.length) { sessions.remove(player); throw new IOException("构件包上传不完整"); }
        try {
            ComponentPackageIO.Verified verified;
            if (s.capture) {
                var prepared = ComponentCapturePersistence.prepareUpload(s.bytes.toByteArray(), player, s.context, environment);
                verified = prepared.verified(); s.thumbnail = prepared.thumbnail();
            } else verified = ComponentPackageIO.readBytes(s.bytes.toByteArray(), environment);
            var d = verified.component();
            if (!d.isCapturedOrigin() && d.variantRules == null) throw new IOException("构件包缺少自包含的作者变化规则");
            s.verified = verified; s.bytes = null; s.touched = clock.getAsLong();
            var m = verified.manifest();
            return new Preview(ComponentSourceView.bounded(d.name, 256), d.size.w, d.size.h, d.size.d, d.blocks.size(),
                    m.author(), m.license(), m.trainingPermission(), m.namespace(), m.version(), s.context.writeGlobal());
        } catch (IOException | RuntimeException invalid) {
            sessions.remove(player);
            if (invalid instanceof IOException io) throw io;
            throw new IOException("构件包校验失败", invalid);
        }
    }
    public synchronized Installed confirm(UUID player, UUID token, ComponentStorage.SaveContext current) throws IOException {
        var s = get(player, token); requirePlayer(player, current);
        sessions.remove(player);
        if (s.verified == null) throw new IOException("请先完成构件包预览");
        if (!s.context.equals(current)) throw new IOException("保存权限或目标库已变化，请重新预览");
        var result = ComponentPackageInstallations.importVerified(s.verified, current);
        if (s.thumbnail != null && (result.status() == ComponentPackageInstallations.ImportStatus.NEW_ASSET
                || result.status() == ComponentPackageInstallations.ImportStatus.NEW_CONFIGURATION)) {
            try { ComponentStorage.saveThumbnail(current, result.assetId(), s.thumbnail); }
            catch (Exception thumbnailFailure) { com.formacraft.FormacraftMod.LOGGER.warn("Capture thumbnail save failed", thumbnailFailure); }
        }
        var root = current.writeGlobal() ? ComponentStorage.getGlobalComponentDir() : ComponentStorage.getPlayerComponentDir(player);
        var name = ComponentPackageInstallations.inspect(root, result.assetId()).component().name;
        return new Installed(result.assetId(), ComponentSourceView.bounded(name, 256), result.status());
    }
    private Session get(UUID player, UUID token) throws IOException {
        expire(); var s = sessions.get(player);
        if (s == null || !s.token.equals(token)) throw new IOException("导入会话已失效，请重新选择文件");
        s.touched = clock.getAsLong(); return s;
    }
    private static void requirePlayer(UUID player, ComponentStorage.SaveContext context) throws IOException {
        if (player == null || context == null || !player.equals(context.playerId())) throw new IOException("导入需要真实玩家保存上下文");
    }
    public synchronized void cancel(UUID player, UUID token) {
        var s = sessions.get(player); if (s != null && s.token.equals(token)) sessions.remove(player);
    }
    public synchronized void disconnect(UUID player) { sessions.remove(player); }
    public synchronized void clear() { sessions.clear(); }
    public synchronized void expire() { long now = clock.getAsLong(); sessions.values().removeIf(s -> now - s.touched >= TTL); }
    public synchronized int pending() { expire(); return sessions.size(); }
}
