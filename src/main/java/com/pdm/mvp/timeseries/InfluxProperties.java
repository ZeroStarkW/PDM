package com.pdm.mvp.timeseries;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pdm.influx")
public record InfluxProperties(String url, String token, String database, String retention) {}
