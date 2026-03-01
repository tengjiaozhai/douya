package com.tengjiao.douya.domain.eating.model;

import lombok.Builder;
import lombok.Value;

/**
 * PDF 切分参数
 */
@Value
@Builder
public class PdfSplitOptions {
    DocumentSplitStrategy strategy;

    public static PdfSplitOptions defaults() {
        return PdfSplitOptions.builder().strategy(null).build();
    }
}
