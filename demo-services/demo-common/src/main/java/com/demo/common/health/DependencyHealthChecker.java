package com.demo.common.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 依赖健康探测器：汇总本实例依赖的中间件连通性。
 *
 * <p>组成方式：
 * <ul>
 *   <li><b>MySQL</b>：内置探测（DataSource 来自 JDK 的 javax.sql，一定可加载）；</li>
 *   <li><b>Redis 等可选依赖</b>：由实现了 {@link DependencyProbe} 的探针提供，
 *       探针本身按 {@code @ConditionalOnClass} 条件装配——
 *       服务不引入某中间件客户端时，对应探针不存在，健康 JSON 里也就不会出现该字段
 *       （"有什么报什么"，不误报）。</li>
 * </ul>
 *
 * <p>输出脱敏：只返回 ok / 耗时 / 异常类别名，绝不回显连接串、账号密码、IP。
 * 完整异常仅写入服务端日志。
 */
@Component
public class DependencyHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(DependencyHealthChecker.class);

    /** MySQL 探测超时：SELECT 1 最多等待 2 秒，避免健康检查被慢查询拖死 */
    private static final int MYSQL_QUERY_TIMEOUT_SECONDS = 2;

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<DependencyProbe> probes;

    public DependencyHealthChecker(ObjectProvider<DataSource> dataSourceProvider,
                                   ObjectProvider<DependencyProbe> probes) {
        this.dataSourceProvider = dataSourceProvider;
        this.probes = probes;
    }

    /**
     * 探测全部依赖。
     *
     * @return 形如 {mysql: {...}, redis: {...}}；未配置的依赖不会出现在结果中
     */
    public Map<String, Object> checkAll() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> mysql = checkMysql();
        if (mysql != null) {
            result.put("mysql", mysql);
        }

        // ObjectProvider 可迭代：没有任何探针时迭代 0 次（不会报错）
        for (DependencyProbe probe : probes) {
            result.put(probe.name(), probe.check());
        }
        return result;
    }

    /** 探测 MySQL：取连接 + SELECT 1（带查询超时） */
    private Map<String, Object> checkMysql() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return null; // 本实例未配置 MySQL
        }
        long startNs = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(MYSQL_QUERY_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", true);
            map.put("latencyMs", costMs(startNs));
            return map;
        } catch (Exception e) {
            log.warn("MySQL 连通性探测失败: {}", e.toString());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", false);
            map.put("latencyMs", costMs(startNs));
            map.put("error", "MySQL 连接异常");
            map.put("errorType", e.getClass().getSimpleName());
            return map;
        }
    }

    private long costMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}