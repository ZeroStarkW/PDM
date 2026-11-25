package com.pdm.mvp.modality;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 模态插件注册表：启动时校验每个 modalityType 只注册一次（重复即启动失败），
 * 运行期拒绝未知模态进入评分或告警。
 */
@Component
public class ModalityPluginRegistry {
    private final Map<String, ModalityPlugin> plugins = new LinkedHashMap<>();

    public ModalityPluginRegistry(List<ModalityPlugin> found) {
        for (ModalityPlugin plugin : found) {
            ModalityPlugin existing = plugins.putIfAbsent(plugin.modalityType(), plugin);
            if (existing != null)
                throw new IllegalStateException("重复注册的模态插件: " + plugin.modalityType()
                        + " (" + existing.getClass().getSimpleName() + " / " + plugin.getClass().getSimpleName() + ")");
        }
    }

    public ModalityPlugin require(String modalityType) {
        ModalityPlugin plugin = plugins.get(modalityType);
        if (plugin == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知遥测模态: " + modalityType);
        return plugin;
    }

    /** 按模态标识查找插件；未注册返回空（资产树、测点列表、告警视图等只读路径据此容错，不因个别测点整体 400）。 */
    public Optional<ModalityPlugin> find(String modalityType) {
        return Optional.ofNullable(plugins.get(modalityType));
    }

    public Collection<ModalityPlugin> all() { return plugins.values(); }
}
