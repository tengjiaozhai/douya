package com.tengjiao.douya.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PDF 文档处理结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfProcessResult {

    /**
     * 文档名称
     */
    private String documentName;

    /**
     * 总页数
     */
    private Integer totalPages;

    /**
     * 提取的图片数量
     */
    private Integer imageCount;

    /**
     * 生成的文档片段数量
     */
    private Integer chunkCount;

    /**
     * 处理状态
     */
    private String status;

    /**
     * 错误信息(如果有)
     */
    private String errorMessage;

    /**
     * 提取的图片列表
     */
    private List<PdfImageInfo> images;
}
