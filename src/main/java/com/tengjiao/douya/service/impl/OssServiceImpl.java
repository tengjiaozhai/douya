package com.tengjiao.douya.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.OSSObjectSummary;
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
import java.util.ArrayList;
import java.util.List;

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

    @Override
    public void deleteObject(String objectName) {
        try {
            ossClient.deleteObject(ossConfig.getBucketName(), objectName);
            log.info("Deleted object from OSS: {}", objectName);
        } catch (Exception e) {
            log.error("Failed to delete object from OSS: {}", objectName, e);
            throw new RuntimeException("OSS delete failed", e);
        }
    }

    @Override
    public List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            String bucketName = ossConfig.getBucketName();
            ListObjectsRequest listObjectsRequest = new ListObjectsRequest();
            listObjectsRequest.setPrefix(prefix);
            listObjectsRequest.setBucketName(bucketName);
            listObjectsRequest.setMaxKeys(1000);
            ObjectListing objectListing = ossClient.listObjects(listObjectsRequest);
            for (OSSObjectSummary objectSummary : objectListing.getObjectSummaries()) {
                keys.add(objectSummary.getKey());
            }
            log.info("Listed {} objects with prefix: {}", keys.size(), prefix);
            return keys;
        } catch (Exception e) {
            log.error("Failed to list objects with prefix: {}", prefix, e);
            throw new RuntimeException("OSS list failed", e);
        }
    }

    @Override
    public void copyObject(String sourceKey, String destinationKey) {
        try {
            String bucketName = ossConfig.getBucketName();
            ossClient.copyObject(bucketName, sourceKey, bucketName, destinationKey);
            log.info("Copied object from {} to {}", sourceKey, destinationKey);
        } catch (Exception e) {
            log.error("Failed to copy object from {} to {}", sourceKey, destinationKey, e);
            throw new RuntimeException("OSS copy failed", e);
        }
    }

    @Override
    public String getBucketName() {
        return ossConfig.getBucketName();
    }

    @Override
    public String getFileUrl(String objectName) {
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
