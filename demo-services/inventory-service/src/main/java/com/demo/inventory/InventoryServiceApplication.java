package com.demo.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 库存服务启动类（端口 8082）。
 *
 * <p>scanBasePackages 同时扫描 com.demo.common：
 * 注册 demo-common 提供的 /internal/health、/internal/metrics、/internal/fault 能力。
 */
@SpringBootApplication(scanBasePackages = {"com.demo.inventory", "com.demo.common"})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}