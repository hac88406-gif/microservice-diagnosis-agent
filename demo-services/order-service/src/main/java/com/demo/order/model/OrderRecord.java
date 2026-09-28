package com.demo.order.model;

import com.demo.order.model.OrderRecord;

import java.math.BigDecimal;

/**
 * 订单数据模型（与 orders 表一一对应）。
 *
 * @param id        订单号
 * @param sku       商品 SKU
 * @param quantity  数量
 * @param amount    金额（单价 * 数量）
 * @param status    状态：CREATED / PAID / CANCELLED
 * @param createdAt 下单时间（格式 yyyy-MM-dd HH:mm:ss，便于日志与报告引用）
 */
public record OrderRecord(long id,
                          String sku,
                          int quantity,
                          BigDecimal amount,
                          String status,
                          String createdAt) {

    /**
     * 对外视图 = 订单数据 + 数据来源标记。
     *
     * @param source 本次读取的数据来源：cache（Redis 命中）/ db（MySQL 回源）
     *               刻意暴露给调用方与诊断工具：缓存命中率异常（如命中率骤降）
     *               是缓存命中率异常类问题的关键信号
     */
    public record OrderView(long id,
                            String sku,
                            int quantity,
                            BigDecimal amount,
                            String status,
                            String createdAt,
                            String source) {

        /** 由订单记录构造视图 */
        public OrderView(OrderRecord record, String source) {
            this(record.id(), record.sku(), record.quantity(),
                    record.amount(), record.status(), record.createdAt(), source);
        }
    }
}