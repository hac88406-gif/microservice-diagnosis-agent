-- ============================================================
-- 微服务智能诊断 Agent · 被诊断环境数据库初始化脚本
-- ------------------------------------------------------------
-- 执行时机：docker-compose 中 MySQL 容器「首次启动、数据卷为空」时自动执行
-- 内容：建 orders / inventory 两张表 + 种子数据
-- 说明：
--   - orders 表的 idx_orders_sku 索引是刻意保留的：便于后续用「删除索引」对比慢 SQL 表现
--     需要有一个真实索引可被删除，从而观察扫描行数变化
--   - 所有时间字段默认 CURRENT_TIMESTAMP，便于日志/慢查询与业务时间对齐
-- ============================================================

SET NAMES utf8mb4;

USE demo;

-- ---------------- 库存表（inventory-service 读写） ----------------
CREATE TABLE IF NOT EXISTS inventory (
    sku        VARCHAR(64)   NOT NULL COMMENT '商品 SKU 编码，业务主键',
    name       VARCHAR(128)  NOT NULL COMMENT '商品名称',
    quantity   INT           NOT NULL DEFAULT 0 COMMENT '当前可用库存数量',
    price      DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '单价（元）',
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (sku)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '库存表（inventory-service 读写）';

-- ---------------- 订单表（order-service 读写） ----------------
CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单号（自增主键）',
    sku        VARCHAR(64)   NOT NULL COMMENT '商品 SKU',
    quantity   INT           NOT NULL COMMENT '购买数量',
    amount     DECIMAL(10,2) NOT NULL COMMENT '订单金额 = 单价 * 数量',
    status     VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT '订单状态：CREATED / PAID / CANCELLED',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    PRIMARY KEY (id),
    KEY idx_orders_sku (sku),                      -- 慢 SQL 验证时可删除该索引做对比
    KEY idx_orders_created_at (created_at)         -- 便于按时间范围统计订单
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单表（order-service 读写）';

-- ---------------- 种子数据：库存 ----------------
INSERT INTO inventory (sku, name, quantity, price) VALUES
    ('SKU-1001', '机械键盘 87 键',   120,  399.00),
    ('SKU-1002', '人体工学鼠标',     200,  199.00),
    ('SKU-1003', '4K 显示器 27 寸',   30, 1899.00),
    ('SKU-1004', 'USB-C 扩展坞',      80,  259.00),
    ('SKU-1005', '主动降噪耳机',      60,  899.00);

-- ---------------- 种子数据：订单 ----------------
INSERT INTO orders (id, sku, quantity, amount, status) VALUES
    (1, 'SKU-1001', 1,  399.00, 'CREATED'),
    (2, 'SKU-1002', 2,  398.00, 'CREATED'),
    (3, 'SKU-1004', 1,  259.00, 'PAID');