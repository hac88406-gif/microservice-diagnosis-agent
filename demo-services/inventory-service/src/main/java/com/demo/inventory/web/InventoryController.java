package com.demo.inventory.web;

import com.demo.inventory.repository.InventoryRepository;
import com.demo.inventory.repository.InventoryRepository.InventoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 库存接口：
 * <pre>
 *   GET  /api/inventory/{sku}      查询库存
 *   POST /api/inventory/deduct     扣减库存（由 order-service 通过 HTTP 调用）
 * </pre>
 *
 * <p>错误响应统一 {@code {"error":"CODE","message":"中文描述"}}：
 * SKU_NOT_FOUND(404) / INSUFFICIENT_STOCK(409) / INVALID_PARAM(400)。
 * order-service 会把下游 error 码原样透传给调用方，形成一致的错误语义链。
 */
@RestController
@RequestMapping("/api")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    /** SKU 格式校验：1~64 位字母/数字/下划线/中划线 */
    private static final String SKU_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /** 单次扣减数量上限 */
    private static final int MAX_QUANTITY = 10_000;

    private final InventoryRepository inventoryRepository;

    public InventoryController(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /** 查询库存 */
    @GetMapping("/inventory/{sku}")
    public ResponseEntity<?> getInventory(@PathVariable String sku) {
        Optional<InventoryItem> item = inventoryRepository.findBySku(sku);
        if (item.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("SKU_NOT_FOUND", "商品不存在: sku=" + sku));
        }
        return ResponseEntity.ok(item.get());
    }

    /** 扣减库存（跨服务调用的下游接口） */
    @PostMapping("/inventory/deduct")
    public ResponseEntity<?> deduct(@RequestBody(required = false) DeductRequest request) {
        // 1) 参数校验
        String invalidReason = validate(request);
        if (invalidReason != null) {
            return ResponseEntity.badRequest().body(error("INVALID_PARAM", invalidReason));
        }
        String sku = request.sku();
        int quantity = request.quantity();

        // 2) 先确认 SKU 是否存在（用于区分 404 与 409 两种语义）
        Optional<InventoryItem> before = inventoryRepository.findBySku(sku);
        if (before.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("SKU_NOT_FOUND", "商品不存在: sku=" + sku));
        }

        // 3) 原子扣减：影响行数为 0 说明库存不足
        int updated = inventoryRepository.deduct(sku, quantity);
        if (updated == 0) {
            log.warn("库存不足拒绝扣减: sku={}, 需求={}, 现有={}", sku, quantity, before.get().quantity());
            Map<String, Object> body = error("INSUFFICIENT_STOCK", "库存不足: 需求 " + quantity
                    + "，现有 " + before.get().quantity());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        // 4) 回查扣减后的真实库存（不要用内存计算假装）
        InventoryItem after = inventoryRepository.findBySku(sku).orElseThrow();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", after.sku());
        body.put("remaining", after.quantity());
        body.put("price", after.price());
        return ResponseEntity.ok(body);
    }

    // ---------------- 内部辅助 ----------------

    /** 参数校验：返回 null 表示合法 */
    private String validate(DeductRequest request) {
        if (request == null) {
            return "请求体不能为空";
        }
        if (request.sku() == null || request.sku().isBlank()) {
            return "sku 不能为空";
        }
        if (!request.sku().matches(SKU_PATTERN)) {
            return "sku 格式非法（仅允许字母/数字/下划线/中划线，长度 1~64）: " + request.sku();
        }
        if (request.quantity() == null) {
            return "quantity 不能为空";
        }
        if (request.quantity() <= 0) {
            return "quantity 必须为正整数: " + request.quantity();
        }
        if (request.quantity() > MAX_QUANTITY) {
            return "quantity 超过单次上限 " + MAX_QUANTITY + ": " + request.quantity();
        }
        return null;
    }

    /** 构造统一错误体 */
    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    /** 扣减请求体 */
    public record DeductRequest(String sku, Integer quantity) {
    }
}