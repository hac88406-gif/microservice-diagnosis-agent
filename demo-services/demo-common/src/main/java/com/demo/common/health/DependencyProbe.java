package com.demo.common.health;

import java.util.Map;

/**
 * 依赖探测探针接口：把「某一类中间件是否可用」抽象成可插拔的探针。
 *
 * <p>为什么要抽象成接口（而不是在 Checker 里 if-else）：
 * <ul>
 *   <li>每个业务服务实际依赖的中间件不同（order 用 MySQL+Redis，inventory 只用 MySQL），
 *       健康 JSON 必须"有什么报什么"，不能误报不存在的依赖；</li>
 *   <li>探针实现类会引用具体中间件的客户端类（如 RedisConnectionFactory）。
 *       如果直接在 Checker 里引用，当该客户端不在 classpath 时，
 *       Spring 解析构造器泛型会直接抛 ClassNotFoundException，导致服务无法启动——
 *       所以引用必须被隔离在"按条件装配"的独立类里（见 RedisProbeConfiguration）。</li>
 * </ul>
 */
public interface DependencyProbe {

    /** 依赖名（将作为健康 JSON 中 dependencies 的 key，如 redis） */
    String name();

    /** 执行探测，返回脱敏结果（ok / latencyMs / 可选 error、errorType），禁止包含地址、账号、密码 */
    Map<String, Object> check();
}