package com.formacraft.common.component.community;

import com.formacraft.common.component.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static com.formacraft.common.component.community.CommunityJson.*;

/** Bounded confirmation text, never a package or an authorization supplied by the client. */
public final class CommunityInstallDocument {
    private CommunityInstallDocument() {}
    private static final String SCHEMA="formacraft.community.install.v1";
    public static final int MAX_BYTES=32768;
    public record Preview(String name,int width,int height,int depth,int blocks,String author,String license,
                          String trainingPermission,String authority,String releaseId,String receiptId) {}
    public record Installed(String assetId,String name,String status,String receiptId) {}
    public static String preview(CommunityDownloadCache.Cached cached) throws IOException {
        var d=cached.download().verified().component(); var m=cached.details().manifest(); var ref=cached.details().descriptor().releaseRef();
        var json=root("PREVIEW"); json.addProperty("name",name(d.name));
        json.addProperty("width",d.size.w); json.addProperty("height",d.size.h); json.addProperty("depth",d.size.d); json.addProperty("blocks",d.blocks.size());
        json.addProperty("author",m.author()); json.addProperty("license",m.license()); json.addProperty("trainingPermission",m.trainingPermission().name());
        json.addProperty("authority",ref.authorityId()); json.addProperty("releaseId",ref.releaseId()); json.addProperty("receiptId",cached.receiptId());
        String result=json.toString(); decodePreview(result.getBytes(StandardCharsets.UTF_8)); return result;
    }
    public static String installed(ComponentSourceBinding.Selection selected) throws IOException {
        var json=root("INSTALLED"); json.addProperty("assetId",selected.assetId()); json.addProperty("name",name(selected.source().component().name));
        json.addProperty("status",selected.status().name()); json.addProperty("receiptId",selected.receipt().receiptId());
        String result=json.toString(); decodeInstalled(result.getBytes(StandardCharsets.UTF_8)); return result;
    }
    private static JsonObject root(String kind) { var root=new JsonObject(); root.addProperty("schema",SCHEMA); root.addProperty("kind",kind); return root; }
    private static String name(String value) {
        if(value==null || value.isBlank()) return "未命名构件";
        int end=Math.min(value.length(),256);
        if(end<value.length() && Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end);
    }
    private static JsonObject decode(byte[] bytes,String kind,String...fields) throws IOException {
        var root=read(bytes,MAX_BYTES); CommunityReleaseDetails.exact(root,fields);
        if(!SCHEMA.equals(text(root.get("schema"),64)) || !kind.equals(text(root.get("kind"),16))) throw new IOException("Invalid installation document");
        receipt(root); return root;
    }
    private static String receipt(JsonObject root) throws IOException {
        String id=text(root.get("receiptId"),72); if(!id.matches("receipt_[0-9a-f]{64}")) throw new IOException("Invalid receipt"); return id;
    }
    public static Preview decodePreview(byte[] bytes) throws IOException {
        var r=decode(bytes,"PREVIEW","schema","kind","name","width","height","depth","blocks","author","license","trainingPermission","authority","releaseId","receiptId");
        String training=text(r.get("trainingPermission"),32), authority=text(r.get("authority"),128), release=text(r.get("releaseId"),128);
        try { ComponentPackageIO.TrainingPermission.valueOf(training); } catch(IllegalArgumentException invalid) { throw new IOException("Invalid permission"); }
        if(!authority.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || !release.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) throw new IOException("Invalid release");
        return new Preview(text(r.get("name"),256),integer(r.get("width"),1,Integer.MAX_VALUE),integer(r.get("height"),1,Integer.MAX_VALUE),
                integer(r.get("depth"),1,Integer.MAX_VALUE),integer(r.get("blocks"),0,Integer.MAX_VALUE),text(r.get("author"),256),text(r.get("license"),2048),training,authority,release,receipt(r));
    }
    public static Installed decodeInstalled(byte[] bytes) throws IOException {
        var r=decode(bytes,"INSTALLED","schema","kind","assetId","name","status","receiptId");
        String asset=text(r.get("assetId"),128), status=text(r.get("status"),64);
        if(!ComponentIds.isSafeId(asset)) throw new IOException("Invalid asset");
        try { ComponentPackageInstallations.ImportStatus.valueOf(status); } catch(IllegalArgumentException invalid) { throw new IOException("Invalid import status"); }
        return new Installed(asset,text(r.get("name"),256),status,receipt(r));
    }
}
