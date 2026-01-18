package com.tengjiao.douya.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.tengjiao.douya.config.OssConfig;
import com.tengjiao.douya.service.OssService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OssServiceImpl implements OssService {

    private final OssConfig ossConfig;
    private OSS ossClient;

    @PostConstruct
    public void init() {
        this.ossClient = new OSSClientBuilder()
                .build(
                ossConfig.getEndpoint(),
                ossConfig.getAccessKeyId(),
                ossConfig.getAccessKeySecret());
    }

    @PreDestroy
    public void destroy() {
        if (this.ossClient != null) {
            this.ossClient.shutdown();
        }
    }

    @Override
    public String uploadFile(String objectName, InputStream inputStream) {
        try {
            // Ensure objectName is valid, maybe prepend a directory or date?
            // The user might want control, so we take objectName as is,
            // but maybe we should ensure it doesn't start with /
            if (objectName.startsWith("/")) {
                objectName = objectName.substring(1);
            }

            ossClient.putObject(ossConfig.getBucketName(), objectName, inputStream);
            log.info("Uploaded file to OSS: {}", objectName);
            return getFileUrl(objectName);
        } catch (Exception e) {
            log.error("Failed to upload file to OSS: {}", objectName, e);
            throw new RuntimeException("OSS upload failed", e);
        }
    }

    @Override
    public String uploadFile(String objectName, String filePath) {
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            return uploadFile(objectName, inputStream);
        } catch (Exception e) {
            log.error("Failed to upload file from path: {}", filePath, e);
            throw new RuntimeException("OSS upload failed", e);
        }
    }

    @Override
    public boolean doesObjectExist(String objectName) {
        return ossClient.doesObjectExist(ossConfig.getBucketName(), objectName);
    }

    private String getFileUrl(String objectName) {
        String endpoint = ossConfig.getEndpoint();
        // Construct the public URL
        // Format: https://{bucket}.{endpoint}/{objectName}

        String protocol = "https://";
        if (endpoint.startsWith("http://")) {
            protocol = "http://";
            endpoint = endpoint.substring(7);
        } else if (endpoint.startsWith("https://")) {
            protocol = "https://";
            endpoint = endpoint.substring(8);
        }

        // Remove trailing slash from endpoint if exists
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }

        return protocol + ossConfig.getBucketName() + "." + endpoint + "/" + objectName;
    }
}
