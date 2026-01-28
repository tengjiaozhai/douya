package com.tengjiao.douya.entity.agent;

import com.tengjiao.douya.entity.feishu.content.FeishuPostContent;
import lombok.Data;
import java.util.List;

/**
 * 结构化输出结果
 */
@Data
public class StructuredOutputResult {
    /**
     * 智能体思考过程
     */
    private String thoughts;

    /**
     * 建议操作 (例如: "SEND_FEISHU", "ASK_HINT")
     */
    private String action;

    /**
     * 富文本内容
     */
    private FeishuPostContent content;

    /**
     * 提取出的关键词或标签
     */
    private List<String> tags;
}
