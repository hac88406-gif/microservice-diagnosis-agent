package com.demo.tool.service;

import com.demo.tool.client.DownstreamClient;
import com.demo.tool.config.ToolServiceProperties;
import com.demo.tool.config.ToolServiceProperties.InstanceDef;
import com.demo.tool.config.ToolServiceProperties.ServiceDef;
import com.demo.tool.health.MiddlewareChecker;
import com.demo.tool.web.ErrorCodes;
import com.demo.tool.web.ToolException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get_service_health}：服务与实例健康汇总。
 *
 * <p>工具契约要点：
 * <ul>
 *   <li>入参 {@code service?}：缺省返回全部服务；</li>
 *   <li>出参：服务名、实例总数/健康数、异常实例摘要、中间件连通性（MySQL/Redis）；</li>
 *   <li>裁剪：实例最多 10 条 + 总数；不含 IP/端口明文（实例只用 ID 标识）；</li>
 *   <li>错误：服务不存在 → 不可重试 + 可用服务名 hint（引导模型纠正参数）；</li>
 *   <li>「实例探测超时」不进错误码，而是作为数据返回（status=UNREACHABLE, reason=TIMEOUT）——
 *       实例不可达本身就是健康工具最有价值的诊断结论（连接被拒=实例下线，超时=实例假死）；</li>
 *   <li>仅当「工具总预算耗尽且一个实例都没探到」时才报可重试的 DOWNSTREAM_TIMEOUT。</li>
 * </ul>
 */
@Service
public class HealthToolService {

    private static final Logger log = LoggerFactory.getLogger(HealthToolService.class);

    /** 契约裁剪：每个服务最多返回 10 个实例明细（超出部分用 instanceTotal/instancesTruncated 表达） */
    private static final int MAX_INSTANCES_PER_SERVICE = 10;

    private final ToolServiceProperties properties;
    private final DownstreamClient downstreamClient;
    private final MiddlewareChecker middlewareChecker;

    public HealthToolService(ToolServiceProperties properties,
                             DownstreamClient downstreamClient,
                             MiddlewareChecker middlewareChecker) {
        this.properties = properties;
        this.downstreamClient = downstreamClient;
        this.middlewareChecker = middlewareChecker;
    }

    /**
     * 汇总服务健康。
     *
     * @param service 服务名（可为空 = 全部）
     * @param traceId 链路 ID（透传给下游）
     */
    public Map<String, Object> getServiceHealth(String service, String traceId) {
        List<ServiceDef> targets = resolveTargets(service);
        long deadline = System.currentTimeMillis() + properties.getTool().getTotalTimeoutMs();

        List<Map<String, Object>> serviceList = new ArrayList<>(targets.size());
        boolean anyInstanceProbed = false;
        boolean totalBudgetExhausted = false;

        for (ServiceDef serviceDef : targets) {
            List<Map<String, Object>> instances = new ArrayList<>();
            List<Map<String, Object>> abnormalInstances = new ArrayList<>();
            int healthyCount = 0;

            for (InstanceDef instance : serviceDef.getInstances()) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", instance.getId());

                // 总预算已耗尽：不再发起新请求，直接标记（防止工具整体被拖死）
                if (System.currentTimeMillis() > deadline) {
                    totalBudgetExhausted = true;
                    view.put("status", "UNKNOWN");
                    view.put("reason", "TOTAL_TIMEOUT");
                    abnormalInstances.add(abnormal(instance.getId(), "TOTAL_TIMEOUT"));
                    instances.add(view);
                    continue;
                }

                try {
                    JsonNode health = downstreamClient.getJson(instance.getBaseUrl(), "/internal/health", traceId);
                    anyInstanceProbed = true;
                    String status = health.path("status").asText("UNKNOWN");
                    view.put("status", status);
                    view.put("uptimeMs", health.path("uptimeMs").asLong());
                    view.put("dependencies", simplifyDependencies(health.path("dependencies")));
                    view.put("faultActive", health.path("fault").path("active").asBoolean(false));
                    if ("UP".equals(status)) {
                        healthyCount++;
                    } else {
                        abnormalInstances.add(abnormal(instance.getId(), "DEGRADED"));
                    }
                } catch (DownstreamClient.DownstreamException e) {
                    // 实例不可达：作为数据返回，原因枚举化（不含地址）
                    view.put("status", "UNREACHABLE");
                    view.put("reason", e.getReason().name());
                    abnormalInstances.add(abnormal(instance.getId(), e.getReason().name()));
                }
                instances.add(view);
            }

            Map<String, Object> serviceView = new LinkedHashMap<>();
            serviceView.put("service", serviceDef.getName());
            serviceView.put("status", serviceStatus(healthyCount, serviceDef.getInstances().size()));
            serviceView.put("instanceTotal", serviceDef.getInstances().size());
            serviceView.put("healthyCount", healthyCount);
            // 裁剪：最多 10 条明细 + 是否被截断标记
            boolean truncated = instances.size() > MAX_INSTANCES_PER_SERVICE;
            serviceView.put("instancesTruncated", truncated);
            serviceView.put("instances", truncated ? instances.subList(0, MAX_INSTANCES_PER_SERVICE) : instances);
            serviceView.put("abnormalInstances", abnormalInstances);
            serviceList.add(serviceView);
        }

        // 一个实例都没探到且总预算耗尽 → 报可重试的超时错误（契约："超时 → 可重试"）
        if (!anyInstanceProbed && totalBudgetExhausted) {
            log.warn("服务健康采集失败：总预算耗尽且无任何实例响应, traceId={}", traceId);
            throw new ToolException(ErrorCodes.DOWNSTREAM_TIMEOUT,
                    "采集服务健康超时：所有实例均未在工具预算内响应",
                    true,
                    "可稍后重试一次；若持续超时，说明实例可能假死（进程在但接口不响应），"
                            + "请重点核对实例 status=UNREACHABLE + reason=TIMEOUT 的模式");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("services", serviceList);
        data.put("middleware", middlewareChecker.check());
        data.put("checkedAt", System.currentTimeMillis());
        return data;
    }

    // ---------------- 内部辅助 ----------------

    /** 解析目标服务列表：缺省全部；指定但不存在 → 不可重试 + 可用服务名 hint */
    private List<ServiceDef> resolveTargets(String service) {
        if (service == null || service.isBlank()) {
            return properties.getRegistry().getServices();
        }
        ServiceDef serviceDef = properties.findService(service)
                .orElseThrow(() -> new ToolException(
                        ErrorCodes.SERVICE_NOT_FOUND,
                        "服务不存在: " + service,
                        false,
                        "可用服务：" + String.join("、", properties.serviceNames())
                                + "；请用其中之一重试，或省略 service 参数查询全部"));
        return List.of(serviceDef);
    }

    /** 服务级状态：全健康=UP；部分健康=DEGRADED；全异常=DOWN */
    private String serviceStatus(int healthyCount, int total) {
        if (total == 0) {
            return "UNKNOWN";
        }
        if (healthyCount == total) {
            return "UP";
        }
        return healthyCount > 0 ? "DEGRADED" : "DOWN";
    }

    /**
     * 依赖状态简化：{"mysql":{"ok":true,...}} → {"mysql":"UP"}。
     * 面向模型只保留"通/不通"这种可直接推理的信息，细节留给原始接口。
     */
    private Map<String, Object> simplifyDependencies(JsonNode dependencies) {
        Map<String, Object> simplified = new LinkedHashMap<>();
        dependencies.fields().forEachRemaining(entry -> {
            boolean ok = entry.getValue().path("ok").asBoolean(false);
            simplified.put(entry.getKey(), ok ? "UP" : "DOWN");
        });
        return simplified;
    }

    /** 异常实例摘要条目（只含实例 ID 与原因枚举，不含地址） */
    private Map<String, Object> abnormal(String instanceId, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", instanceId);
        map.put("reason", reason);
        return map;
    }
}