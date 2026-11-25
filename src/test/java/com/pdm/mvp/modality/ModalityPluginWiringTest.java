package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * Spring 装配冒烟（不起应用、不连数据库/MQTT/时序库）：本次改造动过的构造器注入与属性绑定路径必须可装配 ——
 * application.yml 的新键 pdm.switchgear.max-past-delay 能绑定进属性记录，双构造器的 DegradationTracker 仍可作 Bean，
 * 调节阀插件注入全局劣化窗口。
 */
class ModalityPluginWiringTest {
    /** 与 SwitchgearConfiguration / ControlValveConfiguration 相同的 @EnableConfigurationProperties 装配方式。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SwitchgearProperties.class, ControlValveProperties.class})
    static class ModalityPropertiesConfiguration {}

    /** application.yml → 属性记录：占位符按隔离的空环境解析（取 yml 内置默认值），记录的每个分量都必须有键。 */
    @Test
    void applicationYamlBindsEveryComponentOfTheModalityPropertyRecords() throws IOException {
        MutablePropertySources sources = applicationYamlSources();
        var binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        var switchgear = binder.bind("pdm.switchgear", SwitchgearProperties.class).get();
        assertTrue(switchgear.alertsEnabled());
        assertEquals(1.10, switchgear.durationAttentionRatio(), 1e-9);
        assertEquals(1.20, switchgear.durationDangerRatio(), 1e-9);
        assertEquals(0.80, switchgear.currentAttentionRatio(), 1e-9);
        assertEquals(Duration.ofDays(30), switchgear.staleAfter());
        assertEquals(Duration.ofDays(365), switchgear.sampleRetention());
        assertEquals(Duration.ofDays(30), switchgear.degradationWindow());
        assertEquals(Duration.ofDays(30), switchgear.maxPastDelay(), "pdm.switchgear.max-past-delay 的 yml 默认值为 P30D");

        var valve = binder.bind("pdm.control-valve", ControlValveProperties.class).get();
        assertTrue(valve.alertsEnabled());
        assertEquals(2.5, valve.deadbandAttentionRatio(), 1e-9);
        assertEquals(5.0, valve.deadbandDangerRatio(), 1e-9);
        assertEquals(0.2, valve.leakAttentionRms(), 1e-9);
        assertEquals(0.5, valve.leakDangerRms(), 1e-9);
        assertEquals(Duration.ofHours(1), valve.learningWindow());
        assertEquals(Duration.ofHours(24), binder.bind("pdm.health.degradation-window", Duration.class).get(),
                "调节阀 dk/dt 窗口注入的全局键");
    }

    @Test
    void trackerAndStrategyPluginsAreConstructibleAsSpringBeans() throws IOException {
        try (var context = new AnnotationConfigApplicationContext()) {
            var environment = context.getEnvironment();
            for (var source : applicationYamlSources()) environment.getPropertySources().addLast(source);
            environment.getPropertySources().addFirst(new MapPropertySource("wiringTest", Map.of(
                    "pdm.health.degradation-window", "PT6H", "pdm.switchgear.max-past-delay", "P10D")));
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.register(ModalityPropertiesConfiguration.class);
            context.registerBean(DegradationTracker.class);
            context.registerBean(CircuitBreakerPlugin.class);
            context.registerBean(ControlValvePlugin.class);
            context.refresh();

            assertNotNull(context.getBean(DegradationTracker.class), "双构造器：@Autowired 的单参构造器仍被选用");
            assertEquals(Duration.ofDays(10), context.getBean(SwitchgearProperties.class).maxPastDelay(),
                    "yml 键覆盖后经构造器绑定生效");
            assertEquals(Duration.ofDays(10), context.getBean(CircuitBreakerPlugin.class).maxPastDelay());
            assertNotNull(context.getBean(ControlValvePlugin.class), "调节阀插件按 (properties, 劣化窗口) 构造器注入");
        }
    }

    /** application.yml 的全部 property source（不含系统环境变量源：占位符一律取 yml 内置默认值，结果与本机环境无关）。 */
    private static MutablePropertySources applicationYamlSources() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (var source : new YamlPropertySourceLoader().load("application-test",
                new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        return sources;
    }
}
