package com.demo.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 订单服务业务配置（前缀 demo）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code instanceId}：实例 ID（健康工具汇总实例健康时展示，多实例部署时区分实例）；</li>
 *   <li>{@code orderCacheTtlSeconds}：订单缓存 TTL（秒）。后续缓存击穿类验证
 *       需要把热点 key 过期与并发结合，TTL 是可调参数；</li>
 *   <li>{@code inventory.*}：调用库存服务的连接与超时配置——
 *       readTimeoutMs 必须小于下游可能的响应时间，才能让超时快速暴露。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "demo")
public class OrderServiceProperties {

    /** 实例 ID，例如 order-1 */
    private String instanceId = "order-1";

    /** 订单缓存 TTL（秒） */
    private int orderCacheTtlSeconds = 60;

    /** 库存服务调用配置 */
    private Inventory inventory = new Inventory();

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public int getOrderCacheTtlSeconds() {
        return orderCacheTtlSeconds;
    }

    public void setOrderCacheTtlSeconds(int orderCacheTtlSeconds) {
        this.orderCacheTtlSeconds = orderCacheTtlSeconds;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /** 库存服务（inventory-service）调用配置 */
    public static class Inventory {

        /** 库存服务基础地址 */
        private String baseUrl = "http://127.0.0.1:8082";

        /** 建立连接超时（毫秒） */
        private int connectTimeoutMs = 500;

        /** 读取响应超时（毫秒）：小于下游 3s 注入延迟，保证超时故障真实发生 */
        private int readTimeoutMs = 1500;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

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
}