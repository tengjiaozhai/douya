package com.tengjiao.douya.infrastructure.external.feishu;



import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 飞书获取消息工具类
 *
 * @author tengjiao
 * @since 2025-12-29 17:09
 */

public class FeiShuGetMessageResourceUtils {

    public static GetMessageResourceResp getMessageResource(String appId,String appSecret,String messageId,String fileKey,String type,String path) throws Exception {
        // 构建client
        Client client = Client.newBuilder(appId, appSecret).build();

        // 创建请求对象
        GetMessageResourceReq req = GetMessageResourceReq.newBuilder()
            .messageId(messageId)
            .fileKey(fileKey)
            .type(type)
            .build();

        // 发起请求
        GetMessageResourceResp resp = client.im().v1().messageResource().get(req);

        // 业务数据处理
        resp.writeFile(path);
        return resp;
    }
}
