package com.tengjiao.douya.domain.eating.model;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 图片信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfImageInfo {

    /**
     * 图片文件名
     */
    private String fileName;

    /**
     * OSS 访问 URL
     */
    private String ossUrl;

    /**
     * 图片所在页码
     */
    private Integer pageNumber;

    /**
     * 图片在页面中的 Y 坐标(用于智能关联)
     */
    private Float yPosition;

    /**
     * 图片格式(png/jpeg)
     */
    private String format;
}
