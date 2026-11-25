package com.pdm.mvp.timeseries;

import com.influxdb.v3.client.InfluxDBClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InfluxProperties.class)
public class InfluxConfiguration {
    @Bean(destroyMethod = "close")
    InfluxDBClient influxDBClient(InfluxProperties properties) {
        return InfluxDBClient.getInstance(properties.url(), properties.token().toCharArray(), properties.database());
    }
}
