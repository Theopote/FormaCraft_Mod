package com.formacraft.client.backend;

import java.net.URI;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Validated local launch target; never interprets user paths as shell commands. */
final class LocalBackendLaunch {
    static String endpoint(String raw, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("后端端口必须为 1–65535");
        String value = raw == null || raw.isBlank() ? "http://localhost:" + port : raw.trim();
        if (!value.contains("://")) value = "http://" + value;
        URI uri = URI.create(value);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || !("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))
            throw new IllegalArgumentException("仅支持启动本机 http://localhost 或 http://127.0.0.1 后端");
        if (uri.getPort() != port)
            throw new IllegalArgumentException("后端地址的端口必须与启动端口一致");
        return "http://" + uri.getHost() + ":" + port;
    }

    static List<String> command(String python, File workDir, int port) {
        var cmd = new ArrayList<>(List.of(python, "-m", "uvicorn", "app.main:app",
                "--host", "127.0.0.1", "--port", Integer.toString(port), "--log-level", "info"));
        if (new File(workDir, ".env").isFile()) cmd.addAll(List.of("--env-file", ".env"));
        return cmd;
    }
}
