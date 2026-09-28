package com.demo.tool.controller;

import com.demo.tool.service.HealthToolService;
import com.demo.tool.web.ApiResponse;
import com.demo.tool.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工具 HTTP 入口（本提交先落 T1；T2 随「接口指标工具」一并接入）。
 *
 * <pre>
 *   GET /tools/get_service_health?service=order-service
 * </pre>
 *
 * <p>说明：
 * <ul>
 *   <li>成功统一由 {@link ApiResponse#ok} 包装（code/data/traceId/costMs）；</li>
 *   <li>业务错误由 {@link com.demo.tool.web.ToolExceptionHandler} 统一转换为
 *       {code,message,retryable,hint,traceId,costMs}（HTTP 200，语义在 body）；</li>
 *   <li>success 与 error 共用同一套 traceId / costMs 口径（TraceIdFilter 注入）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/tools")
public class ToolController {

    private final HealthToolService healthToolService;

    public ToolController(HealthToolService healthToolService) {
        this.healthToolService = healthToolService;
    }

    /** 服务与实例健康（service 可省略 = 全部） */
    @GetMapping("/get_service_health")
    public Map<String, Object> getServiceHealth(
            @RequestParam(value = "service", required = false) String service,
            HttpServletRequest request) {
        String traceId = TraceIdFilter.currentTraceId(request);
        Map<String, Object> data = healthToolService.getServiceHealth(service, traceId);
        return ApiResponse.ok(data, traceId, TraceIdFilter.costMs(request));
    }
}