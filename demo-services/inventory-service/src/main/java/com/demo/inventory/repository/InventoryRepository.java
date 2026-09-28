package com.demo.inventory.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 库存数据访问层（JdbcTemplate 直写 SQL）。
 *
 * <p>扣减使用「UPDATE ... WHERE quantity >= ?」的原子写法：
 * 把"判断库存是否充足"和"扣减"合并为一条 SQL，由数据库保证并发安全，
 * 避免先查后改的竞态（并发下超卖）。这是库存业务的标准做法，
 * 也是后续「连接池/锁等待」类故障场景的真实素材。
 */
@Repository
public class InventoryRepository {

    /** 时间统一按 yyyy-MM-dd HH:mm:ss 输出 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 行映射：inventory 表 -> InventoryItem */
    private static final RowMapper<InventoryItem> ROW_MAPPER = (rs, rowNum) -> new InventoryItem(
            rs.getString("sku"),
            rs.getString("name"),
            rs.getInt("quantity"),
            rs.getBigDecimal("price"),
            rs.getTimestamp("updated_at").toLocalDateTime().format(TIME_FORMATTER));

    private final JdbcTemplate jdbcTemplate;

    public InventoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 SKU 查询库存 */
    public Optional<InventoryItem> findBySku(String sku) {
        List<InventoryItem> list = jdbcTemplate.query(
                "SELECT sku, name, quantity, price, updated_at FROM inventory WHERE sku = ?",
                ROW_MAPPER, sku);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 原子扣减库存。
     *
     * @return 影响行数：1=扣减成功；0=SKU 不存在或库存不足（由调用方区分）
     */
    public int deduct(String sku, int quantity) {
        return jdbcTemplate.update(
                "UPDATE inventory SET quantity = quantity - ? WHERE sku = ? AND quantity >= ?",
                quantity, sku, quantity);
    }

    // ============================================================
    // 数据模型
    // ============================================================

    /**
     * 库存记录。
     *
     * @param sku       商品 SKU
     * @param name      商品名称
     * @param quantity  可用库存
     * @param price     单价（下单时用于计算订单金额）
     * @param updatedAt 最近更新时间
     */
    public record InventoryItem(String sku, String name, int quantity, BigDecimal price, String updatedAt) {
    }
}