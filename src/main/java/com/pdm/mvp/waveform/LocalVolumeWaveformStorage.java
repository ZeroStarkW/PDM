package com.pdm.mvp.waveform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本地卷波形归档：按 测点/时间戳 落 JSON 数组文件，返回 file:// 绝对路径引用。
 *  注意：该绝对路径会经快照视图的 storageRef/snapshotUrl 暴露给前端（MVP 可接受，不做路径改写）。 */
@Component
public class LocalVolumeWaveformStorage implements WaveformStorage {
    private final Path root;

    public LocalVolumeWaveformStorage(@Value("${pdm.waveform.storage-dir:./data/waveforms}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(Long pointId, long timestamp, List<Double> samples) {
        try {
            Path file = root.resolve(String.valueOf(pointId)).resolve(timestamp + ".json");
            Files.createDirectories(file.getParent());
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < samples.size(); i++) {
                if (i > 0) json.append(',');
                json.append(samples.get(i));
            }
            json.append(']');
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
            return "file:" + file;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("波形归档写入失败: " + exception.getMessage(), exception);
        }
    }
}
