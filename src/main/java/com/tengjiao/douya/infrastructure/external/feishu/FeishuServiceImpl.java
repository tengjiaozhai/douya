package com.tengjiao.douya.infrastructure.external.feishu;

import com.tengjiao.douya.entity.feishu.FeishuImageUploadResponse;
import com.tengjiao.douya.entity.feishu.FeishuMessageSendRequest;
import com.tengjiao.douya.entity.feishu.FeishuMessageSendResponse;
import com.tengjiao.douya.entity.feishu.FeishuTokenResponse;
import com.tengjiao.douya.infrastructure.config.FeishuProperties;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 飞书服务实现类
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeishuServiceImpl implements FeishuService {

    private final FeishuProperties feishuProperties;

    // 本地缓存 - App Token
    private volatile String cachedAccessToken;
    private volatile long expireTime = 0L;

    // 本地缓存 - Tenant Token
    private volatile String cachedTenantAccessToken;
    private volatile long tenantTokenExpireTime = 0L;

    private final ReentrantLock lock = new ReentrantLock();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getAppAccessToken() {
        long now = System.currentTimeMillis();
        // 检查缓存是否有效 (预留 60 秒缓冲时间)
        if (cachedAccessToken != null && now < expireTime - 60 * 1000) {
            return cachedAccessToken;
        }

        lock.lock();
        try {
            // 双重检查锁定
            now = System.currentTimeMillis();
            if (cachedAccessToken != null && now < expireTime - 60 * 1000) {
                return cachedAccessToken;
            }

            log.info("开始请求飞书 app_access_token...");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("app_id", feishuProperties.getAppId());
            body.put("app_secret", feishuProperties.getAppSecret());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<FeishuTokenResponse> response = restTemplate.postForEntity(feishuProperties.getAppTokenUrl(),
                    request, FeishuTokenResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                FeishuTokenResponse respBody = response.getBody();
                if (respBody.getCode() == 0) {
                    this.cachedAccessToken = respBody.getAppAccessToken();
                    // expire 是秒，转换为毫秒并加上当前时间
                    this.expireTime = now + (respBody.getExpire() * 1000L);
                    log.info("成功获取飞书 app_access_token, 有效期至: {}", this.expireTime);
                    return this.cachedAccessToken;
                } else {
                    log.error("获取飞书 token 失败: code={}, msg={}", respBody.getCode(), respBody.getMsg());
                    throw new RuntimeException("获取飞书 token 失败: " + respBody.getMsg());
                }
            } else {
                log.error("请求飞书接口失败: status={}", response.getStatusCode());
                throw new RuntimeException("请求飞书接口失败");
            }

        } catch (Exception e) {
            log.error("获取飞书 token 异常", e);
            throw new RuntimeException("获取飞书 token 异常", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getTenantAccessToken() {
        long now = System.currentTimeMillis();
        // 检查缓存是否有效 (预留 60 秒缓冲时间)
        if (cachedTenantAccessToken != null && now < tenantTokenExpireTime - 60 * 1000) {
            return cachedTenantAccessToken;
        }

        lock.lock();
        try {
            // 双重检查锁定
            now = System.currentTimeMillis();
            if (cachedTenantAccessToken != null && now < tenantTokenExpireTime - 60 * 1000) {
                return cachedTenantAccessToken;
            }

            log.info("开始请求飞书 tenant_access_token...");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("app_id", feishuProperties.getAppId());
            body.put("app_secret", feishuProperties.getAppSecret());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<FeishuTokenResponse> response = restTemplate
                    .postForEntity(feishuProperties.getTenantTokenUrl(), request, FeishuTokenResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                FeishuTokenResponse respBody = response.getBody();
                if (respBody.getCode() == 0) {
                    this.cachedTenantAccessToken = respBody.getTenantAccessToken();
                    // expire 是秒，转换为毫秒并加上当前时间
                    this.tenantTokenExpireTime = now + (respBody.getExpire() * 1000L);
                    log.info("成功获取飞书 tenant_access_token, 有效期至: {}", this.tenantTokenExpireTime);
                    return this.cachedTenantAccessToken;
                } else {
                    log.error("获取飞书 tenant token 失败: code={}, msg={}", respBody.getCode(), respBody.getMsg());
                    throw new RuntimeException("获取飞书 tenant token 失败: " + respBody.getMsg());
                }
            } else {
                log.error("请求飞书接口失败: status={}", response.getStatusCode());
                throw new RuntimeException("请求飞书接口失败");
            }

        } catch (Exception e) {
            log.error("获取飞书 tenant token 异常", e);
            throw new RuntimeException("获取飞书 tenant token 异常", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public FeishuMessageSendResponse sendMessage(String receiveIdType, FeishuMessageSendRequest request) {
        String tenantToken = getTenantAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + tenantToken);

        // 构建 URL，添加查询参数
        String url = feishuProperties.getMessageSendUrl() + "?receive_id_type=" + receiveIdType;

        HttpEntity<FeishuMessageSendRequest> httpEntity = new HttpEntity<>(request, headers);

        try {
            log.info("发送飞书消息: url={}, request={}", url, request);
            ResponseEntity<FeishuMessageSendResponse> response = restTemplate.postForEntity(url, httpEntity,
                    FeishuMessageSendResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                FeishuMessageSendResponse respBody = response.getBody();
                if (respBody.getCode() == 0) {
                    log.info("发送飞书消息成功: messageId={}", respBody.getData().getMessageId());
                    return respBody;
                } else {
                    log.error("发送飞书消息失败: code={}, msg={}", respBody.getCode(), respBody.getMsg());
                    throw new RuntimeException("发送飞书消息失败: " + respBody.getMsg());
                }
            } else {
                log.error("请求飞书发送消息接口失败: status={}", response.getStatusCode());
                throw new RuntimeException("请求飞书发送消息接口失败");
            }
        } catch (Exception e) {
            log.error("发送飞书消息异常", e);
            throw new RuntimeException("发送飞书消息异常", e);
        }
    }

    @Override
    public String uploadImage(java.io.File imageFile) {
        if (imageFile == null || !imageFile.exists()) {
            throw new IllegalArgumentException("图片文件不存在");
        }

        String tenantToken = getTenantAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + tenantToken);

        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("image_type", "message");
        body.add("image", new org.springframework.core.io.FileSystemResource(imageFile));

        HttpEntity<org.springframework.util.MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body,
                headers);

        try {
            log.info("开始上传图片到飞书: file={}", imageFile.getAbsolutePath());
            String url = feishuProperties.getImageUploadUrl();

            ResponseEntity<com.tengjiao.douya.entity.feishu.FeishuImageUploadResponse> response = restTemplate
                    .postForEntity(
                            url,
                            requestEntity,
                            com.tengjiao.douya.entity.feishu.FeishuImageUploadResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                com.tengjiao.douya.entity.feishu.FeishuImageUploadResponse respBody = response.getBody();
                if (respBody.getCode() != null && respBody.getCode() == 0) {
                    String imageKey = respBody.getData().getImageKey();
                    log.info("图片上传成功: imageKey={}", imageKey);
                    return imageKey;
                } else {
                    log.error("图片上传失败: code={}, msg={}", respBody.getCode(), respBody.getMsg());
                    throw new RuntimeException("图片上传失败: " + respBody.getMsg());
                }
            } else {
                log.error("请求飞书上传图片接口失败: status={}", response.getStatusCode());
                throw new RuntimeException("请求飞书上传图片接口失败");
            }
        } catch (Exception e) {
            log.error("上传图片异常", e);
            throw new RuntimeException("上传图片异常", e);
        }
    }

}
