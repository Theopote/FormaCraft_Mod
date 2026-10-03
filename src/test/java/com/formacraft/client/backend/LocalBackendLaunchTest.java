package com.formacraft.client.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalBackendLaunchTest {
    @TempDir Path directory;
    @Test void localAddressNormalizesSchemeAndHealthBase() {
        assertEquals("http://localhost:8000",LocalBackendLaunch.endpoint("localhost:8000/build",8000));
        assertEquals("http://127.0.0.1:8123",LocalBackendLaunch.endpoint("http://127.0.0.1:8123/models",8123));
        assertEquals("http://localhost:8000",LocalBackendLaunch.endpoint("",8000));
    }
    @Test void remoteHttpsAndInvalidHostsCannotLaunchLocalProcess() {
        for (String url : new String[]{"https://localhost:8000","http://example.com:8000","http://localhost.evil:8000","http://user@localhost:8000","not a url"})
            assertThrows(IllegalArgumentException.class,()->LocalBackendLaunch.endpoint(url,8000));
    }
    @Test void endpointAndLaunchPortMustMatch() {
        assertThrows(IllegalArgumentException.class,()->LocalBackendLaunch.endpoint("http://localhost:8001",8000));
        assertThrows(IllegalArgumentException.class,()->LocalBackendLaunch.endpoint("http://localhost",8000));
    }
    @Test void invalidPortsAreRejected() {
        for (int port : new int[]{-1,0,65536}) assertThrows(IllegalArgumentException.class,()->LocalBackendLaunch.endpoint("",port));
    }
    @Test void interpreterPathWithSpacesIsOneArgumentAndNoShellIsUsed() {
        var cmd=LocalBackendLaunch.command("C:/Python folder/python.exe",directory.toFile(),8000);
        assertEquals("C:/Python folder/python.exe",cmd.getFirst());
        assertEquals("-m",cmd.get(1)); assertEquals("uvicorn",cmd.get(2));
        assertFalse(cmd.contains("cmd")); assertFalse(cmd.contains("powershell"));
        assertFalse(cmd.contains("--env-file")); assertFalse(cmd.contains("--reload"));
    }
    @Test void existingEnvIsPassedWithoutReadingItsSecrets() throws Exception {
        Files.writeString(directory.resolve(".env"),"TEST_VALUE=placeholder");
        var cmd=LocalBackendLaunch.command("python",directory.toFile(),8123);
        assertEquals(".env",cmd.get(cmd.indexOf("--env-file")+1));
        assertEquals("8123",cmd.get(cmd.indexOf("--port")+1));
        assertFalse(cmd.stream().anyMatch(arg->arg.contains("placeholder")));
    }
    @Test void rejectedManualStartNeverTakesOwnership() {
        var cfg=new com.formacraft.config.SettingsConfig(); cfg.orchestratorEndpoint="http://example.com:8000";
        assertTrue(BackendAutoStarter.startAsync(cfg).join().contains("仅支持"));
        assertFalse(BackendAutoStarter.isStarting()); assertFalse(BackendAutoStarter.ownsRunningProcess());
    }
}
