package com.demo.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工具服务配置（前缀 demo）。
 *
 * <p>核心是「服务注册表」：当前把被诊断服务的实例清单写在配置里，
 * 后续可平滑替换为 Nacos 等注册中心——因为结构已经按
 * 「服务 → 实例列表」两级设计（工具输出结构同样容纳多实例）。
 *
 * <pre>
 * demo:
 *   registry:
 *     services:
 *       - name: order-service
 *         instances:
 *           - id: order-1
 *             base-url: http://127.0.0.1:8081
 *   http:                      # 调用被诊断服务的超时（必须显式，防止工具被拖死）
 *     connect-timeout-ms: 500
 *     read-timeout-ms: 1500
 *   tool:
 *     total-timeout-ms: 5000   # 单个工具调用的总预算
 * </pre>
 */
@ConfigurationProperties(prefix = "demo")
public class ToolServiceProperties {

    private Registry registry = new Registry();
    private Http http = new Http();
    private Tool tool = new Tool();

    /** 按服务名（忽略大小写）查找服务定义 */
    public Optional<ServiceDef> findService(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return registry.getServices().stream()
                .filter(service -> service.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    /** 全部可用服务名（用于错误 hint 中引导模型纠正参数） */
    public List<String> serviceNames() {
        List<String> names = new ArrayList<>();
        for (ServiceDef service : registry.getServices()) {
            names.add(service.getName());
        }
        return names;
    }

    public Registry getRegistry() {
        return registry;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Tool getTool() {
        return tool;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
    }

    // ============================================================
    // 嵌套配置结构
    // ============================================================

    /** 服务注册表 */
    public static class Registry {

        private List<ServiceDef> services = new ArrayList<>();

        public List<ServiceDef> getServices() {
            return services;
        }

        public void setServices(List<ServiceDef> services) {
            this.services = services;
        }
    }

    /** 单个服务定义 */
    public static class ServiceDef {

        /** 服务名（对外统一口径，如 order-service） */
        private String name;

        /** 实例列表（当前每个服务 1 个实例；结构本身支持多实例） */
        private List<InstanceDef> instances = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<InstanceDef> getInstances() {
            return instances;
        }

        public void setInstances(List<InstanceDef> instances) {
            this.instances = instances;
        }
    }

    /** 单个实例定义 */
    public static class InstanceDef {

        /** 实例 ID（工具输出中唯一允许出现的实例标识，替代 IP:端口） */
        private String id;

        /** 实例基础地址（仅用于服务端调用，绝不返回给模型） */
        private String baseUrl;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /** HTTP 调用超时配置 */
    public static class Http {

        /** 建立连接超时（毫秒） */
        private int connectTimeoutMs = 500;

        /** 读取响应超时（毫秒） */
        private int readTimeoutMs = 1500;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /** 工具执行配置 */
    public static class Tool {

        /** 单个工具调用总预算（毫秒）：超出后剩余实例标记为 TOTAL_TIMEOUT */
        private long totalTimeoutMs = 5000;

        public long getTotalTimeoutMs() {
            return totalTimeoutMs;
        }

        public void setTotalTimeoutMs(long totalTimeoutMs) {
            this.totalTimeoutMs = totalTimeoutMs;
        }
    }
}