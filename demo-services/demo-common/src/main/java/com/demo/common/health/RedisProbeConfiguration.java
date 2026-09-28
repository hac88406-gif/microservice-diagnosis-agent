package com.demo.common.health;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis 探针的条件装配配置。
 *
 * <p>这是「可选依赖」在 Spring 里的标准做法：
 * <ul>
 *   <li>{@code @ConditionalOnClass(RedisConnectionFactory.class)} 由 Spring 通过
 *       注解元数据（ASM 读取，不加载类）判断——类不存在时整个配置类被跳过，
 *       不会触发 ClassNotFoundException；</li>
 *   <li>因此：order-service（引入 data-redis）健康 JSON 含 redis 字段；
 *       inventory-service（不引入）自动不含 redis 字段——"有什么报什么"。</li>
 * </ul>
 *
 * <p>实现要点：最初把 Redis 探针写成
 * {@code ObjectProvider<RedisConnectionFactory>} 直接在 Checker 构造器里注入，
 * 结果 inventory-service 启动即失败——Spring 解析构造器泛型时会加载
 * RedisConnectionFactory 类，类不在 classpath 直接抛 ClassNotFoundException。
 * 正确做法就是本类：把"对可选类的引用"隔离到条件装配的独立类中。
 */
@Configuration
@ConditionalOnClass(RedisConnectionFactory.class)
public class RedisProbeConfiguration {

    /** 注册 Redis 探针（仅在 Redis 客户端存在时生效） */
    @Bean
    public DependencyProbe redisDependencyProbe(RedisConnectionFactory connectionFactory) {
        return new RedisDependencyProbe(connectionFactory);
    }
}