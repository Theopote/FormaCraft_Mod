package com.formacraft.client.component;

import com.formacraft.common.component.community.*;
import com.formacraft.common.network.CommunityTaskNetworking;
import com.formacraft.common.network.CommunityTaskNetworking.*;
import java.io.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Client-thread state only. No rendering, filesystem, direct HTTP, or automatic installation. */
public final class CommunityTaskClientState {
    public enum Phase { CLOSED, OPENING, IDLE, UPLOADING, WAITING, FETCHING, READY, ERROR, CLOSING }
    private final LongSupplier clock;
    private Phase phase = Phase.CLOSED;
    private UUID session,task,catalog,uploadId;
    private String authority = "",error = "",receipt = "",kind = "",sha = "";
    private Request pending;
    private boolean sent;
    private long sentAt,nextPoll;
    private byte[] upload;
    private int uploadOffset,resultLength;
    private ByteArrayOutputStream resultBytes;
    private CommunitySearchContract.Request query;
    private CommunitySearchResponse.Decoded result;
    private View view;
    private CommunityInstallDocument.Preview preview;
    private CommunityInstallDocument.Installed installed;
    private CommunityInstallDocument.Installed favorite;
    private CommunityInstallDocument.Preview restoredSource;
    private String pendingRestore;
    private String selectedRelease="";
    public CommunityTaskClientState(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public Phase phase() { return phase; }
    public String error() { return error; }
    public String receiptId() { return receipt; }
    public UUID sessionId() { return session; }
    public UUID taskId() { return task; }
    public View view() { return view; }
    public CommunitySearchResponse.Decoded result() { return result; }
    public CommunityInstallDocument.Preview preview() { return preview; }
    public CommunityInstallDocument.Installed installed() { return installed; }
    public CommunityInstallDocument.Installed recipe() { return favorite; }
    public CommunityInstallDocument.Preview restoredSource() { return restoredSource; }
    public void open() { clear(); phase = Phase.OPENING; issue(Operation.OPEN,null,"",0,0,null,false); }
    public void restoreRecipe(String id) throws IOException {
        if(!com.formacraft.common.component.ComponentRecipeLibrary.isRecipe(id)) throw new IOException("Invalid recipe identity");
        clear(); pendingRestore=id; phase=Phase.OPENING; issue(Operation.OPEN_RESTORE,null,"",0,0,null,false);
    }
    public void search(byte[] bytes) throws IOException {
        if (session == null || phase == Phase.OPENING || phase == Phase.CLOSING) throw new IOException("Community session is not open");
        if (bytes == null || bytes.length == 0 || bytes.length > CommunityTaskNetworking.MAX_REQUEST) throw new IOException("Search size limit");
        byte[] owned = bytes.clone(); var parsed = CommunitySearchContract.decode(owned);
        query = parsed; upload = owned; uploadOffset = 0; uploadId = UUID.randomUUID(); task = catalog = null; preview=null; installed=null; favorite=null; restoredSource=null; selectedRelease="";
        result = null; resultBytes = null; receipt = error = ""; kind = "SEARCH"; phase = Phase.UPLOADING;
        issue(Operation.SEARCH_BEGIN,uploadId,"",0,upload.length,null,false);
    }
    public void download(String releaseId,boolean resumable) throws IOException {
        if (phase != Phase.READY || result == null || catalog == null || result.candidates().stream().noneMatch(candidate -> candidate.releaseRef().releaseId().equals(releaseId)))
            throw new IOException("Candidate is not selected from the current page");
        receipt = error = ""; task = null; kind = "DOWNLOAD"; phase = Phase.WAITING; preview=null; installed=null; favorite=null; restoredSource=null; selectedRelease=releaseId;
        issue(Operation.DOWNLOAD,catalog,releaseId,0,0,null,resumable);
    }
    public void previewInstall() throws IOException {
        if(phase!=Phase.READY || !kind.equals("DOWNLOAD") || receipt.isEmpty() || task==null) throw new IOException("No current download");
        UUID download=task; task=null; kind="PREVIEW"; phase=Phase.WAITING; error="";
        issue(Operation.INSTALL_PREVIEW,download,"",0,0,null,false);
    }
    public void confirmInstall() throws IOException {
        if(phase!=Phase.READY || !kind.equals("PREVIEW") || preview==null || task==null) throw new IOException("No current preview");
        UUID approval=task; task=null; preview=null; kind="INSTALL"; phase=Phase.WAITING; error="";
        issue(Operation.INSTALL_CONFIRM,approval,"",0,0,null,false);
    }
    public void saveRecipe(com.formacraft.common.component.ComponentRecipeLibrary.Spec spec) throws IOException {
        if(phase!=Phase.READY || !Set.of("INSTALL","RECIPE","RESTORE").contains(kind) || installed==null || task==null || receipt.isEmpty()) throw new IOException("No current installed source");
        byte[] options=CommunityRecipeOptions.encode(spec); UUID source=task;
        task=null; favorite=null; kind="RECIPE"; phase=Phase.WAITING; error="";
        issue(Operation.SAVE_RECIPE,source,"",0,0,options,false);
    }
    public void cancel() { close(""); }
    private void close(String reason) {
        if (session == null) { clear(); if (!reason.isEmpty()) fail(reason); return; }
        error = reason; upload = null; resultBytes = null; result = null; receipt = ""; preview=null; installed=null; favorite=null; restoredSource=null; pendingRestore=null; phase = Phase.CLOSING;
        issue(Operation.CLOSE,null,"",0,0,null,false);
    }
    public Request nextRequest() {
        long now = clock.getAsLong();
        if (pending != null && sent && now-sentAt >= 10_000_000_000L) {
            if (phase == Phase.CLOSING) { clear(); fail("request_timeout"); }
            else close("request_timeout");
        }
        if (pending == null && phase == Phase.WAITING && task != null && now >= nextPoll) issue(Operation.POLL,task,"",0,0,null,false);
        if (pending == null || sent) return null;
        sent = true; sentAt = now; return pending;
    }
    public boolean accept(Response response) {
        if (pending == null || !pending.token().equals(response.token())) return false;
        var request = pending;
        boolean opening=request.operation()==Operation.OPEN || request.operation()==Operation.OPEN_RESTORE;
        try {
            if (!opening && !Objects.equals(response.sessionId(),session)) throw invalid();
            if (response.reply() == Reply.ERROR) {
                if (response.view() != null || response.data().length != 0 || response.length() != 0 || response.offset() != 0 || !response.sha256().isEmpty() || response.code().isEmpty()) throw invalid();
                if (!Objects.equals(response.subjectId(),request.subjectId()) || opening && response.sessionId() != null) throw invalid();
                if (request.operation() == Operation.CLOSE) { String reason = error; clear(); if (!reason.isEmpty()) fail(reason); }
                else { if (response.code().equals("session_expired") || response.code().equals("community_disabled")) session = null; fail(response.code()); }
                return true;
            }
            if (!response.code().isEmpty()) throw invalid();
            pending = null;
            switch (request.operation()) {
                case OPEN, OPEN_RESTORE -> {
                    if (response.reply() != Reply.OPENED || response.sessionId() == null || response.subjectId() != null || response.view() == null
                            || !response.view().status().equals("IDLE") || !emptyBody(response)) throw invalid();
                    session = response.sessionId(); authority = response.view().authorityId(); view = response.view(); phase = Phase.IDLE;
                    if(request.operation()==Operation.OPEN_RESTORE) {
                        if(pendingRestore==null) throw invalid(); String id=pendingRestore; pendingRestore=null;
                        kind="RESTORE"; phase=Phase.WAITING; issue(Operation.RESTORE_RECIPE,null,id,0,0,null,false);
                    }
                }
                case SEARCH_BEGIN, SEARCH_CHUNK -> {
                    int expected = request.operation() == Operation.SEARCH_BEGIN ? 0 : request.offset()+request.data().length;
                    if (response.reply() != Reply.ACK || response.view() != null || !uploadId.equals(response.subjectId()) || response.offset() != expected
                            || response.length() != upload.length || response.data().length != 0 || !response.sha256().isEmpty()) throw invalid();
                    uploadOffset = expected;
                    if (uploadOffset == upload.length) issue(Operation.SEARCH_SUBMIT,uploadId,"",0,0,null,false);
                    else issue(Operation.SEARCH_CHUNK,uploadId,"",uploadOffset,0,Arrays.copyOfRange(upload,uploadOffset,Math.min(upload.length,uploadOffset+CommunityTaskNetworking.CHUNK_BYTES)),false);
                }
                case SEARCH_SUBMIT, DOWNLOAD, INSTALL_PREVIEW, INSTALL_CONFIRM, SAVE_RECIPE, RESTORE_RECIPE, POLL, CANCEL -> acceptStatus(request,response);
                case RESULT_CHUNK -> {
                    byte[] chunk = response.data();
                    if (response.reply() != Reply.CHUNK || response.view() != null || !Objects.equals(task,response.subjectId()) || response.length() != resultLength
                            || response.offset() != resultBytes.size() || !sha.equals(response.sha256()) || chunk.length == 0 || (long)resultBytes.size()+chunk.length > resultLength) throw invalid();
                    resultBytes.writeBytes(chunk);
                    if (resultBytes.size() < resultLength) issue(Operation.RESULT_CHUNK,task,"",resultBytes.size(),0,null,false);
                    else {
                        byte[] bytes = resultBytes.toByteArray(); resultBytes = null;
                        if (!CommunityPackages.sha256(bytes).equals(sha)) throw invalid();
                        switch (kind) {
                            case "SEARCH" -> {
                                result = CommunitySearchResponse.decode(bytes, query, authority);
                                catalog = task;
                            }
                            case "PREVIEW" -> {
                                var document = CommunityInstallDocument.decodePreview(bytes);
                                if (!receipt.equals(document.receiptId()) || !authority.equals(document.authority()) || !selectedRelease.equals(document.releaseId()))
                                    throw invalid();
                                preview = document;
                            }
                            case "INSTALL" -> {
                                var document = CommunityInstallDocument.decodeInstalled(bytes);
                                if (!receipt.equals(document.receiptId())) throw invalid();
                                installed = document;
                            }
                            case "RECIPE" -> {
                                var document = CommunityInstallDocument.decodeRecipe(bytes);
                                if (!receipt.equals(document.receiptId())) throw invalid();
                                favorite = document;
                            }
                            case "RESTORE" -> {
                                var document = CommunityInstallDocument.decodeRestored(bytes);
                                if (!receipt.equals(document.installed().receiptId())) throw invalid();
                                installed = document.installed();
                                restoredSource = document.source();
                                selectedRelease = restoredSource.releaseId();
                            }
                            default -> throw invalid();
                        }
                        phase = Phase.READY;
                    }
                }
                case CLOSE -> {
                    if (response.reply() != Reply.CLOSED || response.view() != null || response.subjectId() != null || !emptyBody(response)) throw invalid();
                    String reason = error; clear(); if (!reason.isEmpty()) fail(reason);
                }
            }
            return true;
        } catch (IOException | RuntimeException malformed) { fail("invalid_response"); return true; }
    }
    private void acceptStatus(Request request,Response response) throws IOException {
        var next = response.view();
        if (response.reply() != Reply.STATUS || next == null || next.taskId() == null || !next.taskId().equals(response.subjectId())
                || !next.kind().equals(kind) || !next.authorityId().equals(authority) || response.offset() != 0 || response.data().length != 0) throw invalid();
        if ((request.operation() == Operation.POLL || request.operation() == Operation.CANCEL) && !Objects.equals(task,next.taskId())) throw invalid();
        task = next.taskId(); view = next; upload = null;
        if (next.status().equals("FAILED") || next.status().equals("CANCELLED")) {
            if (next.code().isEmpty() || !emptyBody(response)) throw invalid();
            if(next.status().equals("FAILED") && kind.equals("RECIPE") && next.code().equals("recipe_failed") && installed!=null && !receipt.isEmpty()) {
                error=next.code(); favorite=null; phase=Phase.READY; return;
            }
            fail(next.code()); return;
        }
        if (!next.code().isEmpty()) throw invalid();
        if (next.status().equals("SUCCEEDED")) {
            if (kind.equals("DOWNLOAD")) {
                if (next.receiptId().isEmpty() || !emptyBody(response)) throw invalid(); receipt = next.receiptId(); phase = Phase.READY;
            } else {
                if(response.length()<1 || response.sha256().isEmpty()) throw invalid();
                if(kind.equals("RESTORE")) { if(next.receiptId().isEmpty()) throw invalid(); receipt=next.receiptId(); }
                if(kind.equals("SEARCH")) { if(!Objects.equals(task,next.catalogTaskId()) || !next.receiptId().isEmpty()) throw invalid(); }
                else if(!receipt.equals(next.receiptId()) || receipt.isEmpty() || response.length()>CommunityInstallDocument.MAX_BYTES) throw invalid();
                resultLength = response.length(); sha = response.sha256(); resultBytes = new ByteArrayOutputStream(); phase = Phase.FETCHING;
                issue(Operation.RESULT_CHUNK,task,"",0,0,null,false);
            }
        } else {
            if (!Set.of("QUEUED","RUNNING","CACHING").contains(next.status()) || !emptyBody(response)) throw invalid();
            phase = Phase.WAITING; nextPoll = clock.getAsLong()+500_000_000L;
        }
    }
    private static boolean emptyBody(Response response) { return response.offset() == 0 && response.length() == 0 && response.sha256().isEmpty() && response.data().length == 0; }
    private void issue(Operation operation,UUID subject,String releaseId,int offset,int length,byte[] data,boolean resumable) {
        pending = new Request(UUID.randomUUID(),operation,operation == Operation.OPEN || operation==Operation.OPEN_RESTORE ? null : session,subject,releaseId,offset,length,data,resumable); sent = false;
    }
    public void fail(String code) {
        error = code == null || !code.matches("[a-z][a-z0-9_]{0,63}") ? "invalid_response" : code; phase = Phase.ERROR;
        pending = null; upload = null; resultBytes = null; result = null; catalog = null; receipt = ""; preview=null; installed=null; favorite=null; restoredSource=null; pendingRestore=null; selectedRelease="";
    }
    public void clear() {
        phase = Phase.CLOSED; session = task = catalog = uploadId = null; authority = error = receipt = kind = sha = "";
        pending = null; sent = false; sentAt = nextPoll = 0; upload = null; uploadOffset = resultLength = 0;
        resultBytes = null; query = null; result = null; view = null; preview=null; installed=null; favorite=null; restoredSource=null; pendingRestore=null; selectedRelease="";
    }
    private static IOException invalid() { return new IOException("Invalid community response"); }
}
