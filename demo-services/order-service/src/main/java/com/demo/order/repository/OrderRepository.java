package com.demo.order.repository;

import com.demo.order.model.OrderRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 订单数据访问层（JdbcTemplate 直写 SQL）。
 *
 * <p>为什么用 JdbcTemplate 而不是 JPA：
 * 被诊断环境要"小而透明"——SQL 必须可见可改（慢 SQL 排查、
 * 连接池问题排查都依赖能精确控制 SQL 行为），ORM 的隐式行为反而增加噪音。
 */
@Repository
public class OrderRepository {

    /** 时间统一按 yyyy-MM-dd HH:mm:ss 输出，避免各端时区/格式差异 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 行映射：orders 表 -> OrderRecord */
    private static final RowMapper<OrderRecord> ROW_MAPPER = (rs, rowNum) -> new OrderRecord(
            rs.getLong("id"),
            rs.getString("sku"),
            rs.getInt("quantity"),
            rs.getBigDecimal("amount"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime().format(TIME_FORMATTER));

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按订单号查询 */
    public Optional<OrderRecord> findById(long id) {
        List<OrderRecord> list = jdbcTemplate.query(
                "SELECT id, sku, quantity, amount, status, created_at FROM orders WHERE id = ?",
                ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 插入订单并回填自增主键。
     *
     * @return 落库后的完整订单记录（含 id 与 created_at）
     */
    public OrderRecord insert(String sku, int quantity, BigDecimal amount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO orders (sku, quantity, amount, status) VALUES (?, ?, ?, 'CREATED')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sku);
            ps.setInt(2, quantity);
            ps.setBigDecimal(3, amount);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("订单插入成功但未取到自增主键");
        }
        // 回查一次，保证返回的 created_at 与库中真实值一致（不要用内存时间假装）
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("订单插入后回查失败: id=" + key.longValue()));
    }
}