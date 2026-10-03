package com.formacraft.client.backend;

import com.formacraft.FormacraftMod;
import com.formacraft.config.SettingsConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
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

    private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
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
        FormacraftMod.LOGGER.info("[BackendAutoStarter] {}", msg);
        try {
            File logsDir = new File("logs");
            logsDir.mkdirs();
            File logFile = new File(logsDir, "formacraft_backend_autostart.log");
            try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true)) {
                fw.write("[FormaCraft][BackendAutoStarter] " + msg + System.lineSeparator());
            }
        } catch (Exception ex) {
            FormacraftMod.LOGGER.debug("[BackendAutoStarter] failed to append autostart log file", ex);
        }
    }

    private static String stackTraceString(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder().append(t);
        for (StackTraceElement frame : t.getStackTrace()) {
            sb.append(System.lineSeparator()).append("\tat ").append(frame);
        }
        return sb.toString();
    }

    private static void logErr(String msg, Throwable t) {
        if (t != null) {
            FormacraftMod.LOGGER.warn("[BackendAutoStarter] {}", msg, t);
        } else {
            FormacraftMod.LOGGER.warn("[BackendAutoStarter] {}", msg);
        }
        try {
            File logsDir = new File("logs");
            logsDir.mkdirs();
            File logFile = new File(logsDir, "formacraft_backend_autostart.log");
            try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true)) {
                fw.write("[FormaCraft][BackendAutoStarter] " + msg);
                if (t != null) {
                    fw.write(System.lineSeparator() + stackTraceString(t));
                }
                fw.write(System.lineSeparator());
            }
        } catch (Exception ex) {
            FormacraftMod.LOGGER.debug("[BackendAutoStarter] failed to append autostart error log file", ex);
        }
    }

    private static volatile String status = "尚未检查";
    private static volatile boolean manuallyStopped;

    public static String getStatus() { return status; }
    public static boolean ownsRunningProcess() { return backendProcess != null && backendProcess.isAlive(); }

    public static void ensureStartedAsync() {
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        if (cfg == null || !cfg.autoStartBackend || manuallyStopped || starting.get()) return;
        if (!isLocalhostEndpoint(cfg.orchestratorEndpoint, cfg.backendPort)) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < MIN_ATTEMPT_INTERVAL_MS) return;
        lastAttemptMs = now;
        startAsync(cfg);
    }

    public static CompletableFuture<String> startAsync(SettingsConfig cfg) {
        final String endpoint;
        try { endpoint = LocalBackendLaunch.endpoint(cfg.orchestratorEndpoint, cfg.backendPort); }
        catch (Exception e) { status = e.getMessage(); return CompletableFuture.completedFuture(status); }
        if (!starting.compareAndSet(false, true)) return CompletableFuture.completedFuture("后端操作正在进行");
        manuallyStopped = false;
        var snapshot = new SettingsConfig();
        snapshot.backendWorkDir = cfg.backendWorkDir;
        snapshot.pythonExecutable = cfg.pythonExecutable;
        snapshot.backendPort = cfg.backendPort;
        status = "正在启动／检查后端…";
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (isHealthy(endpoint)) { lastError = null; return status = "后端可用"; }
                lastError = null;
                startProcess(snapshot);
                Process proc = backendProcess;
                if (proc == null || !proc.isAlive())
                    return status = "启动失败：" + (lastError == null ? "请检查后端日志" : lastError);
                long deadline = System.currentTimeMillis() + 15_000;
                while (proc.isAlive() && System.currentTimeMillis() < deadline) {
                    if (isHealthy(endpoint)) { lastError = null; return status = "后端可用（本模组启动）"; }
                    Thread.sleep(500);
                }
                return status = "后端尚未就绪，请检查 logs/formacraft_orchestrator.log";
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = e.getMessage();
                return status = "启动失败：" + lastError;
            } finally { starting.set(false); }
        });
    }

    public static CompletableFuture<String> checkAsync(String endpoint) {
        return CompletableFuture.supplyAsync(() -> status = isHealthy(endpoint)
                ? "后端可用" : "后端不可达，请检查地址或日志");
    }

    public static CompletableFuture<String> stopAsync() {
        if (!starting.compareAndSet(false, true)) return CompletableFuture.completedFuture("后端操作正在进行");
        manuallyStopped = true;
        status = "正在停止后端…";
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process proc = backendProcess;
                if (proc == null || !proc.isAlive()) return status = "没有本模组启动的进程；外部服务请在原窗口停止";
                proc.destroy();
                if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) return status = "停止未完成，请检查日志";
                }
                backendProcess = null;
                lastError = null;
                return status = "后端已停止；点击启动可恢复";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return status = "停止被中断";
            } finally { starting.set(false); }
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
            return resp.statusCode() >= 200 && resp.statusCode() < 300
                    && com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject().get("ok").getAsBoolean();
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
        if (f0.exists() || f0.isAbsolute()) return f0;

        // 以游戏目录为基准（dev 环境一般是 <project>/run）
        try {
            File gameDir = FabricLoader.getInstance().getGameDir().toFile();
            File f1 = new File(gameDir, wd0);
            if (f1.exists()) return f1;

            // dev 常见：gameDir=.../run，后端在上一级的 python_backend
            File parent = gameDir.getParentFile();
            if (parent != null) {
                File f2 = new File(parent, "python_backend");
                if ("python_backend".equals(wd0) && f2.exists()) return f2;
                File f3 = new File(parent, wd0);
                if (f3.exists()) return f3;
            }
        } catch (Exception ex) {
            FormacraftMod.LOGGER.debug("[BackendAutoStarter] resolve backend workdir failed", ex);
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

    /** Python 候选探测结果。 */
    private enum ProbeResult {
        /** 可运行 Python 且能 import uvicorn, fastapi, pydantic, dotenv, requests, openai。 */
        OK,
        /** 能运行 Python，但缺少后端依赖（包括 uvicorn、python-dotenv 等）。 */
        MISSING_DEPENDENCIES,
        /** 不可用（未找到 / Store 占位桩 / 其它错误）。 */
        UNAVAILABLE
    }

    /**
     * 探测某个候选解释器是否真正可用于启动后端。
     * <p>
     * 通过 {@code <py> -c "import uvicorn, fastapi, pydantic, dotenv, requests, openai"} 判定：退出码 0 = 可用；因缺少模块而失败 =
     * {@link ProbeResult#MISSING_DEPENDENCIES}；其它（含 Windows Store 的 python.exe 占位桩返回的
     * 9009）= {@link ProbeResult#UNAVAILABLE}。这样即便某个 {@code python} 能被“启动”，
     * 只要它并非真正的 Python，也不会被误用。
     */
    private static ProbeResult probeInterpreter(String py, File wd) {
        if (py == null || py.isBlank()) return ProbeResult.UNAVAILABLE;
        try {
            ProcessBuilder pb = new ProcessBuilder(py, "-c", "import uvicorn, fastapi, pydantic, dotenv, requests, openai");
            if (wd != null && wd.exists()) pb.directory(wd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            boolean done = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return ProbeResult.UNAVAILABLE;
            }
            String out = new String(proc.getInputStream().readNBytes(4000), java.nio.charset.StandardCharsets.UTF_8);
            int code = proc.exitValue();
            if (code == 0) return ProbeResult.OK;

            String o = out.toLowerCase(java.util.Locale.ROOT);
            // 真正的 Python 跑起来了但缺少 uvicorn：给出精确的“装依赖”提示。
            if (o.contains("modulenotfounderror") || o.contains("no module named")) {
                return ProbeResult.MISSING_DEPENDENCIES;
            }
            // 其它非零退出（例如 Store 桩的 9009、"Python was not found" 等）视为不可用。
            return ProbeResult.UNAVAILABLE;
        } catch (IOException e) {
            return ProbeResult.UNAVAILABLE;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ProbeResult.UNAVAILABLE;
        }
    }

    private static void startProcess(SettingsConfig cfg) {
        // 已有进程且还活着：不重复启动
        Process p = backendProcess;
        if (p != null && p.isAlive()) return;

        String pythonConfigured = (cfg.pythonExecutable == null) ? "" : cfg.pythonExecutable.trim();
        int port = cfg.backendPort > 0 ? cfg.backendPort : 8000;

        File wd = resolveWorkDir(cfg.backendWorkDir);
        if (!wd.isDirectory() || !new File(wd, "app/main.py").isFile()) {
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

            // 先探测每个候选解释器：必须能实际运行 Python 且能 import uvicorn, fastapi, pydantic, dotenv, requests, openai，
            // 才认定可用。这可避开 Windows 的“Microsoft Store python.exe 桩”——
            // 它能被 ProcessBuilder 启动、随即以退出码 9009 结束（并未真正跑 Python）。
            String workingPython = null;
            boolean sawPythonMissingDependencies = false;
            String missingDependenciesPython = null;
            for (String py : pythonCandidates) {
                ProbeResult pr = probeInterpreter(py, wd);
                if (pr == ProbeResult.OK) {
                    workingPython = py;
                    break;
                } else if (pr == ProbeResult.MISSING_DEPENDENCIES) {
                    sawPythonMissingDependencies = true;
                    missingDependenciesPython = py;
                    log("found usable Python but backend dependencies are not importable: " + py);
                } else {
                    log("python candidate not usable (not found / Store stub / error): " + py);
                }
            }

            if (workingPython == null) {
                StringBuilder errorMsg = new StringBuilder();
                if (sawPythonMissingDependencies) {
                    errorMsg.append("找到了 Python，但缺少后端依赖（包括 uvicorn、python-dotenv 等），后端无法启动。");
                    errorMsg.append("\n请在 python_backend 目录安装依赖：");
                    errorMsg.append("\n  ").append(missingDependenciesPython != null ? missingDependenciesPython : "python")
                            .append(" -m pip install -r requirements.lock");
                } else {
                    errorMsg.append("无法启动后端服务：未找到可用的 Python。");
                    errorMsg.append("\n（Windows 提示：命令行里的 python 可能是 Microsoft Store 的占位程序，"
                            + "会以退出码 9009 立即退出，并非真正的 Python。）");
                }
                errorMsg.append("\n\n已尝试：");
                for (String py : pythonCandidates) {
                    errorMsg.append("\n  - ").append(py);
                }
                errorMsg.append("\n\n解决方案（任选其一）：");
                errorMsg.append("\n1. 安装 Python 3 并勾选 \"Add python.exe to PATH\"；");
                errorMsg.append("""
                        
                        2. 在设置面板/配置里把 pythonExecutable 指向具体的 python.exe，例如：\
                        
                           C:\\\\Users\\\\<你>\\\\AppData\\\\Local\\\\Programs\\\\Python\\\\Python312\\\\python.exe""");
                errorMsg.append("\n3. 关闭 Windows「应用执行别名」里的 python.exe / python3.exe 占位项；");
                errorMsg.append("\n4. 在 python_backend 下安装依赖：python -m pip install -r requirements.lock。");
                lastError = errorMsg.toString();
                log(lastError);
                return;
            }

            List<String> cmd = LocalBackendLaunch.command(workingPython, wd, port);

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
                        if (backendProcess == proc) { lastError = msg; status = "后端进程已退出，请检查日志"; }
                        log(msg);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        logErr("failed while waiting backend process", e);
                    }
                });
            } catch (IOException e) {
                backendProcess = null;
                logErr("failed to start backend with '" + workingPython + "'. cwd=" + wd.getAbsolutePath(), e);
                lastError = "failed to start backend with '" + workingPython + "': " + e.getMessage();
                log(lastError);
                return;
            }

            // Minecraft 退出时尽量清理
            if (shutdownHookRegistered.compareAndSet(false, true)) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Process bp = backendProcess;
                    if (bp != null && bp.isAlive()) {
                        bp.destroy();
                    }
                } catch (Exception ex) {
                    FormacraftMod.LOGGER.debug("[BackendAutoStarter] shutdown hook destroy failed", ex);
                }
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


