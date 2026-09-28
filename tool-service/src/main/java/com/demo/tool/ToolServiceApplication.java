package com.demo.tool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 诊断工具服务启动类（端口 8090）。
 *
 * <p>当前提供两个只读工具：
 * <ul>
 *   <li>{@code GET /tools/get_service_health}：服务/实例健康 + 中间件连通性</li>
 *   <li>{@code GET /tools/get_api_metrics}：接口 QPS/P95/P99/错误率/环比</li>
 * </ul>
 *
 * <p>本服务对「被诊断环境」只做只读探测（HTTP + MySQL/Redis 连通性检查），
 * 不依赖 demo-services 的任何代码——两者之间只有 HTTP 契约。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ToolServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolServiceApplication.class, args);
    }
}