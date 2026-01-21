package com.tengjiao.douya.service;

import java.io.InputStream;

/**
 * OSS Service Interface for handling object storage operations.
 */
public interface OssService {

    /**
     * Uploads a file to OSS.
     *
     * @param objectName  The name of the object in OSS (including path).
     * @param inputStream The input stream of the file to upload.
     * @return The URL of the uploaded file properly encoded.
     */
    String uploadFile(String objectName, InputStream inputStream);

    /**
     * Uploads a file to OSS.
     * 
     * @param objectName The name of the object in OSS.
     * @param filePath   The local file path.
     * @return The URL of the uploaded file.
     */
    String uploadFile(String objectName, String filePath);

    /**
     * Checks if a file exists in OSS.
     * 
     * @param objectName The name of the object.
     * @return true if exists, false otherwise.
     */
    boolean doesObjectExist(String objectName);

    /**
     * Deletes an object from OSS.
     *
     * @param objectName The name of the object to delete.
     */
    void deleteObject(String objectName);

    /**
     * Lists objects in the bucket with a specific prefix.
     *
     * @param prefix The prefix to filter objects.
     * @return A list of object keys.
     */
    java.util.List<String> listObjects(String prefix);

    /**
     * Copies an object within the same bucket.
     *
     * @param sourceKey      The source object key.
     * @param destinationKey The destination object key.
     */
    void copyObject(String sourceKey, String destinationKey);

    /**
     * Gets the bucket name.
     * @return The bucket name.
     */
    String getBucketName();
}
