package com.tengjiao.douya.infrastructure.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tengjiao.douya.infrastructure.config.PageIndexRagProperties;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 Python PageIndexRAG 的页级引用查询工具。
 * 调用本地 Python 脚本执行查询，避免通过 HTTP Client 代理调用。
 */
@Slf4j
public class PageIndexRagSearchTool {

    private final PageIndexRagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PageIndexRagSearchTool(PageIndexRagProperties properties) {
        this.properties = properties;
    }

    public record Request(String query, Integer topK, Boolean withDebug) {
    }

    public record Response(String content) {
    }

    public Response search(Request request) {
        String query = request == null ? null : request.query();
        if (query == null || query.isBlank()) {
            return new Response("查询参数不能为空。");
        }
        log.info("[PageIndexRagTool] query={}", query);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("top_k", normalizeTopK(request.topK()));
            payload.put("with_debug", request.withDebug() == null || request.withDebug());

            Map<String, Object> result = runPythonQuery(payload);
            String answer = String.valueOf(result.getOrDefault("answer", ""));
            Object citationsObj = result.get("citations");
            String citations = formatCitations(citationsObj);
            Object debug = result.get("debug");
            log.info("[PageIndexRagTool] hit answerLength={} citations={} debug={}",
                    answer.length(),
                    citations.isBlank() ? 0 : citations.split("\n").length,
                    debug);

            if (answer.isBlank()) {
                return new Response("未在 PageIndexRAG 中找到有效内容。");
            }
            return new Response("根据 PageIndexRAG 检索结果：\n\n" + answer + (citations.isBlank() ? "" : "\n\n引用:\n" + citations));
        } catch (Exception e) {
            log.error("[PageIndexRagTool] query failed", e);
            return new Response("PageIndexRAG 检索失败，建议稍后重试。");
        }
    }

    private Map<String, Object> runPythonQuery(Map<String, Object> payload) throws Exception {
        Path scriptPath = resolveScriptPath(properties.getQueryScript());
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("PageIndexRAG 查询脚本不存在: " + scriptPath);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(buildPythonCommand(scriptPath));
        processBuilder.directory(Path.of(System.getProperty("user.dir")).toFile());
        Process process = processBuilder.start();

        try (var outputStream = process.getOutputStream()) {
            objectMapper.writeValue(outputStream, payload);
            outputStream.flush();
        }

        boolean finished = process.waitFor(properties.getPythonTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("PageIndexRAG 查询脚本执行超时");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            String reason = stderr.isBlank() ? stdout : stderr;
            throw new IllegalStateException("PageIndexRAG 查询脚本执行失败: " + reason);
        }
        if (stdout.isBlank()) {
            throw new IllegalStateException("PageIndexRAG 查询脚本返回为空");
        }
        return objectMapper.readValue(stdout, new TypeReference<>() {
        });
    }

    private List<String> buildPythonCommand(Path scriptPath) {
        List<String> cmd = new ArrayList<>();
        String pythonExec = properties.getPythonExecutable();
        if (pythonExec != null && !pythonExec.isBlank()) {
            cmd.add(pythonExec);
        } else {
            cmd.add(properties.getPythonCommand());
        }
        cmd.add(scriptPath.toString());
        cmd.add("--mode");
        cmd.add("json-stdin");
        return cmd;
    }

    private Path resolveScriptPath(String script) {
        Path scriptPath = Path.of(script);
        if (scriptPath.isAbsolute()) {
            return scriptPath.normalize();
        }
        return Path.of(System.getProperty("user.dir")).resolve(scriptPath).normalize();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return 8;
        }
        return Math.min(topK, 20);
    }

    @SuppressWarnings("unchecked")
    private String formatCitations(Object citationsObj) {
        if (!(citationsObj instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    Object doc = item.get("doc_id");
                    Object page = item.get("page_no");
                    Object chunk = item.get("chunk_id");
                    return String.format("- doc=%s, page=%s, chunk=%s", doc, page, chunk);
                })
                .collect(Collectors.joining("\n"));
    }
}
