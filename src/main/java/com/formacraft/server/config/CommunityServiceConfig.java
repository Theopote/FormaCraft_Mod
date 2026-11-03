package com.formacraft.server.config;

import com.formacraft.common.component.community.CommunitySearchHttpClient;
import com.google.gson.Strictness;
import com.google.gson.stream.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Trusted per-world configuration. Parsing and loading never create files or access the network. */
public record CommunityServiceConfig(boolean enabled,String authorityId,URI baseUri) {
    public static final String SCHEMA="formacraft.community.service.v1";
    public static final int MAX_BYTES=8192;
    public CommunityServiceConfig {
        if ((authorityId==null)!=(baseUri==null) || enabled && authorityId==null) throw new IllegalArgumentException("Invalid community configuration");
        if (authorityId!=null) {
            if(!authorityId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || baseUri.toString().length()>2048) throw new IllegalArgumentException("Invalid community configuration");
            CommunitySearchHttpClient.validateBase(baseUri);
        }
    }
    public static CommunityServiceConfig disabled() { return new CommunityServiceConfig(false,null,null); }
    public static CommunityServiceConfig decode(byte[] bytes) throws IOException {
        try {
            if(bytes==null || bytes.length>MAX_BYTES) throw new IOException();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.startsWith("\ufeff")) throw new IOException();
            try(var reader=new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT); reader.beginObject();
                Set<String> seen=new HashSet<>(); String schema=null,authority=null,base=null; Boolean enabled=null;
                while(reader.hasNext()) {
                    String key=reader.nextName(); if(!seen.add(key)) throw new IOException();
                    switch(key) {
                        case "schema" -> schema=string(reader,64,false);
                        case "enabled" -> { if(reader.peek()!=JsonToken.BOOLEAN) throw new IOException(); enabled=reader.nextBoolean(); }
                        case "authorityId" -> authority=string(reader,128,true);
                        case "baseUri" -> base=string(reader,2048,true);
                        default -> throw new IOException();
                    }
                }
                reader.endObject();
                if(reader.peek()!=JsonToken.END_DOCUMENT || !SCHEMA.equals(schema) || enabled==null) throw new IOException();
                return new CommunityServiceConfig(enabled,authority,base==null ? null : URI.create(base));
            }
        } catch(IOException | RuntimeException invalid) { throw invalid(); }
    }
    private static String string(JsonReader reader,int max,boolean nullable) throws IOException {
        if(nullable && reader.peek()==JsonToken.NULL) { reader.nextNull(); return null; }
        if(reader.peek()!=JsonToken.STRING) throw new IOException();
        String value=reader.nextString();
        if(value.isBlank() || value.length()>max || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c>=0xD800 && c<=0xDFFF)) throw new IOException();
        return value;
    }
    public static CommunityServiceConfig load(Path world) throws IOException {
        try {
            Path root=world.toRealPath();
            for(String child:List.of("formacraft","community")) {
                root=root.resolve(child);
                BasicFileAttributes attributes;
                try { attributes=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
                catch(NoSuchFileException missing) { return disabled(); }
                if(!attributes.isDirectory() || attributes.isSymbolicLink()) throw invalid();
            }
            Path file=root.resolve("service.json"); BasicFileAttributes attributes;
            try { attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
            catch(NoSuchFileException missing) { return disabled(); }
            if(!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size()>MAX_BYTES) throw invalid();
            try(var input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                return decode(input.readNBytes(MAX_BYTES+1));
            }
        } catch(IOException | RuntimeException invalid) { throw invalid(); }
    }
    private static IOException invalid() { return new IOException("community_config_invalid"); }
}
