package com.tengjiao.douya.entity.feishu.content;

import lombok.Data;

/**
 * 文本消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuTextContent {
    /**
     * 文本内容
     * 注意:
     * - 超链接格式为 [文本](链接)
     * - @ 会被替换为 @_user_X 形式
     * - 粗体、下划线等样式会被忽略
     */
    private String text;
}
