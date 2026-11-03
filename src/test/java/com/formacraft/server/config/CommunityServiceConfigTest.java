package com.formacraft.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityServiceConfigTest {
    @TempDir Path temp;
    private static final String OFF="{\"schema\":\"formacraft.community.service.v1\",\"enabled\":false}";
    private static String enabled(String base) {
        return "{\"schema\":\"formacraft.community.service.v1\",\"enabled\":true,\"authorityId\":\"test\",\"baseUri\":\""+base+"\"}";
    }
    private static CommunityServiceConfig decode(String json) throws IOException { return CommunityServiceConfig.decode(json.getBytes(StandardCharsets.UTF_8)); }
    private static void invalid(String json) {
        assertEquals("community_config_invalid",assertThrows(IOException.class,()->decode(json)).getMessage());
    }
    @Test void explicitEnableAndDisableUseTransportPolicyWithoutNetwork() throws Exception {
        assertEquals(CommunityServiceConfig.disabled(),decode(OFF));
        for(String uri:new String[]{"https://example.org/api/","http://127.0.0.1:8080/","http://[::1]:8080/"}) {
            var config=decode(enabled(uri)); assertTrue(config.enabled()); assertEquals("test",config.authorityId()); assertEquals(uri,config.baseUri().toString());
        }
        assertFalse(decode(enabled("https://example.org/").replace("true","false")).enabled());
    }
    @Test void rejectsUnsafeOrUnsupportedEndpointsEvenWhenDisabled() {
        for(String uri:new String[]{"http://example.org/","http://localhost/","file:///tmp/","https://user:pass@example.org/",
                "https://example.org/?key=secret","https://example.org/#fragment","https://example.org/a/../b/",
                "https://example.org/%2f/","https://example.org//bad/","https://example.org:0/","https://example.org:65536/"}) {
            invalid(enabled(uri)); invalid(enabled(uri).replace("true","false"));
        }
    }
    @Test void strictSchemaAndTypesNeverCoerceOrIgnoreConditions() {
        for(String json:new String[]{"{}","[]","null",OFF.replace("false","\"false\""),OFF.replace("false","0"),OFF.replace("false","null"),
                OFF.replace("service.v1","service.v2"),OFF.replace("\"enabled\":false", "\"enabled\":false,\"enabled\":true"),
                OFF.replace("\"enabled\":false", "\"enabled\":false,\"unknown\":1"),OFF+" {}","/*comment*/"+OFF,
                OFF.replace("false","true"),enabled("https://example.org/").replace("\"test\"","null"),
                enabled("https://example.org/").replace("\"test\"","\"../test\""),enabled("https://example.org/").replace("\"test\"","\"\\uD800\""),
                "\ufeff"+OFF}) invalid(json);
    }
    @Test void byteAndStringLimitsRejectMalformedUtf8AndLargeInputs() throws Exception {
        assertThrows(IOException.class,()->CommunityServiceConfig.decode(new byte[CommunityServiceConfig.MAX_BYTES+1]));
        assertThrows(IOException.class,()->CommunityServiceConfig.decode(new byte[]{(byte)0xFF}));
        assertThrows(IOException.class,()->CommunityServiceConfig.decode(null));
        invalid(enabled("https://example.org/"+"a".repeat(2048)));
        invalid(enabled("https://example.org/").replace("\"test\"","\""+"a".repeat(129)+"\""));
        assertEquals(CommunityServiceConfig.disabled(),decode(OFF+" ".repeat(CommunityServiceConfig.MAX_BYTES-OFF.length())));
    }
    @Test void missingConfigurationStaysDisabledWithoutCreatingDirectories() throws Exception {
        assertEquals(CommunityServiceConfig.disabled(),CommunityServiceConfig.load(temp)); assertFalse(Files.exists(temp.resolve("formacraft")));
        Files.createDirectories(temp.resolve("formacraft/community"));
        assertEquals(CommunityServiceConfig.disabled(),CommunityServiceConfig.load(temp)); assertFalse(Files.exists(temp.resolve("formacraft/community/service.json")));
    }
    @Test void boundedFileReadLoadsValidConfigAndRejectsWrongKindsAndOversize() throws Exception {
        Path folder=Files.createDirectories(temp.resolve("formacraft/community")), file=folder.resolve("service.json");
        Files.writeString(file,enabled("https://example.org/api/")); assertTrue(CommunityServiceConfig.load(temp).enabled());
        Files.writeString(file,"x".repeat(CommunityServiceConfig.MAX_BYTES+1)); assertThrows(IOException.class,()->CommunityServiceConfig.load(temp));
        Files.delete(file); Files.createDirectory(file); assertThrows(IOException.class,()->CommunityServiceConfig.load(temp));
        Path other=Files.createDirectory(temp.resolve("other")); Files.writeString(other.resolve("formacraft"),"not a directory");
        assertThrows(IOException.class,()->CommunityServiceConfig.load(other));
    }
    @Test void rejectsSymlinkedConfigurationAndParent() throws Exception {
        Path file=Files.writeString(temp.resolve("external.json"),OFF);
        Path folder=Files.createDirectories(temp.resolve("formacraft/community"));
        try { Files.createSymbolicLink(folder.resolve("service.json"),file); }
        catch(UnsupportedOperationException | FileSystemException unsupported) { org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symbolic links unavailable: "+unsupported.getClass().getSimpleName()); }
        assertThrows(IOException.class,()->CommunityServiceConfig.load(temp));
        Path world=Files.createDirectory(temp.resolve("world")); Files.createSymbolicLink(world.resolve("formacraft"),temp.resolve("formacraft"));
        assertThrows(IOException.class,()->CommunityServiceConfig.load(world));
    }
}
