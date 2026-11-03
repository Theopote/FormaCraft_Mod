package com.formacraft.client.component;

import com.formacraft.common.component.ComponentTypes;
import com.formacraft.common.component.community.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure presentation/query adapter. It never grants install or compatibility permission. */
public final class CommunityBrowserModel {
    private JsonObject submitted;
    public record Row(String releaseId, String title, String type, String author, String license,
                      String trainingPermission, int bytes) {}

    public byte[] search(String typeKey, String tags) throws IOException {
        var query = new JsonObject();
        if (typeKey != null) query.addProperty("exactTypeKey", typeKey);
        var hard = new LinkedHashSet<String>();
        if (tags != null) for (String tag : tags.split("[,，]", -1)) {
            String value = tag.strip(); if (!value.isEmpty()) hard.add(value);
        }
        if (hard.size() > 64) throw new IOException("标签过多");
        var array = new JsonArray(); hard.forEach(array::add); query.add("hardTags",array);
        var root = new JsonObject(); root.addProperty("contract",CommunitySearchContract.CONTRACT);
        root.addProperty("requestId",UUID.randomUUID().toString()); root.addProperty("needId","community-browser");
        root.add("query",query); root.addProperty("pageSize",10);
        byte[] bytes = encode(root); submitted = root.deepCopy(); return bytes;
    }
    /** Cursor is bound to the last submitted conditions, never the edited form. */
    public byte[] nextPage(CommunitySearchResponse.Decoded page) throws IOException {
        if (submitted == null || page == null) throw new IOException("没有可继续的搜索");
        var json = page.json();
        var previous = CommunitySearchContract.decode(encode(submitted));
        if (!previous.queryFingerprint().equals(json.get("queryFingerprint").getAsString())) throw new IOException("搜索条件已变化");
        var cursor = json.get("nextCursor");
        if (cursor == null || cursor.isJsonNull()) throw new IOException("没有下一页");
        var root = submitted.deepCopy(); root.addProperty("requestId",UUID.randomUUID().toString()); root.add("cursor",cursor.deepCopy());
        byte[] bytes = encode(root); submitted = root; return bytes;
    }
    private static byte[] encode(JsonObject root) throws IOException {
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8); CommunitySearchContract.decode(bytes); return bytes;
    }
    public void clear() { submitted = null; }
    public static boolean hasNext(CommunitySearchResponse.Decoded page) {
        if (page == null) return false; var cursor = page.json().get("nextCursor"); return cursor != null && !cursor.isJsonNull();
    }
    public static List<Row> rows(CommunitySearchResponse.Decoded page) {
        if (page == null) return List.of();
        var rows = new ArrayList<Row>(); var items = page.json().getAsJsonArray("items");
        for (int i=0;i<page.candidates().size();i++) {
            var descriptor = page.candidates().get(i); var item = items.get(i).getAsJsonObject().getAsJsonObject("preferredCandidate");
            String key = item.has("type") ? item.getAsJsonObject("type").get("key").getAsString() : "未提供";
            var preset = ComponentTypes.preset(key);
            var rights = item.has("rights") ? item.getAsJsonObject("rights") : new JsonObject();
            rows.add(new Row(descriptor.releaseRef().releaseId(),display(text(item,"title"),120),
                    preset == null ? display(key,80) : preset.type().label(),
                    display(text(item.getAsJsonObject("sourceClaim"),"author"),80),
                    display(text(rights,"license"),256),display(text(rights,"trainingPermission"),32),descriptor.byteLength()));
        }
        return List.copyOf(rows);
    }
    private static String text(JsonObject object,String key) {
        var value = object.get(key); return value == null || value.isJsonNull() ? "未声明" : value.getAsString();
    }
    public static String display(String value,int limit) {
        if (value == null || value.isBlank()) return "未声明";
        var clean = new StringBuilder(); boolean format = false;
        for (int c : value.codePoints().toArray()) {
            if (format) { format=false; continue; }
            if (c == 0xA7) { format=true; continue; }
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) clean.appendCodePoint(c);
        }
        String text=clean.toString().strip(); if (text.isEmpty()) return "未声明";
        return text.codePointCount(0,text.length()) > limit ? text.substring(0,text.offsetByCodePoints(0,limit))+"…" : text;
    }
}
