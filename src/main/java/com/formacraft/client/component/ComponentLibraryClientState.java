package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.network.ComponentLibraryNetworking.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.LongSupplier;

/** Pure correlated download/delete state, independent of dialogs and connections. */
public final class ComponentLibraryClientState {
    public enum Phase { IDLE, DELETING, DOWNLOADING, READY, DELETED, ERROR }
    private final LongSupplier clock;
    private UUID token; private String assetId, primaryRef, sourceRef;
    private int sourceIndex, length; private long touched;
    private ByteArrayOutputStream bytes;
    private Phase phase = Phase.IDLE;
    private String message = "";
    public ComponentLibraryClientState(LongSupplier clock) { this.clock = clock; }
    public Request begin(String id, String primary, int index, String source, boolean delete) {
        if (busy()) throw new IllegalStateException("请先完成当前构件操作");
        if (!ComponentIds.isSafeId(id) || primary == null || primary.isBlank()) throw new IllegalArgumentException("请刷新并选择已入库构件");
        clear(); token = UUID.randomUUID(); assetId = id; primaryRef = primary;
        sourceIndex = index; sourceRef = source == null ? primary : source;
        phase = delete ? Phase.DELETING : Phase.DOWNLOADING; touched = clock.getAsLong();
        if (!delete) bytes = new ByteArrayOutputStream();
        return request(delete ? Action.DELETE : Action.EXPORT, 0);
    }
    public Request accept(Response response) {
        if (response == null || !Objects.equals(token,response.token()) || !busy()) return null;
        touched = clock.getAsLong();
        if (response.reply() == Reply.ERROR) { fail(response.message()); return null; }
        if (phase == Phase.DELETING) {
            if (response.reply() == Reply.DELETED) phase = Phase.DELETED;
            else fail("删除回执无效");
            return null;
        }
        byte[] data = response.data();
        if (response.reply() != Reply.CHUNK || response.length() <= 0 || response.length() > ComponentPackageIO.MAX_PACKAGE_BYTES
                || (length != 0 && length != response.length()) || response.offset() != bytes.size()
                || data.length == 0 || data.length > ComponentExportSessions.CHUNK_BYTES || (long)bytes.size()+data.length > response.length()) {
            fail("导出分块无效"); return null;
        }
        length = response.length(); bytes.writeBytes(data);
        if (bytes.size() == length) { phase = Phase.READY; return null; }
        return request(Action.NEXT,bytes.size());
    }
    public boolean expire() {
        if (busy() && clock.getAsLong()-touched >= 120_000_000_000L) { fail("构件操作超时，请刷新确认结果后重试"); return true; }
        return false;
    }
    public Request cancellation() { return token == null ? null : request(Action.CANCEL,0); }
    private Request request(Action action,int offset) { return new Request(token,action,assetId,primaryRef,sourceIndex,sourceRef,offset); }
    public boolean busy() { return phase == Phase.DELETING || phase == Phase.DOWNLOADING; }
    public Phase phase() { return phase; }
    public String assetId() { return assetId; }
    public String sourceRef() { return sourceRef; }
    public String message() { return message; }
    public byte[] bytes() { return phase == Phase.READY ? bytes.toByteArray() : null; }
    public void fail(String message) { this.message = message; phase = Phase.ERROR; bytes = null; }
    public void clear() { token = null; assetId = primaryRef = sourceRef = null; length = 0; bytes = null; phase = Phase.IDLE; message = ""; }
}
