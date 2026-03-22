package com.tengjiao.douya.infrastructure.external.pageindexrag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tengjiao.douya.infrastructure.config.PageIndexRagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PageIndexRAG PythonTool 脚本客户端（本地进程调用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageIndexRagClient {

    private static final String ENV_PYTHON_COMMAND = "PAGE_INDEX_RAG_PYTHON_COMMAND";
    private static final String ENV_PYTHON_EXECUTABLE = "PAGE_INDEX_RAG_PYTHON_EXECUTABLE";
    private static final String ENV_QUERY_SCRIPT = "PAGE_INDEX_RAG_QUERY_SCRIPT";
    private static final String ENV_INGEST_SCRIPT = "PAGE_INDEX_RAG_INGEST_SCRIPT";
    private static final String ENV_INGEST_FILE_SCRIPT = "PAGE_INDEX_RAG_INGEST_FILE_SCRIPT";
    private static final String ENV_STATUS_SCRIPT = "PAGE_INDEX_RAG_STATUS_SCRIPT";
    private static final String ENV_DATA_FILE = "PAGE_INDEX_RAG_DATA_FILE";
    private static final String ENV_TIMEOUT_SECONDS = "PAGE_INDEX_RAG_PYTHON_TIMEOUT_SECONDS";

    private final PageIndexRagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> status() throws Exception {
        ensureEnabled();
        String script = resolveEnv(ENV_STATUS_SCRIPT, properties.getStatusScript());
        return runScript(script, Map.of(), "PYTHON_SCRIPT_STATUS_FAILED");
    }

    public Map<String, Object> ingest(Map<String, Object> request) throws Exception {
        ensureEnabled();
        String script = resolveEnv(ENV_INGEST_SCRIPT, properties.getIngestScript());
        return runScript(script, request == null ? Map.of() : request, "PYTHON_SCRIPT_INGEST_FAILED");
    }

    public Map<String, Object> query(Map<String, Object> request) throws Exception {
        ensureEnabled();
        String script = resolveEnv(ENV_QUERY_SCRIPT, properties.getQueryScript());
        return runScript(script, request == null ? Map.of() : request, "PYTHON_SCRIPT_QUERY_FAILED");
    }

    public Map<String, Object> ingestFile(
            MultipartFile file,
            String docId,
            String docName,
            String version,
            String metadata
    ) throws Exception {
        ensureEnabled();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("file_name", file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename());
        payload.put("file_base64", Base64.getEncoder().encodeToString(file.getBytes()));

        if (hasText(docId)) {
            payload.put("doc_id", docId.trim());
        }
        if (hasText(docName)) {
            payload.put("doc_name", docName.trim());
        }
        if (hasText(version)) {
            payload.put("version", version.trim());
        }
        if (hasText(metadata)) {
            payload.put("metadata", metadata.trim());
        }

        String script = resolveEnv(ENV_INGEST_FILE_SCRIPT, properties.getIngestFileScript());
        return runScript(script, payload, "PYTHON_SCRIPT_INGEST_FILE_FAILED");
    }

    private Map<String, Object> runScript(String script, Map<String, Object> payload, String defaultErrorCode)
            throws Exception {
        Path scriptPath = resolveScriptPath(script);
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("PageIndexRAG 脚本不存在: " + scriptPath);
        }

        Map<String, Object> requestPayload = enrichDataFile(payload);
        List<String> command = buildPythonCommand(scriptPath);
        log.info("page_index_rag_python_tool_run script={} command={}", scriptPath, command);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(System.getProperty("user.dir")).toFile());
        Process process = processBuilder.start();

        try (var outputStream = process.getOutputStream()) {
            objectMapper.writeValue(outputStream, requestPayload);
            outputStream.flush();
        }

        boolean finished = process.waitFor(resolveTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("PageIndexRAG PythonTool 执行超时");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        if (process.exitValue() != 0) {
            String reason = stderr.isBlank() ? stdout : stderr;
            throw new IllegalStateException(reason.isBlank() ? defaultErrorCode : reason);
        }

        if (stdout.isBlank()) {
            throw new IllegalStateException("PythonTool 返回为空");
        }

        Map<String, Object> result = objectMapper.readValue(stdout, new TypeReference<>() {
        });
        Object code = result.get("code");
        Object status = result.get("status");
        if ("FAILED".equals(String.valueOf(status)) || (code != null && !String.valueOf(code).isBlank())) {
            log.warn("page_index_rag_python_tool_business_failed code={} response={}", code, result);
        }
        return result;
    }

    private List<String> buildPythonCommand(Path scriptPath) {
        List<String> cmd = new ArrayList<>();
        String pythonExecutable = resolveEnv(ENV_PYTHON_EXECUTABLE, properties.getPythonExecutable());
        if (hasText(pythonExecutable)) {
            cmd.add(pythonExecutable.trim());
        } else {
            cmd.add(resolvePythonCommand());
        }
        cmd.add(scriptPath.toString());
        cmd.add("--mode");
        cmd.add("json-stdin");
        return cmd;
    }

    private String resolvePythonCommand() {
        String command = resolveEnv(ENV_PYTHON_COMMAND, properties.getPythonCommand());
        return hasText(command) ? command.trim() : "python3";
    }

    private long resolveTimeoutSeconds() {
        String timeoutText = System.getenv(ENV_TIMEOUT_SECONDS);
        if (hasText(timeoutText)) {
            try {
                long timeout = Long.parseLong(timeoutText.trim());
                if (timeout > 0) {
                    return timeout;
                }
            } catch (NumberFormatException ignored) {
                log.warn("环境变量 {} 不是有效整数: {}", ENV_TIMEOUT_SECONDS, timeoutText);
            }
        }
        return Math.max(1, properties.getPythonTimeoutSeconds());
    }

    private Path resolveScriptPath(String script) {
        if (!hasText(script)) {
            throw new IllegalStateException("PageIndexRAG 脚本路径未配置");
        }
        Path scriptPath = Path.of(script.trim());
        if (scriptPath.isAbsolute()) {
            return scriptPath.normalize();
        }
        return Path.of(System.getProperty("user.dir")).resolve(scriptPath).normalize();
    }

    private Map<String, Object> enrichDataFile(Map<String, Object> payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (payload != null) {
            merged.putAll(payload);
        }
        String dataFile = resolveEnv(ENV_DATA_FILE, properties.getDataFile());
        if (hasText(dataFile) && !merged.containsKey("data_file")) {
            merged.put("data_file", dataFile.trim());
        }
        return merged;
    }

    private String resolveEnv(String envKey, String fallback) {
        String envValue = System.getenv(envKey);
        if (hasText(envValue)) {
            return envValue;
        }
        return fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("page-index-rag.enabled=false, PythonTool is disabled");
        }
    }
}
