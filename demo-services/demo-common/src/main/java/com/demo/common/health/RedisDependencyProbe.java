package com.demo.common.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 依赖探针：PING 探测连通性。
 *
 * <p>注意：本类直接引用 Redis 客户端类型，因此<b>只能</b>在
 * Redis 客户端存在于 classpath 时被加载——由 {@link RedisProbeConfiguration}
 * 的 {@code @ConditionalOnClass} 条件装配保证（不使用 Redis 的服务不会加载本类）。
 */
public class RedisDependencyProbe implements DependencyProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisDependencyProbe.class);

    private final RedisConnectionFactory connectionFactory;

    public RedisDependencyProbe(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public Map<String, Object> check() {
        long startNs = System.nanoTime();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", true);
            map.put("latencyMs", costMs(startNs));
            return map;
        } catch (Exception e) {
            // 完整异常只进日志；返回体只给类别与异常类名（不含连接串）
            log.warn("Redis 连通性探测失败: {}", e.toString());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", false);
            map.put("latencyMs", costMs(startNs));
            map.put("error", "Redis 连接异常");
            map.put("errorType", e.getClass().getSimpleName());
            return map;
        }
    }

    private long costMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}