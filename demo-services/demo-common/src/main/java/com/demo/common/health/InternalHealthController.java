package com.demo.common.health;

import com.demo.common.fault.FaultInjectionManager;
import com.demo.common.fault.FaultSpec;
import com.demo.common.instance.ServiceInstanceInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康端点：GET /internal/health
 *
 * <p>返回本实例的实时健康 JSON，供工具服务 get_service_health 汇总：
 * <pre>
 * {
 *   "service": "order-service",
 *   "instance": "order-1",
 *   "status": "UP",                       // UP=依赖全通；DEGRADED=有依赖探测失败
 *   "uptimeMs": 123456,
 *   "timestamp": 1730000000000,
 *   "dependencies": {
 *     "mysql": {"ok": true, "latencyMs": 3},
 *     "redis": {"ok": true, "latencyMs": 1}
 *   },
 *   "fault": {"active": false}            // 当前是否有注入故障（便于诊断时对照）
 * }
 * </pre>
 *
 * <p>特别注意：本端点属于 /internal/**，永远不受故障注入影响——
 * 否则注入故障后健康检查会一起失败，诊断 Agent 就失去了"基线信号"。
 */
@RestController
@RequestMapping("/internal")
public class InternalHealthController {

    private final ServiceInstanceInfo instanceInfo;
    private final DependencyHealthChecker dependencyHealthChecker;
    private final FaultInjectionManager faultInjectionManager;

    public InternalHealthController(ServiceInstanceInfo instanceInfo,
                                    DependencyHealthChecker dependencyHealthChecker,
                                    FaultInjectionManager faultInjectionManager) {
        this.instanceInfo = instanceInfo;
        this.dependencyHealthChecker = dependencyHealthChecker;
        this.faultInjectionManager = faultInjectionManager;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> dependencies = dependencyHealthChecker.checkAll();

        // 只要有一个依赖探测失败就标记 DEGRADED（演示环境不引入 DOWN：进程活着本身就算"在线"）
        boolean allOk = dependencies.values().stream()
                .allMatch(dep -> Boolean.TRUE.equals(((Map<?, ?>) dep).get("ok")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", instanceInfo.getServiceName());
        body.put("instance", instanceInfo.getInstanceId());
        body.put("status", allOk ? "UP" : "DEGRADED");
        body.put("uptimeMs", instanceInfo.uptimeMs());
        body.put("timestamp", System.currentTimeMillis());
        body.put("dependencies", dependencies);

        FaultSpec fault = faultInjectionManager.current();
        Map<String, Object> faultMap = new LinkedHashMap<>();
        faultMap.put("active", fault != null);
        if (fault != null) {
            faultMap.put("type", fault.getType());
            faultMap.put("percent", fault.getPercent());
        }
        body.put("fault", faultMap);
        return body;
    }
}