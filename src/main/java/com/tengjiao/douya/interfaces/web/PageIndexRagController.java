package com.tengjiao.douya.interfaces.web;

import com.tengjiao.douya.infrastructure.oss.OssService;
import com.tengjiao.douya.infrastructure.external.pageindexrag.PageIndexRagClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * PageIndexRAG 脚本工具接口（Java -> PythonTool）
 */
@Slf4j
@RestController
@RequestMapping("/douya/page-index-rag")
@Tag(name = "PageIndexRAG 脚本工具接口")
@RequiredArgsConstructor
public class PageIndexRagController {

    private final PageIndexRagClient pageIndexRagClient;
    private final OssService ossService;

    @GetMapping("/status")
    @Operation(summary = "查询 PageIndexRAG 状态（脚本模式）")
    public Map<String, Object> status() {
        try {
            return pageIndexRagClient.status();
        } catch (Exception e) {
            log.error("page_index_rag_status_failed error={}", e.getMessage(), e);
            return error("STATUS_FAILED", e.getMessage());
        }
    }

    @PostMapping("/ingest")
    @Operation(summary = "调用 PythonTool 执行 PageIndexRAG 入库")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> request) {
        try {
            return pageIndexRagClient.ingest(request);
        } catch (Exception e) {
            log.error("page_index_rag_ingest_failed error={}", e.getMessage(), e);
            return error("INGEST_FAILED", e.getMessage());
        }
    }

    /**
     * 文件入库时的可选文档标识参数说明（四个参数都不是必填）：
     * 1) doc_id：文档唯一标识，用于“更新已存在文档”或“幂等重试”。
     *    - 不传：由后端/脚本侧生成新的文档 ID。
     *    - 传入：通常表示“继续写入同一文档实体”（例如同一文档新版本）。
     * 2) doc_name：文档展示名/逻辑名，用于检索展示、引用输出、排障定位。
     *    - 常见值：原始文件名（如 menu_v2.pdf），或业务可读名（如《门店SOP手册》）。
     * 3) version：文档版本号，用于区分同一 doc_id 的不同版本内容。
     *    - 常见值：v1 / v2 / 2026-03-22 / 20260322_1。
     *    - 典型场景：同一文档修订后重新入库，避免新旧内容混淆。
     * 4) metadata：扩展元数据（建议传 JSON 字符串），用于后续过滤、追踪与审计。
     *    - 为什么传：
     *      a. 提升检索精度：可按 tenant/部门/标签过滤，避免串库召回。
     *      b. 权限隔离：按 acl 控制“谁可以检索到这份文档”。
     *      c. 可追踪与审计：定位上传来源、责任人、变更单。
     *      d. 生命周期管理：按有效期/状态做下线、重建、清理。
     *    - 常见字段及含义：
     *      source：文档来源（manual_upload / ocr / crawler）。
     *      tenant_id：租户/门店ID，用于多租户数据隔离。
     *      department：归属部门（ops/rd/hr...），用于组织维度过滤。
     *      acl：可见范围（角色/组列表），用于权限裁剪。
     *      uploader：上传人，便于排障与责任追踪。
     *      doc_type：文档类型（sop/menu/manual/...），用于检索意图过滤。
     *      biz_line：业务线（restaurant/retail/...），用于跨业务隔离。
     *      tags：业务标签数组（如 ["menu","2026Q1"]），用于灵活检索。
     *      change_ticket：变更单号，便于追溯版本改动来源。
     *      retention_until：保留到期日（如 2026-12-31），用于合规清理。
     *
     * metadata 场景示例（JSON）：
     * 1) 多门店隔离检索：
     *    {"source":"manual_upload","tenant_id":"store_001","department":"ops","acl":["store_001_ops"]}
     * 2) SOP 新旧版本灰度：
     *    {"biz_line":"restaurant","doc_type":"sop","release":"2026Q1","is_active":true,"change_ticket":"CM-2026-0318"}
     * 3) OCR 质量控制：
     *    {"source":"ocr","ocr_confidence":0.86,"language":"zh-CN"}
     * 4) 合规与到期治理：
     *    {"compliance":"internal","retention_until":"2026-12-31","owner":"zhangsan"}
     *
     * 示例1：首次入库（不指定 doc_id，让系统自动生成）
     * curl -X POST 'http://127.0.0.1:8787/api/douya/page-index-rag/ingest/file' \
     *   -F 'file=@/path/to/menu.pdf' \
     *   -F 'doc_name=门店菜单.pdf' \
     *   -F 'version=v1' \
     *   -F 'metadata={"source":"ops","department":"beijing","tags":["menu","2026Q1"]}'
     *
     * 示例2：对同一文档做新版本入库（复用 doc_id）
     * curl -X POST 'http://127.0.0.1:8787/api/douya/page-index-rag/ingest/file' \
     *   -F 'file=@/path/to/menu_v2.pdf' \
     *   -F 'doc_id=doc_menu_001' \
     *   -F 'doc_name=门店菜单.pdf' \
     *   -F 'version=v2' \
     *   -F 'metadata={"source":"ops","change_ticket":"CM-2026-0318"}'
     */
    @PostMapping(value = "/ingest/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "调用 PythonTool 执行 PageIndexRAG 文件入库")
    public Map<String, Object> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "doc_id", required = false) String docId,
            @RequestParam(value = "doc_name", required = false) String docName,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "metadata", required = false) String metadata
    ) {
        try {
            return pageIndexRagClient.ingestFile(file, docId, docName, version, metadata);
        } catch (Exception e) {
            log.error("page_index_rag_ingest_file_failed error={}", e.getMessage(), e);
            return error("INGEST_FILE_FAILED", e.getMessage());
        }
    }

    @PostMapping(value = "/assets/upload-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "PageIndexRAG 图片上传 OSS（供 Python OCR 管道调用）")
    public Map<String, Object> uploadImageAsset(@RequestBody Map<String, String> request) {
        try {
            String fileName = normalizeFileName(request.get("file_name"));
            String contentBase64 = request.getOrDefault("content_base64", "");
            String documentName = normalizeFileName(request.getOrDefault("document_name", "page_index_rag"));
            if (contentBase64.isBlank()) {
                return error("UPLOAD_IMAGE_FAILED", "content_base64 不能为空");
            }

            byte[] imageBytes = Base64.getDecoder().decode(contentBase64);
            String objectName = String.format("documents/page-index-rag/%s/%s", documentName, fileName);
            String ossUrl;
            if (ossService.doesObjectExist(objectName)) {
                ossUrl = ossService.getFileUrl(objectName);
            } else {
                ossUrl = ossService.uploadFile(objectName, new ByteArrayInputStream(imageBytes));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("file_name", fileName);
            result.put("object_name", objectName);
            result.put("oss_url", ossUrl);
            return result;
        } catch (Exception e) {
            log.error("page_index_rag_upload_image_failed error={}", e.getMessage(), e);
            return error("UPLOAD_IMAGE_FAILED", e.getMessage());
        }
    }

    @PostMapping("/query")
    @Operation(summary = "调用 PythonTool 执行 PageIndexRAG 查询")
    public Map<String, Object> query(@RequestBody Map<String, Object> request) {
        try {
            return pageIndexRagClient.query(request);
        } catch (Exception e) {
            log.error("page_index_rag_query_failed error={}", e.getMessage(), e);
            return error("QUERY_FAILED", e.getMessage());
        }
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("status", "FAILED");
        map.put("error", message);
        return map;
    }

    private String normalizeFileName(String raw) {
        String name = (raw == null || raw.isBlank()) ? "image.png" : raw;
        String normalized = name.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (normalized.isBlank()) {
            return "image.png";
        }
        return normalized;
    }
}
