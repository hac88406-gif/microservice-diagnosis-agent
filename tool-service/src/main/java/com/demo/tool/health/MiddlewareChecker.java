package com.demo.tool.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 中间件连通性复核器：工具服务直连 MySQL/Redis 做独立探测。
 *
 * <p>为什么工具服务要「自己再探一遍」：
 * 实例自报的依赖状态可能因为"实例自己就假死了"而拿不到；
 * 工具服务直连中间件是第二路独立证据——两路证据互相印证，
 * 才能把"Redis 挂了"与"order 实例挂了"区分开。
 *
 * <p>安全问题：只返回 ok / 耗时 / 异常类别名，绝不回显连接串、账号密码、IP。
 * 完整异常仅写入服务端日志。
 */
@Component
public class MiddlewareChecker {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareChecker.class);

    /** MySQL 探测超时（SELECT 1 最多等 2 秒） */
    private static final int MYSQL_QUERY_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    /**
     * 说明：本类位于工具服务（独立工程），刻意不依赖 demo-common——
     * 工具服务与被诊断环境之间只保留 HTTP 契约，没有代码依赖。
     * 因此这里对探测逻辑做了少量重复实现（约 40 行），换取两个工程的彻底解耦。
     */
    @Autowired
    public MiddlewareChecker(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    /** 探测 MySQL + Redis，返回脱敏结果 */
    public Map<String, Object> check() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mysql", checkMysql());
        result.put("redis", checkRedis());
        return result;
    }

    /** MySQL：取连接 + SELECT 1 */
    private Map<String, Object> checkMysql() {
        long startNs = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(MYSQL_QUERY_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return ok(costMs(startNs));
        } catch (Exception e) {
            log.warn("MySQL 连通性复核失败: {}", e.toString());
            return fail(costMs(startNs), "MySQL 连接异常", e);
        }
    }

    /** Redis：PING */
    private Map<String, Object> checkRedis() {
        long startNs = System.nanoTime();
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.ping();
            return ok(costMs(startNs));
        } catch (Exception e) {
            log.warn("Redis 连通性复核失败: {}", e.toString());
            return fail(costMs(startNs), "Redis 连接异常", e);
        }
    }

    // ---------------- 内部辅助 ----------------

    private long costMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private Map<String, Object> ok(long latencyMs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("latencyMs", latencyMs);
        return map;
    }

    private Map<String, Object> fail(long latencyMs, String description, Exception e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("latencyMs", latencyMs);
        map.put("error", description);
        map.put("errorType", e.getClass().getSimpleName()); // 只给类名，不给可能含地址的消息
        return map;
    }
}