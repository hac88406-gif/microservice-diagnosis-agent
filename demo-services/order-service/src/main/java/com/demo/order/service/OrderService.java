package com.demo.order.service;

import com.demo.order.client.InventoryClient;
import com.demo.order.config.OrderServiceProperties;
import com.demo.order.model.OrderRecord;
import com.demo.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 订单业务逻辑：Redis 缓存 + MySQL 读写 + 调用库存服务。
 *
 * <p>读路径（GET /api/orders/{id}）：
 * <ol>
 *   <li>读 Redis 缓存（key = demo:order:{id}）→ 命中直接返回（source=cache）；</li>
 *   <li>未命中 → 查 MySQL → 回填缓存（TTL 见配置）→ 返回（source=db）；</li>
 *   <li>Redis 异常时降级直查 MySQL（缓存故障不应导致业务整体不可用，
 *       但日志会留下降级痕迹，供诊断 Agent 发现"Redis 有问题"）。</li>
 * </ol>
 *
 * <p>写路径（POST /api/orders）：
 * 先调用库存服务扣减（真实跨服务调用）→ 成功后再落库 → 回填缓存。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Redis 缓存 key 前缀 */
    private static final String CACHE_KEY_PREFIX = "demo:order:";

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderServiceProperties properties;

    public OrderService(OrderRepository orderRepository,
                        InventoryClient inventoryClient,
                        StringRedisTemplate redisTemplate,
                        ObjectMapper objectMapper,
                        OrderServiceProperties properties) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 查询订单：缓存 → 数据库 → 回填缓存。
     *
     * @return 订单视图；订单不存在返回 null（由 Controller 转 404）
     */
    public OrderRecord.OrderView getOrder(long id) {
        String cacheKey = CACHE_KEY_PREFIX + id;

        // 1) 读缓存（失败降级，不影响主流程）
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return new OrderRecord.OrderView(objectMapper.readValue(cached, OrderRecord.class), "cache");
            }
        } catch (Exception e) {
            log.warn("[缓存降级] 读取订单缓存失败，转为直查数据库: id={}, err={}", id, e.toString());
        }

        // 2) 查库
        OrderRecord record = orderRepository.findById(id).orElse(null);
        if (record == null) {
            return null;
        }

        // 3) 回填缓存（同样失败不影响主流程）
        cacheOrder(record);
        return new OrderRecord.OrderView(record, "db");
    }

    /**
     * 创建订单：调用库存服务扣减 → 落库 → 回填缓存。
     *
     * <p>注意：库存调用失败会抛出 {@link InventoryClient.InventoryCallException} 子类，
     * 由 Controller 统一映射为 503/504/404/409，本方法不做吞异常处理——
     * 故障必须以正确的错误形态暴露给上层（这正是"被诊断环境"需要的行为）。
     */
    public OrderRecord.OrderView createOrder(String sku, int quantity) {
        // 1) 真实跨服务调用：扣减库存
        InventoryClient.DeductResult deductResult = inventoryClient.deduct(sku, quantity);

        // 2) 落库（金额 = 下游返回单价 * 数量）
        BigDecimal amount = deductResult.price().multiply(BigDecimal.valueOf(quantity));
        OrderRecord record = orderRepository.insert(sku, quantity, amount);

        // 3) 回填缓存：让"创建后立刻查询"也走缓存（同时便于观察缓存一致性）
        cacheOrder(record);

        return new OrderRecord.OrderView(record, "db");
    }

    /** 写入订单缓存（TTL 来自配置；失败仅记日志） */
    private void cacheOrder(OrderRecord record) {
        String cacheKey = CACHE_KEY_PREFIX + record.id();
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(record),
                    Duration.ofSeconds(properties.getOrderCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("[缓存降级] 回填订单缓存失败: id={}, err={}", record.id(), e.toString());
        }
    }
}