package com.pdm.mvp.diagnostics;

import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeRepository;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds default rules for installed multimodal equipment categories without depending on demo asset seeding. */
@Component
@Order(200)
public class DefaultDiagnosticRuleInitializer implements ApplicationRunner {
    private static final long RULE_SEED_LOCK_KEY = 0x50444D5F52554C45L; // PDM_RULE

    @PersistenceContext
    private EntityManager entityManager;

    private final DeviceTypeRepository types;
    private final DiagnosticRuleService rules;
    private final McsaProperties mcsa;
    private final SwitchgearProperties switchgear;
    private final ControlValveProperties valve;
    private final PlatformTransactionManager transactionManager;

    public DefaultDiagnosticRuleInitializer(DeviceTypeRepository types, DiagnosticRuleService rules,
            McsaProperties mcsa, SwitchgearProperties switchgear, ControlValveProperties valve,
            PlatformTransactionManager transactionManager) {
        this.types = types;
        this.rules = rules;
        this.mcsa = mcsa;
        this.switchgear = switchgear;
        this.valve = valve;
        this.transactionManager = transactionManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            entityManager.createNativeQuery("select cast(pg_advisory_xact_lock(?1) as text)")
                    .setParameter(1, RULE_SEED_LOCK_KEY)
                    .getSingleResult();
            seed("ELECTRIC_MOTOR", this::motorRules);
            seed("CIRCUIT_BREAKER", this::breakerRules);
            seed("CONTROL_VALVE", this::valveRules);
        });
    }

    private void seed(String typeCode, Supplier<List<DiagnosticRuleRequest>> defaults) {
        types.findByTypeCode(typeCode).ifPresent(type -> rules.seedDefaults(type.getId(), defaults.get()));
    }

    private List<DiagnosticRuleRequest> motorRules() {
        return List.of(
                rule(DiagnosticMetric.MCSA_SIDEBAND_GAP_DB, DiagnosticOperator.LTE, mcsa.sidebandDangerDb(), 300,
                        "疑似转子导条损伤", "边频带/载波间距已达到候选危险阈值；阈值需结合目标电机样本现场校准。",
                        "复核负载工况并结合健康/故障样本校准，检查转子导条与端环。"),
                rule(DiagnosticMetric.MCSA_SIDEBAND_GAP_DB, DiagnosticOperator.LTE, mcsa.sidebandWarnDb(), 200,
                        "工频边频带增大", "边频带/载波间距已达到候选关注阈值；阈值需现场校准。",
                        "复核运行负载并对比健康基线，检查电机电流频谱变化。"));
    }

    private List<DiagnosticRuleRequest> breakerRules() {
        return List.of(
                rule(DiagnosticMetric.COIL_CURRENT_RATIO, DiagnosticOperator.LTE,
                        switchgear.currentAttentionRatio(), 250, "脱扣线圈驱动力不足",
                        "线圈峰值电流相对基线偏低，可能与铁芯卡涩或控制回路电压有关。",
                        "检查合分闸回路供电、线圈接线与铁芯动作状态。"),
                rule(DiagnosticMetric.TRIP_DURATION_RATIO, DiagnosticOperator.GTE,
                        switchgear.durationAttentionRatio(), 200, "分闸动作迟缓",
                        "分闸时间超过设备基线关注倍率。",
                        "复测分闸时间与行程曲线，检查传动机构润滑和卡涩。"));
    }

    private List<DiagnosticRuleRequest> valveRules() {
        return List.of(
                rule(DiagnosticMetric.VALVE_DEADBAND_RATIO, DiagnosticOperator.GTE,
                        valve.deadbandDangerRatio(), 400, "调节阀动作迟滞卡阻",
                        "回环死区超过基线危险倍率，阀杆/填料可能卡涩。",
                        "检查填料压紧力、阀杆结垢和定位器动作。"),
                rule(DiagnosticMetric.VALVE_DEADBAND_RATIO, DiagnosticOperator.GTE,
                        valve.deadbandAttentionRatio(), 300, "调节阀回环死区扩大",
                        "回环死区超过基线关注倍率。",
                        "复测 CV-FB 迟滞回环并检查阀杆与定位器。"),
                rule(DiagnosticMetric.VALVE_LEAK_RMS, DiagnosticOperator.GT,
                        valve.leakAttentionRms(), 200, "阀内泄漏异常",
                        "超声泄漏 RMS 超出候选关注阈值。",
                        "安排阀体超声复测并与工艺压差、介质状态联合确认。"));
    }

    private static DiagnosticRuleRequest rule(DiagnosticMetric metric, DiagnosticOperator operator, double threshold,
            int priority, String factor, String evidence, String advice) {
        return new DiagnosticRuleRequest(metric, null, operator, threshold, priority, factor, evidence, advice, true);
    }
}
