package com.demo.common.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 故障注入端点（/internal/fault）：
 *
 * <pre>
 *   POST   /internal/fault  注入故障，body 示例：
 *          {"type":"latency","pathRegex":"/api/.*","percent":100,"latencyMs":3000}
 *          {"type":"exception","pathRegex":"/api/orders/.*","percent":50,"message":"库存服务内部异常"}
 *   GET    /internal/fault  查询当前故障
 *   DELETE /internal/fault  清除当前故障
 * </pre>
 *
 * <p>约束：同一时刻只允许一个活动故障；已有故障时 POST 返回 409，
 * 并在响应里给出当前故障与清除提示（hint）。
 */
@RestController
@RequestMapping("/internal/fault")
public class FaultController {

    private static final Logger log = LoggerFactory.getLogger(FaultController.class);

    private final FaultInjectionManager faultInjectionManager;

    public FaultController(FaultInjectionManager faultInjectionManager) {
        this.faultInjectionManager = faultInjectionManager;
    }

    /** 注入故障 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> inject(@RequestBody FaultSpec spec) {
        // 1) 参数校验：字段缺失/越界直接 400，返回中文原因便于人工与脚本排查
        try {
            validate(spec);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody("INVALID_FAULT_SPEC", e.getMessage()));
        }

        // 2) 激活故障：已有活动故障时返回 409（单一变量原则）
        try {
            faultInjectionManager.activate(spec);
        } catch (IllegalStateException e) {
            FaultSpec current = faultInjectionManager.current();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "FAULT_ALREADY_ACTIVE");
            body.put("message", e.getMessage());
            body.put("current", current == null ? null : current.toMap());
            body.put("hint", "先执行 DELETE /internal/fault 清除现有故障，再注入新故障");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        log.warn("[故障注入] 已激活: {}", spec.toMap());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("fault", spec.toMap());
        return ResponseEntity.ok(body);
    }

    /** 查询当前故障 */
    @GetMapping
    public Map<String, Object> status() {
        FaultSpec current = faultInjectionManager.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", current != null);
        body.put("fault", current == null ? null : current.toMap());
        return body;
    }

    /** 清除当前故障 */
    @DeleteMapping
    public Map<String, Object> clear() {
        boolean cleared = faultInjectionManager.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cleared", cleared);
        if (!cleared) {
            body.put("message", "当前没有活动故障");
        }
        return body;
    }

    // ---------------- 内部辅助 ----------------

    /** 校验注入参数；不合法时抛 IllegalArgumentException（消息为中文原因） */
    private void validate(FaultSpec spec) {
        if (spec == null || spec.getType() == null || spec.getType().isBlank()) {
            throw new IllegalArgumentException("type 不能为空，可选值：latency / exception");
        }
        if (!FaultSpec.TYPE_LATENCY.equals(spec.getType()) && !FaultSpec.TYPE_EXCEPTION.equals(spec.getType())) {
            throw new IllegalArgumentException("type 仅支持 latency / exception，当前值：" + spec.getType());
        }
        if (spec.getPathRegex() == null || spec.getPathRegex().isBlank()) {
            throw new IllegalArgumentException("pathRegex 不能为空，例如 /api/.*");
        }
        try {
            spec.compile(); // 预编译校验正则合法性
        } catch (Exception e) {
            throw new IllegalArgumentException("pathRegex 不是合法正则：" + e.getMessage());
        }
        int percent = spec.getPercent() == null ? 100 : spec.getPercent();
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent 取值范围 0~100，当前值：" + percent);
        }
        if (FaultSpec.TYPE_LATENCY.equals(spec.getType())) {
            long latencyMs = spec.getLatencyMs() == null ? 1000L : spec.getLatencyMs();
            if (latencyMs <= 0 || latencyMs > FaultSpec.MAX_LATENCY_MS) {
                throw new IllegalArgumentException("latencyMs 取值范围 1~" + FaultSpec.MAX_LATENCY_MS
                        + "，当前值：" + latencyMs);
            }
        }
    }

    /** 构造统一错误体 */
    private Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}