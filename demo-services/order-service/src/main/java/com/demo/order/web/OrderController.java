package com.demo.order.web;

import com.demo.order.client.InventoryClient;
import com.demo.order.model.OrderRecord;
import com.demo.order.service.OrderService;
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

/**
 * 订单接口：
 * <pre>
 *   GET  /api/orders/{id}   查询订单（Redis 缓存 → MySQL 回源 → 回填）
 *   POST /api/orders        创建订单（HTTP 调库存服务扣减 → 落库）
 * </pre>
 *
 * <p>错误响应统一为 {@code {"error":"CODE","message":"中文描述"}}，
 * 与库存服务的错误格式保持一致，便于 Agent 侧统一解析。
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    /** SKU 格式校验：1~64 位字母/数字/下划线/中划线（非法参数要能被明确识别并记录） */
    private static final String SKU_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /** 单次下单数量上限（防御性校验，也用于参数异常场景） */
    private static final int MAX_QUANTITY = 10_000;

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 查询订单 */
    @GetMapping("/orders/{id}")
    public ResponseEntity<?> getOrder(@PathVariable long id) {
        OrderRecord.OrderView view = orderService.getOrder(id);
        if (view == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("ORDER_NOT_FOUND", "订单不存在: id=" + id));
        }
        return ResponseEntity.ok(view);
    }

    /** 创建订单 */
    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody(required = false) CreateOrderRequest request) {
        // 1) 参数校验：非法参数直接 400，并给出明确原因（供日志指纹聚合）
        String invalidReason = validate(request);
        if (invalidReason != null) {
            return ResponseEntity.badRequest().body(error("INVALID_PARAM", invalidReason));
        }

        // 2) 调用库存服务 + 落库：按下游异常类型返回精确错误码与状态码
        try {
            OrderRecord.OrderView view = orderService.createOrder(request.sku(), request.quantity());
            return ResponseEntity.status(HttpStatus.CREATED).body(view);
        } catch (InventoryClient.TimeoutException e) {
            log.warn("创建订单失败：库存服务超时, sku={}", request.sku());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(error("INVENTORY_TIMEOUT", "调用库存服务超时，库存未知，请勿直接重试下单"));
        } catch (InventoryClient.UnavailableException e) {
            log.warn("创建订单失败：库存服务不可达, sku={}, err={}", request.sku(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("INVENTORY_UNAVAILABLE", "库存服务不可达"));
        } catch (InventoryClient.BusinessException e) {
            log.warn("创建订单失败：库存服务业务错误, sku={}, downstream={}/{}",
                    request.sku(), e.getDownstreamStatus(), e.getErrorCode());
            return ResponseEntity.status(e.getDownstreamStatus())
                    .body(error(e.getErrorCode(), "库存服务拒绝了本次扣减: " + e.getErrorCode()));
        } catch (InventoryClient.ServerErrorException e) {
            log.error("创建订单失败：库存服务 5xx, sku={}, err={}", request.sku(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("INVENTORY_SERVER_ERROR", "库存服务内部错误（5xx），请结合日志排查其异常堆栈"));
        } catch (InventoryClient.BadResponseException e) {
            log.error("创建订单失败：库存服务响应异常, sku={}, err={}", request.sku(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("INVENTORY_BAD_RESPONSE", "库存服务响应无法解析"));
        }
    }

    // ---------------- 内部辅助 ----------------

    /** 参数校验：返回 null 表示合法，否则返回中文原因 */
    private String validate(CreateOrderRequest request) {
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

    /** 创建订单请求体 */
    public record CreateOrderRequest(String sku, Integer quantity) {
    }
}