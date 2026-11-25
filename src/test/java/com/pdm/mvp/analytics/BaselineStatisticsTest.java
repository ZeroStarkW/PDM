package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BaselineStatisticsTest {
    /** TC-03（文档 §9）：基线学习池人为插入 5 条超均值 10 倍的大飞刺 ——
     *  MAD 准确剔除全部 5 条离群点，P50 基线误差 ≤1%，CV≤0.15 达到 ACTIVE 锁定门槛。 */
    @Test
    void madCleaningDropsFiveTenTimesSpikesFromTwoHundredSamples() {
        double[] samples = new double[200];
        for (int i = 0; i < samples.length; i++) samples[i] = i < 5 ? 10.0 : 1.0 + (i % 2 == 0 ? 0.005 : -0.005);

        double[] clean = BaselineService.madClean(samples);

        assertEquals(195, clean.length, "5 条飞刺必须全部被剔除");
        assertEquals(1.0, BaselineService.median(clean), 0.01, "P50 基线误差必须 ≤1%");
        assertTrue(BaselineService.coefficientOfVariation(clean) <= 0.15, "清洗后 CV 必须满足 ACTIVE 锁定门槛");
        var stats = BaselineService.featureStatistics(Map.of("freq_1x_amp", samples));
        assertTrue(stats.maxCv() <= 0.15, "状态机应以 CV≤0.15 判定锁定");
    }

    /** 波动剧烈（CV > 0.15）的学习池必须判 DIVERGED，禁止物理锁定。 */
    @Test
    void divergedPoolExceedsConvergenceThreshold() {
        double[] unstable = new double[200];
        Random random = new Random(7L);
        for (int i = 0; i < unstable.length; i++) unstable[i] = 1.0 + random.nextDouble();
        var stats = BaselineService.featureStatistics(Map.of("freq_1x_amp", unstable));
        assertTrue(stats.maxCv() > 0.15, "高波动池应超出收敛门槛: " + stats.maxCv());
    }
}
