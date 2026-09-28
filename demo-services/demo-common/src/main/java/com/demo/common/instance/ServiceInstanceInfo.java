package com.demo.common.instance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务实例自描述信息（每个业务服务启动时创建一份）。
 *
 * <p>为什么需要它：
 * <ul>
 *   <li>工具服务 get_service_health 按「服务 → 实例」两级结构汇总健康状态，
 *       因此每个业务实例必须能自报「我是谁」（服务名 + 实例 ID）；</li>
 *   <li>后续多实例部署中，
 *       实例 ID 是诊断输出里唯一允许出现的实例标识——不暴露 IP/端口明文。</li>
 * </ul>
 *
 * <p>数据来源：配置项 {@code spring.application.name} 与 {@code demo.instance-id}，
 * 启动时快照一次；{@link #uptimeMs()} 为动态计算的运行时长。
 */
@Component
public class ServiceInstanceInfo {

    /** 服务名，例如 order-service（来自 spring.application.name） */
    private final String serviceName;

    /** 实例 ID，例如 order-1（来自 demo.instance-id；多实例部署时可配 order-2 ...） */
    private final String instanceId;

    /** JVM 启动时刻（毫秒时间戳），用于计算运行时长 */
    private final long startTimeMs;

    public ServiceInstanceInfo(@Value("${spring.application.name:unknown-service}") String serviceName,
                               @Value("${demo.instance-id:unknown-1}") String instanceId) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.startTimeMs = System.currentTimeMillis();
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /** 已运行时长（毫秒），健康工具汇总实例健康时用于观察实例是否刚重启 */
    public long uptimeMs() {
        return System.currentTimeMillis() - startTimeMs;
    }
}