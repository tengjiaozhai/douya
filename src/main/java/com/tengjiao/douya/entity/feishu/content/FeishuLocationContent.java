package com.tengjiao.douya.entity.feishu.content;



import lombok.Data;

/**
 * 位置消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuLocationContent {
    /**
     * 位置名称
     */
    private String name;

    /**
     * 经度
     */
    private String longitude;

    /**
     * 纬度
     */
    private String latitude;
}
