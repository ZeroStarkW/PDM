package com.pdm.mvp.waveform;

import java.util.List;

/**
 * 波形原始归档存储抽象：MVP 落本地卷（file:// 引用），
 * 后续接入 MinIO/S3 时实现 {@code store} 返回 s3:// 引用即可（文档 §2.2 storage 扩展点）。
 */
public interface WaveformStorage {

    /** 归档波形样本并返回存储引用（raw_file_url 语义）。 */
    String store(Long pointId, long timestamp, List<Double> samples);
}
