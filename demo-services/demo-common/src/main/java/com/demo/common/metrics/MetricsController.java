package com.demo.common.metrics;

import com.demo.common.instance.ServiceInstanceInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标端点：GET /internal/metrics
 *
 * <p>返回本实例的接口级指标快照，供工具服务 get_api_metrics 跨实例汇总：
 * <pre>
 * {
 *   "service": "order-service",
 *   "instance": "order-1",
 *   "generatedAt": 1730000000000,
 *   "uptimeMs": 123456,
 *   "retentionMinutes": 130,
 *   "totalRequests": 88,
 *   "totalErrors": 3,
 *   "endpoints": [
 *     { "method": "GET", "path": "/api/orders/{id}",
 *       "totalCount": 50, "totalErrors": 0,
 *       "buckets": [ { "minuteStart": 1730000000000, "count": 5, "errors": 0, "samples": [12,15,...] } ] }
 *   ]
 * }
 * </pre>
 *
 * <p>说明：本端点属于 /internal/**，自身不计入 metrics；
 * 各项统计均为「本实例视角」，多实例汇总时不做去重（QPS 天然相加）。
 */
@RestController
@RequestMapping("/internal")
public class MetricsController {

    private final MetricsCollector metricsCollector;
    private final ServiceInstanceInfo instanceInfo;

    public MetricsController(MetricsCollector metricsCollector, ServiceInstanceInfo instanceInfo) {
        this.metricsCollector = metricsCollector;
        this.instanceInfo = instanceInfo;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", instanceInfo.getServiceName());
        body.put("instance", instanceInfo.getInstanceId());
        body.put("generatedAt", System.currentTimeMillis());
        body.put("uptimeMs", instanceInfo.uptimeMs());
        body.put("retentionMinutes", MetricsCollector.RETENTION_MINUTES);
        body.put("totalRequests", metricsCollector.totalRequests());
        body.put("totalErrors", metricsCollector.totalErrors());
        body.put("endpoints", metricsCollector.snapshot());
        return body;
    }
}