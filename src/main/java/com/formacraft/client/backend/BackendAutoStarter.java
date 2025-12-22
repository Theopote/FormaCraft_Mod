package com.formacraft.client.backend;

import com.formacraft.config.SettingsConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动启动本地 Python 后端（uvicorn）：
 * - 仅对 localhost 生效
 * - 启动前先探测 /health
 * - 将子进程输出重定向到 logs/formacraft_orchestrator.log
 */
public final class BackendAutoStarter {
    private BackendAutoStarter() {}

    private static final AtomicBoolean starting = new AtomicBoolean(false);
    private static volatile Process backendProcess = null;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    private static void log(String msg) {
        String line = "[FormaCraft][BackendAutoStarter] " + msg;
        System.out.println(line);
        try {
            File logsDir = new File("logs");
            logsDir.mkdirs();
            File logFile = new File(logsDir, "formacraft_backend_autostart.log");
            try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true)) {
                fw.write(line + System.lineSeparator());
            }
        } catch (Exception ignored) {
        }
    }

    private static void logErr(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        log(msg + (t != null ? ("\n" + sw) : ""));
    }

    public static void ensureStartedAsync() {
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        if (cfg == null) return;
        if (!cfg.autoStartBackend) return;

        // 只对 localhost 地址启用自动启动，避免误启动远端
        String endpoint = (cfg.orchestratorEndpoint == null) ? "" : cfg.orchestratorEndpoint.trim();
        if (!isLocalhostEndpoint(endpoint, cfg.backendPort)) return;

        if (isHealthy(endpoint)) {
            log("health ok, skip start. endpoint=" + endpoint);
            return;
        }

        if (!starting.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                // double-check
                if (isHealthy(endpoint)) return;
                startProcess(cfg);
            } finally {
                starting.set(false);
            }
        });
    }

    private static boolean isLocalhostEndpoint(String endpoint, int port) {
        if (endpoint == null || endpoint.isBlank()) return true;
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            int p = uri.getPort();
            if (p <= 0) p = port;
            if (host == null) return false;
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
            return local && (p == port);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHealthy(String endpoint) {
        try {
            String base = endpoint;
            if (base == null || base.isBlank()) base = "http://localhost:8000";
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String url = base + "/health";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static File resolveWorkDir(String configured) {
        String wd0 = (configured == null || configured.isBlank()) ? "python_backend" : configured.trim();
        File f0 = new File(wd0);
        if (f0.exists()) return f0;

        // 以游戏目录为基准（dev 环境一般是 <project>/run）
        try {
            File gameDir = FabricLoader.getInstance().getGameDir().toFile();
            File f1 = new File(gameDir, wd0);
            if (f1.exists()) return f1;

            // dev 常见：gameDir=.../run，后端在上一级的 python_backend
            File parent = gameDir.getParentFile();
            if (parent != null) {
                File f2 = new File(parent, "python_backend");
                if (f2.exists()) return f2;
                File f3 = new File(parent, wd0);
                if (f3.exists()) return f3;
            }
        } catch (Exception ignored) {
        }

        return f0; // 不存在，返回原值用于日志
    }

    private static void startProcess(SettingsConfig cfg) {
        // 已有进程且还活着：不重复启动
        Process p = backendProcess;
        if (p != null && p.isAlive()) return;

        String python = (cfg.pythonExecutable == null || cfg.pythonExecutable.isBlank()) ? "python" : cfg.pythonExecutable.trim();
        int port = cfg.backendPort > 0 ? cfg.backendPort : 8000;

        File wd = resolveWorkDir(cfg.backendWorkDir);
        if (!wd.exists()) {
            log("backendWorkDir not found: " + wd.getAbsolutePath()
                    + " (configured=" + cfg.backendWorkDir + ", gameDir=" + safeGameDir() + ")");
            return;
        }

        List<String> cmd = new ArrayList<>();
        // 兼容：如果 python 不在 PATH，允许用户在 settings 里填 pythonExecutable
        cmd.add(python);
        cmd.add("-m");
        cmd.add("uvicorn");
        cmd.add("app.main:app");
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--log-level");
        cmd.add("info");

        try {
            File logsDir = new File("logs");
            logsDir.mkdirs();
            File logFile = new File(logsDir, "formacraft_orchestrator.log");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(wd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            log("starting: " + String.join(" ", cmd) + " (cwd=" + wd.getAbsolutePath() + ")");
            backendProcess = pb.start();

            // Minecraft 退出时尽量清理
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Process bp = backendProcess;
                    if (bp != null && bp.isAlive()) {
                        bp.destroy();
                    }
                } catch (Exception ignored) {}
            }));
        } catch (IOException e) {
            logErr("failed to start backend. python=" + python + " cwd=" + wd.getAbsolutePath(), e);
        }
    }

    private static String safeGameDir() {
        try {
            return FabricLoader.getInstance().getGameDir().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}


