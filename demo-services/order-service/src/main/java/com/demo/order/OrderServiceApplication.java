package com.demo.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 订单服务启动类（端口 8081）。
 *
 * <p>为什么要显式指定 {@code scanBasePackages}：
 * demo-common 模块里的过滤器（MetricsFilter / FaultInjectionFilter）与
 * /internal/** 控制器都在 com.demo.common 包下，必须被扫描注册，
 * 否则指标与故障注入能力不会生效。
 */
@SpringBootApplication(scanBasePackages = {"com.demo.order", "com.demo.common"})
@ConfigurationPropertiesScan
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}