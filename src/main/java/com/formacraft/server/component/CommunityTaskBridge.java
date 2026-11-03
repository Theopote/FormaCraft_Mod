package com.formacraft.server.component;

import com.formacraft.common.component.community.CommunityPackages;
import com.formacraft.common.network.CommunityTaskNetworking.*;
import com.formacraft.common.network.CommunityTaskNetworking;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Pure in-memory game protocol. Caller supplies authenticated connection UUID, never a payload UUID. */
public final class CommunityTaskBridge implements AutoCloseable {
    public static final int MAX_CONNECTIONS = 64, MAX_UPLOADS = 32, MESSAGES_PER_SECOND = 40;
    private static final class Upload {
        final UUID id; final int length; final Instant expires; final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Upload(UUID id,int length,Instant now) { this.id = id; this.length = length; expires = now.plusSeconds(30); }
    }
    private static final class Result {
        final UUID task; final byte[] bytes; final String sha; int offset;
        Result(UUID task,String json) throws IOException {
            this.task = task; bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > CommunityTaskNetworking.MAX_RESULT) throw failure("response_too_large");
            sha = CommunityPackages.sha256(bytes);
        }
    }
    private static final class Slot {
        UUID session; Upload upload; Result result; Instant window,touched; int messages;
        Slot(Instant now) { window = touched = now; }
    }
    private final CommunityTaskCoordinator tasks;
    private final Clock clock;
    private final Map<UUID,Slot> slots = new HashMap<>();
    public CommunityTaskBridge(CommunityTaskCoordinator tasks,Clock clock) { this.tasks = Objects.requireNonNull(tasks); this.clock = Objects.requireNonNull(clock); }
    public synchronized Response handle(UUID actor,Request request) {
        try {
            Objects.requireNonNull(actor); validate(request); expire();
            var slot = slots.get(actor);
            if (slot == null) {
                if (request.operation() != Operation.OPEN) throw failure("session_expired");
                if (slots.size() >= MAX_CONNECTIONS) throw failure("budget_exceeded");
                slot = new Slot(clock.instant()); slots.put(actor,slot);
            }
            if (!clock.instant().isBefore(slot.window.plusSeconds(1))) { slot.window = clock.instant(); slot.messages = 0; }
            if (slot.messages >= MESSAGES_PER_SECOND) throw failure("rate_limited");
            slot.messages++; slot.touched = clock.instant();
            if (request.operation() == Operation.OPEN) {
                slot.session = tasks.connect(actor); slot.upload = null; slot.result = null;
                return status(request,slot,Reply.OPENED,tasks.snapshot(actor,slot.session));
            }
            if (!Objects.equals(slot.session,request.sessionId()) || slot.session == null) throw failure("session_expired");
            var snapshot = tasks.snapshot(actor,slot.session);
            switch (request.operation()) {
                case SEARCH_BEGIN -> {
                    if (request.length() < 1 || request.length() > CommunityTaskNetworking.MAX_REQUEST) throw failure("invalid_transfer");
                    long count = slots.values().stream().filter(s -> s.upload != null).count();
                    if (slot.upload == null && count >= MAX_UPLOADS) throw failure("budget_exceeded");
                    slot.upload = new Upload(request.subjectId(),request.length(),clock.instant()); slot.result = null;
                    return ack(request,slot.upload,0);
                }
                case SEARCH_CHUNK -> {
                    var upload = upload(slot,request.subjectId()); byte[] data = request.data();
                    if (data.length == 0 || request.offset() != upload.bytes.size() || (long)upload.bytes.size()+data.length > upload.length) {
                        slot.upload = null; throw failure("invalid_transfer");
                    }
                    upload.bytes.writeBytes(data); return ack(request,upload,upload.bytes.size());
                }
                case SEARCH_SUBMIT -> {
                    var upload = upload(slot,request.subjectId());
                    if (upload.bytes.size() != upload.length) { slot.upload = null; throw failure("invalid_transfer"); }
                    byte[] data = upload.bytes.toByteArray(); slot.upload = null; slot.result = null;
                    UUID task = tasks.search(actor,slot.session,data); snapshot = tasks.snapshot(actor,slot.session);
                    if (!task.equals(snapshot.taskId())) throw failure("stale_task");
                    return status(request,slot,Reply.STATUS,snapshot);
                }
                case DOWNLOAD -> {
                    slot.upload = null; slot.result = null;
                    UUID task = tasks.download(actor,slot.session,request.subjectId(),request.releaseId(),request.resumable() ? CommunityTaskCoordinator.Transfer.RESUMABLE : CommunityTaskCoordinator.Transfer.FULL);
                    snapshot = tasks.snapshot(actor,slot.session); if (!task.equals(snapshot.taskId())) throw failure("stale_task");
                    return status(request,slot,Reply.STATUS,snapshot);
                }
                case INSTALL_PREVIEW, INSTALL_CONFIRM -> {
                    slot.upload=null; slot.result=null;
                    UUID task=request.operation()==Operation.INSTALL_PREVIEW ? tasks.preview(actor,slot.session,request.subjectId()) : tasks.install(actor,slot.session,request.subjectId());
                    snapshot=tasks.snapshot(actor,slot.session); if(!task.equals(snapshot.taskId())) throw failure("stale_task");
                    return status(request,slot,Reply.STATUS,snapshot);
                }
                case POLL -> {
                    if (!request.subjectId().equals(snapshot.taskId())) throw failure("stale_task");
                    return status(request,slot,Reply.STATUS,snapshot);
                }
                case RESULT_CHUNK -> {
                    if (!request.subjectId().equals(snapshot.taskId())) throw failure("stale_task");
                    var result = result(slot,snapshot); if (result == null || result.offset != request.offset() || result.offset >= result.bytes.length) throw failure("invalid_transfer");
                    int start = result.offset, end = Math.min(result.bytes.length,start+CommunityTaskNetworking.CHUNK_BYTES); result.offset = end;
                    return new Response(request.token(),Reply.CHUNK,slot.session,result.task,null,start,result.bytes.length,result.sha,Arrays.copyOfRange(result.bytes,start,end),"");
                }
                case CANCEL -> {
                    tasks.cancel(actor,slot.session,request.subjectId()); slot.result = null;
                    return status(request,slot,Reply.STATUS,tasks.snapshot(actor,slot.session));
                }
                case CLOSE -> {
                    tasks.disconnect(actor); slot.upload = null; slot.result = null; slot.session = null;
                    return new Response(request.token(),Reply.CLOSED,request.sessionId(),null,null,0,0,"",null,"");
                }
                default -> throw failure("invalid_request");
            }
        } catch (Exception failed) { return error(request,failed instanceof CommunityPackages.Failure failure ? failure.code() : "invalid_request"); }
    }
    private Upload upload(Slot slot,UUID subject) throws IOException {
        if (slot.upload == null || !slot.upload.id.equals(subject)) throw failure("stale_upload");
        if (!clock.instant().isBefore(slot.upload.expires)) { slot.upload = null; throw failure("upload_expired"); }
        return slot.upload;
    }
    private Response ack(Request request,Upload upload,int offset) { return new Response(request.token(),Reply.ACK,request.sessionId(),upload.id,null,offset,upload.length,"",null,""); }
    private Response status(Request request,Slot slot,Reply reply,CommunityTaskCoordinator.Snapshot snapshot) throws IOException {
        var result = result(slot,snapshot);
        var view = new View(snapshot.taskId(),snapshot.kind() == null ? "" : snapshot.kind().name(),snapshot.status().name(),text(snapshot.code()),text(snapshot.receiptId()),
                snapshot.catalogTaskId(),millis(snapshot.deadline()),millis(snapshot.catalogExpiresAt()),tasks.authorityId());
        return new Response(request.token(),reply,slot.session,snapshot.taskId(),view,0,result == null ? 0 : result.bytes.length,result == null ? "" : result.sha,null,"");
    }
    private Result result(Slot slot,CommunityTaskCoordinator.Snapshot snapshot) throws IOException {
        if (snapshot.kind() == CommunityTaskCoordinator.Kind.DOWNLOAD || snapshot.kind()==null || snapshot.status() != CommunityTaskCoordinator.Status.SUCCEEDED) { slot.result = null; return null; }
        if (snapshot.kind()==CommunityTaskCoordinator.Kind.SEARCH && !clock.instant().isBefore(snapshot.catalogExpiresAt())) { slot.result = null; throw failure("selection_expired"); }
        if (snapshot.kind()==CommunityTaskCoordinator.Kind.PREVIEW && !clock.instant().isBefore(snapshot.deadline())) { slot.result=null; throw failure("preview_expired"); }
        if (slot.result == null || !slot.result.task.equals(snapshot.taskId())) slot.result = new Result(snapshot.taskId(),snapshot.kind()==CommunityTaskCoordinator.Kind.SEARCH ? snapshot.searchJson() : snapshot.document());
        return slot.result;
    }
    private static void validate(Request r) throws IOException {
        if (r.operation() != Operation.SEARCH_CHUNK && r.data().length != 0 || r.operation() != Operation.SEARCH_BEGIN && r.length() != 0
                || r.operation() != Operation.SEARCH_CHUNK && r.operation() != Operation.RESULT_CHUNK && r.offset() != 0
                || r.operation() != Operation.DOWNLOAD && (!r.releaseId().isEmpty() || r.resumable())) throw failure("invalid_request");
        if (r.operation() == Operation.OPEN) { if (r.sessionId() != null || r.subjectId() != null) throw failure("invalid_request"); }
        else if (r.sessionId() == null || r.operation() != Operation.CLOSE && r.subjectId() == null || r.operation() == Operation.CLOSE && r.subjectId() != null)
            throw failure("invalid_request");
        if (r.operation() == Operation.DOWNLOAD && r.releaseId().isEmpty()) throw failure("invalid_selection");
    }
    public static Response error(Request request,String code) {
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) code = "invalid_request";
        return new Response(request.token(),Reply.ERROR,request.sessionId(),request.subjectId(),null,0,0,"",null,code);
    }
    public synchronized void disconnect(UUID owner) { slots.remove(owner); tasks.disconnect(owner); }
    public synchronized void expire() {
        tasks.expire(); Instant now = clock.instant(); var iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var slot = entry.getValue();
            if (slot.upload != null && !now.isBefore(slot.upload.expires)) slot.upload = null;
            if (!now.isBefore(slot.touched.plusSeconds(120))) {
                if (slot.session != null) try { tasks.snapshot(entry.getKey(),slot.session); tasks.disconnect(entry.getKey()); } catch (IOException stale) { /* Never close a newer session. */ }
                iterator.remove();
            }
        }
    }
    @Override public synchronized void close() { slots.clear(); tasks.close(); }
    private static String text(String value) { return value == null ? "" : value; }
    private static long millis(Instant value) { return value == null || value.equals(Instant.MIN) ? 0 : Math.max(0,value.toEpochMilli()); }
    private static CommunityPackages.Failure failure(String code) { return new CommunityPackages.Failure(code); }
}
