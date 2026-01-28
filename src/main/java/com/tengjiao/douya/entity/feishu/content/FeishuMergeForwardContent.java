package com.tengjiao.douya.entity.feishu.content;



import lombok.Data;

/**
 * 合并转发消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuMergeForwardContent {
    /**
     * 固定内容: "Merged and Forwarded Message"
     */
    private String content;
}
