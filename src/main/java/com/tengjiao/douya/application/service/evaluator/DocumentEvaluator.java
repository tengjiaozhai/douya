package com.tengjiao.douya.application.service.evaluator;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档评估与重排序服务
 * 负责对检索回来的粗排文档进行精细化评估，挑选最相关的 Top N
 *
 * @author tengjiao
 * @since 2026-01-28
 */
@Slf4j
public class DocumentEvaluator {

    private final ChatModel chatModel;

    private static final String EVALUATOR_SYSTEM_PROMPT = """
            你是一个严谨的文档评估专家。你的任务是根据用户的查询 (Query)，评估给定的文档片段 (Document Chunk) 是否包含能够回答该查询的有价值信息。
            
            评分标准 (0-10分)：
            - 10分：直接、完整地包含了问题的答案（例如具体的食谱步骤、明确的参数）。
            - 7-9分：包含核心信息，但可能需要结合其他片段。
            - 4-6分：提及了相关主题，但信息比较边缘或模糊。
            - 0-3分：完全不相关，或者是噪音数据。
            
            请只输出一个数字作为分数，不要包含任何解释。
            """;

    private static final String EVALUATOR_INSTRUCTION = """
            User Query: {query}
            
            Document Chunk:
            {content}
            
            请评分 (0-10):
            """;

    public DocumentEvaluator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 评估并重排文档
     * @param query 用户查询
     * @param docs 原始文档列表
     * @param topK 需要返回的 Top K
     * @return 重排后的文档列表
     */
    public List<Document> evaluateAndRerank(String query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("[DocumentEvaluator] 开始评估 {} 个文档片段, Query: {}", docs.size(), query);
        Map<Document, Integer> scores = new HashMap<>();

        // 串行或并行评估 (简单起见这里用串行，生产环境建议 CompletableFuture 并行)
        for (Document doc : docs) {
            try {
                int score = evaluateSingle(query, doc.getText());
                scores.put(doc, score);
                log.debug("文档片段评分: {}, 内容摘要: {}...", score, doc.getText().substring(0, Math.min(20, doc.getText().length())));
            } catch (Exception e) {
                log.warn("文档评估失败, 默认给 0 分", e);
                scores.put(doc, 0);
            }
        }

        // 排序：分数降序
        List<Document> sortedDocs = docs.stream()
                .sorted((d1, d2) -> scores.get(d2).compareTo(scores.get(d1)))
                .limit(topK)
                .collect(Collectors.toList());

        log.info("[DocumentEvaluator] 重排完成，保留 Top {}", sortedDocs.size());
        return sortedDocs;
    }

    private int evaluateSingle(String query, String content) {
        String prompt = EVALUATOR_INSTRUCTION
                .replace("{query}", query)
                .replace("{content}", content);

        String result = chatModel.call(new UserMessage(EVALUATOR_SYSTEM_PROMPT + "\n" + prompt));
        try {
            return Integer.parseInt(result.trim());
        } catch (NumberFormatException e) {
            log.warn("评分解析失败: {}", result);
            return 0; // 解析失败视为无关
        }
    }
}
