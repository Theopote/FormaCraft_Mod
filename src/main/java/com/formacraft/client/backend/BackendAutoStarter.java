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
    private static volatile String lastError = null;
    private static volatile long lastAttemptMs = 0L;
    private static final long MIN_ATTEMPT_INTERVAL_MS = 10_000L;

    /**
     * 注意：默认 HttpClient 可能尝试 h2c upgrade（HTTP/2 over cleartext），
     * uvicorn 会打印 "Unsupported upgrade request."。
     * 这里强制 HTTP/1.1，避免日志刷屏。
     */
    private static final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
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
        String endpointRaw = (cfg.orchestratorEndpoint == null) ? "" : cfg.orchestratorEndpoint.trim();
        if (!isLocalhostEndpoint(endpointRaw, cfg.backendPort)) return;

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < MIN_ATTEMPT_INTERVAL_MS) return;
        lastAttemptMs = now;

        String endpointBase = normalizeBaseEndpointForHealth(endpointRaw, cfg.backendPort);

        if (isHealthy(endpointBase)) {
            lastError = null;
            log("health ok, skip start. endpoint=" + endpointBase);
            return;
        }

        if (!starting.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                // double-check
                if (isHealthy(endpointBase)) return;
                lastError = null;
                startProcess(cfg);

                // 如果连进程都没拉起，就别再输出“started but ...”这种误导信息
                Process bp = backendProcess;
                if (bp == null || !bp.isAlive()) {
                    if (lastError == null || lastError.isBlank()) {
                        lastError = "backend process not started (python/py not found or uvicorn missing)";
                    }
                    log("backend not started: " + lastError);
                    return;
                }

                // 启动后最多等 10 秒轮询健康（避免“启动失败但用户无感”）
                long deadline = System.currentTimeMillis() + 10_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (isHealthy(endpointBase)) {
                        lastError = null;
                        log("backend became healthy: " + endpointBase);
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!isHealthy(endpointBase)) {
                    lastError = "started but /health still not reachable: " + endpointBase;
                    log(lastError);
                }
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

    private static boolean isHealthy(String endpointBase) {
        try {
            String base = endpointBase;
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

    /**
     * 将 orchestratorEndpoint 规整为用于健康检查的“基址”（只保留 scheme://host:port）。
     * 例如：
     * - http://localhost:8000 -> http://localhost:8000
     * - http://localhost:8000/models -> http://localhost:8000
     */
    private static String normalizeBaseEndpointForHealth(String endpoint, int port) {
        int pDefault = port > 0 ? port : 8000;
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:" + pDefault;
        }
        String v = endpoint.trim();
        try {
            URI uri = URI.create(v);
            String scheme = (uri.getScheme() == null || uri.getScheme().isBlank()) ? "http" : uri.getScheme();
            String host = uri.getHost();
            int p = uri.getPort();
            if (p <= 0) p = pDefault;

            // 容错：用户可能填了 "localhost:8000"（无 scheme）
            if (host == null || host.isBlank()) {
                if (!v.startsWith("http://") && !v.startsWith("https://")) {
                    URI uri2 = URI.create("http://" + v);
                    scheme = (uri2.getScheme() == null || uri2.getScheme().isBlank()) ? "http" : uri2.getScheme();
                    host = uri2.getHost();
                    p = uri2.getPort();
                    if (p <= 0) p = pDefault;
                }
            }

            if (host == null || host.isBlank()) {
                return "http://localhost:" + p;
            }
            return scheme + "://" + host + ":" + p;
        } catch (Exception e) {
            return "http://localhost:" + pDefault;
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

    /**
     * 检测虚拟环境并返回虚拟环境中的 Python 可执行文件路径
     * 支持的虚拟环境类型：venv, .venv, env, virtualenv
     * @param workDir 工作目录（python_backend）
     * @return 虚拟环境中的 Python 可执行文件，如果未找到则返回 null
     */
    private static String findVirtualEnvPython(File workDir) {
        if (workDir == null || !workDir.exists()) return null;
        
        // 检查工作目录内的虚拟环境
        String[] venvNames = {"venv", ".venv", "env", "virtualenv"};
        for (String venvName : venvNames) {
            File venvDir = new File(workDir, venvName);
            if (venvDir.exists() && venvDir.isDirectory()) {
                // Windows: Scripts\python.exe, Unix: bin/python
                String[] pythonPaths = {
                    venvName + File.separator + "Scripts" + File.separator + "python.exe",
                    venvName + File.separator + "bin" + File.separator + "python",
                    venvName + File.separator + "Scripts" + File.separator + "python",
                };
                for (String pythonPath : pythonPaths) {
                    File pythonExe = new File(workDir, pythonPath);
                    if (pythonExe.exists() && pythonExe.canExecute()) {
                        return pythonExe.getAbsolutePath();
                    }
                }
            }
        }
        
        // 检查工作目录的父目录中的虚拟环境（常见于项目根目录有 venv）
        File parent = workDir.getParentFile();
        if (parent != null) {
            for (String venvName : venvNames) {
                File venvDir = new File(parent, venvName);
                if (venvDir.exists() && venvDir.isDirectory()) {
                    String[] pythonPaths = {
                        ".." + File.separator + venvName + File.separator + "Scripts" + File.separator + "python.exe",
                        ".." + File.separator + venvName + File.separator + "bin" + File.separator + "python",
                        ".." + File.separator + venvName + File.separator + "Scripts" + File.separator + "python",
                    };
                    for (String pythonPath : pythonPaths) {
                        File pythonExe = new File(workDir, pythonPath);
                        if (pythonExe.exists() && pythonExe.canExecute()) {
                            return pythonExe.getAbsolutePath();
                        }
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * 检测 Python 可执行文件是否可用（通过运行 --version）
     * @param pythonPath Python 可执行文件路径
     * @return true 如果可用，false 如果不可用
     */
    private static boolean testPythonExecutable(String pythonPath) {
        if (pythonPath == null || pythonPath.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void startProcess(SettingsConfig cfg) {
        // 已有进程且还活着：不重复启动
        Process p = backendProcess;
        if (p != null && p.isAlive()) return;

        String pythonConfigured = (cfg.pythonExecutable == null) ? "" : cfg.pythonExecutable.trim();
        int port = cfg.backendPort > 0 ? cfg.backendPort : 8000;

        File wd = resolveWorkDir(cfg.backendWorkDir);
        if (!wd.exists()) {
            lastError = "backendWorkDir not found: " + wd.getAbsolutePath()
                    + " (configured=" + cfg.backendWorkDir + ", gameDir=" + safeGameDir() + ")";
            log(lastError);
            return;
        }

        try {
            File logsDir = new File("logs");
            logsDir.mkdirs();
            File logFile = new File(logsDir, "formacraft_orchestrator.log");

            // 构建 Python 候选列表（优先级：配置 > 虚拟环境 > 系统 Python）
            List<String> pythonCandidates = new ArrayList<>();
            
            // 1. 如果配置了，优先使用配置的
            if (!pythonConfigured.isBlank()) {
                pythonCandidates.add(pythonConfigured);
            } else {
                // 2. 尝试检测虚拟环境
                String venvPython = findVirtualEnvPython(wd);
                if (venvPython != null) {
                    log("detected virtual environment Python: " + venvPython);
                    pythonCandidates.add(venvPython);
                }
                
                // 3. 系统 Python（Windows 兼容：python 和 py）
                pythonCandidates.add("python");
                pythonCandidates.add("py");
                // Linux/Mac 可能还有 python3
                if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
                    pythonCandidates.add("python3");
                }
            }

            IOException lastIo = null;
            String lastTestedPython = null;
            for (String py : pythonCandidates) {
                lastTestedPython = py;
                
                // 先测试 Python 可执行文件是否可用（仅对路径形式的，不对命令形式的）
                if (py.contains(File.separator) || py.contains("/") || py.contains("\\")) {
                    if (!testPythonExecutable(py)) {
                        log("Python executable not available: " + py);
                        continue;
                    }
                }
                
                List<String> cmd = new ArrayList<>();
                cmd.add(py);
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
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(wd);
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

                    log("starting: " + String.join(" ", cmd) + " (cwd=" + wd.getAbsolutePath() + ")");
                    backendProcess = pb.start();
                    lastError = null;

                    // 异步监控：如果子进程很快退出，把退出码记到 autostart 日志里（否则用户只能猜）
                    Process proc = backendProcess;
                    CompletableFuture.runAsync(() -> {
                        try {
                            int code = proc.waitFor();
                            String msg = "backend process exited. code=" + code + " (see logs/formacraft_orchestrator.log)";
                            lastError = msg;
                            log(msg);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            logErr("failed while waiting backend process", e);
                        }
                    });
                    break;
                } catch (IOException e) {
                    lastIo = e;
                    backendProcess = null;
                    logErr("failed to start backend with '" + py + "'. cwd=" + wd.getAbsolutePath(), e);
                }
            }

            if (backendProcess == null && lastIo != null) {
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("无法启动后端服务。");
                errorMsg.append("\n已尝试的 Python 可执行文件：");
                for (String py : pythonCandidates) {
                    errorMsg.append("\n  - ").append(py);
                }
                errorMsg.append("\n\n可能的原因：");
                errorMsg.append("\n1. Python 未安装或未添加到 PATH");
                errorMsg.append("\n2. uvicorn 未安装（运行：pip install uvicorn fastapi）");
                errorMsg.append("\n3. 虚拟环境未激活或配置不正确");
                errorMsg.append("\n\n解决方案：");
                errorMsg.append("\n1. 在设置中配置正确的 pythonExecutable 路径");
                errorMsg.append("\n2. 确保在 python_backend 目录中安装了依赖：pip install -r requirements.txt");
                errorMsg.append("\n3. 如果使用虚拟环境，确保虚拟环境已创建并激活");
                if (lastTestedPython != null) {
                    errorMsg.append("\n\n最后尝试的 Python：").append(lastTestedPython);
                }
                if (lastIo != null) {
                    errorMsg.append("\n错误详情：").append(lastIo.getMessage());
                }
                lastError = errorMsg.toString();
                log(lastError);
                return;
            }

            // Minecraft 退出时尽量清理
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Process bp = backendProcess;
                    if (bp != null && bp.isAlive()) {
                        bp.destroy();
                    }
                } catch (Exception ignored) {}
            }));
        } catch (Exception e) {
            lastError = "failed to start backend: " + e.getMessage();
            logErr("failed to start backend. cwd=" + wd.getAbsolutePath(), e);
        }
    }

    private static String safeGameDir() {
        try {
            return FabricLoader.getInstance().getGameDir().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static boolean isStarting() {
        return starting.get();
    }

    public static String getLastError() {
        return lastError;
    }
}


