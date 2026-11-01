package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.network.ComponentImportNetworking;
import java.util.*;
import java.util.function.LongSupplier;

/** Pure client state: asynchronous file reads and replies must match the active token and phase. */
public final class ComponentImportClientState {
    public enum Phase { IDLE, READING, UPLOADING, VERIFYING, PREVIEW, COMMITTING, DONE, ERROR }
    private final LongSupplier clock;
    private UUID token;
    private Phase phase = Phase.IDLE;
    private byte[] bytes;
    private int offset, length;
    private long touched;
    private String message = "";
    private ComponentImportSessions.Preview preview;
    private ComponentImportSessions.Installed installed;
    public ComponentImportClientState(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public UUID begin() { clear(); token = UUID.randomUUID(); phase = Phase.READING; touched = clock.getAsLong(); return token; }
    public boolean fileReady(UUID request, byte[] data) {
        if (!matches(request) || phase != Phase.READING) return false;
        if (data == null || data.length == 0 || data.length > ComponentPackageIO.MAX_PACKAGE_BYTES) { fail("构件包为空或超过 16 MiB"); return false; }
        bytes = data.clone(); length = bytes.length; offset = 0; phase = Phase.UPLOADING; touched = clock.getAsLong(); return true;
    }
    public ComponentImportNetworking.Request nextUploadRequest() {
        if (phase != Phase.UPLOADING) return null;
        touched = clock.getAsLong();
        if (offset == length) { bytes = null; phase = Phase.VERIFYING; return request(ComponentImportNetworking.Operation.PREVIEW); }
        int end = Math.min(length, offset + ComponentImportSessions.CHUNK_BYTES);
        var request = new ComponentImportNetworking.Request(token, ComponentImportNetworking.Operation.CHUNK, 0, offset, Arrays.copyOfRange(bytes, offset, end));
        offset = end; return request;
    }
    public ComponentImportNetworking.Request confirm() {
        if (phase != Phase.PREVIEW) return null;
        phase = Phase.COMMITTING; touched = clock.getAsLong(); return request(ComponentImportNetworking.Operation.CONFIRM);
    }
    private ComponentImportNetworking.Request request(ComponentImportNetworking.Operation op) { return new ComponentImportNetworking.Request(token, op, 0, 0, null); }
    public void accept(ComponentImportNetworking.Response response) {
        if (!matches(response.token()) || phase == Phase.ERROR || phase == Phase.DONE || phase == Phase.IDLE) return;
        touched = clock.getAsLong();
        try {
            switch (response.reply()) {
                case ERROR -> fail(JsonUtil.fromJson(response.json(), String.class));
                case PREVIEW -> {
                    if (phase != Phase.VERIFYING) return;
                    var value = JsonUtil.fromJson(response.json(), ComponentImportSessions.Preview.class);
                    if (value == null || value.name() == null || value.author() == null || value.license() == null || value.trainingPermission() == null
                            || value.width() <= 0 || value.height() <= 0 || value.depth() <= 0 || value.blocks() <= 0 || value.blocks() > ComponentIds.MAX_BLOCKS)
                        throw new IllegalArgumentException("Invalid preview");
                    preview = value; phase = Phase.PREVIEW;
                }
                case INSTALLED -> {
                    if (phase != Phase.COMMITTING) return;
                    var value = JsonUtil.fromJson(response.json(), ComponentImportSessions.Installed.class);
                    if (value == null || !ComponentIds.isSafeId(value.assetId()) || value.name() == null || value.status() == null)
                        throw new IllegalArgumentException("Invalid import result");
                    installed = value; phase = Phase.DONE; preview = null;
                }
            }
        } catch (RuntimeException invalid) { fail("导入响应无效，请重试"); }
    }
    public boolean expire() {
        if (token != null && phase != Phase.IDLE && phase != Phase.ERROR && phase != Phase.DONE && clock.getAsLong() - touched >= 120_000_000_000L) {
            fail("导入会话超时，请重新选择文件"); return true;
        }
        return false;
    }
    public void fail(String text) { message = text == null ? "导入失败" : text; bytes = null; preview = null; phase = Phase.ERROR; }
    public void clear() { token = null; bytes = null; preview = null; installed = null; phase = Phase.IDLE; message = ""; offset = length = 0; }
    public boolean matches(UUID request) { return token != null && token.equals(request); }
    public boolean busy() { return phase == Phase.READING || phase == Phase.UPLOADING || phase == Phase.VERIFYING || phase == Phase.COMMITTING; }
    public Phase phase() { return phase; }
    public UUID token() { return token; }
    public int length() { return length; }
    public int progress() { return length == 0 ? 0 : (int) ((long) offset * 100 / length); }
    public String message() { return message; }
    public ComponentImportSessions.Preview preview() { return preview; }
    public ComponentImportSessions.Installed installed() { return installed; }
}
