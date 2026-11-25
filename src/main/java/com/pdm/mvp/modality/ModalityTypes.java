package com.pdm.mvp.modality;

/**
 * 首期内置模态与契约版本常量；新模态通过插件注册扩展，不在此枚举固化。
 */
public final class ModalityTypes {
    public static final String ROTATING_VIB = "ROTATING_VIB";
    public static final String MCSA = "MCSA";
    /** 高压断路器机械特性（事件型动作触发，稀疏采样）。 */
    public static final String SWITCHGEAR_EVENT = "SWITCHGEAR_EVENT";
    /** 工艺控制调节阀（连续低频 DCS 过程量）。 */
    public static final String CONTROL_VALVE_PROCESS = "CONTROL_VALVE_PROCESS";
    /** 当前支持的统一遥测契约版本。 */
    public static final int SUPPORTED_CONTRACT_VERSION = 2;
    /** 工况门禁未通过（负载波动/非稳态等）时的样本状态，不进入评分与告警。 */
    public static final String CONDITION_REJECTED = "CONDITION_REJECTED";
    public static final int CONDITION_REJECTED_CODE = 6;

    /** 归一化模态标识：去空白、统一大写，并把文档别名 ELECTRICAL_MCSA 映射为平台 MCSA；空值返回 null。 */
    public static String normalize(String modalityType) {
        if (modalityType == null || modalityType.isBlank()) return null;
        String upper = modalityType.trim().toUpperCase(java.util.Locale.ROOT);
        return "ELECTRICAL_MCSA".equals(upper) ? MCSA : upper;
    }

    private ModalityTypes() {}
}
