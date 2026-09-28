package com.demo.tool.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceId 过滤器：为每次工具调用建立链路 ID。
 *
 * <p>规则：
 * <ol>
 *   <li>优先读取上游请求头 {@code X-Trace-Id}（Agent 侧传入时即可实现全链路串联）；</li>
 *   <li>缺省生成 {@code trace-xxxxxxxxxxxx}（12 位十六进制）；</li>
 *   <li>写入 request attribute（供响应体回填）与 MDC（供日志输出，日志 pattern 已含 %X{traceId}）；</li>
 *   <li>响应头回写 X-Trace-Id，便于 curl / 前端定位本次调用。</li>
 * </ol>
 *
 * <p>同时记录请求开始时刻（attribute），统一在响应体里输出 costMs——
 * 保证"耗时"口径一致：从请求进入工具服务到响应生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 链路 ID 请求/响应头 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** request attribute key：当前 traceId */
    private static final String ATTR_TRACE_ID = "traceId";

    /** request attribute key：请求开始纳秒时刻 */
    private static final String ATTR_START_NS = "requestStartNs";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = generateTraceId();
        }
        request.setAttribute(ATTR_TRACE_ID, traceId);
        request.setAttribute(ATTR_START_NS, System.nanoTime());
        response.setHeader(TRACE_HEADER, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    /** 取当前请求的 traceId（过滤器保证一定存在） */
    public static String currentTraceId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_TRACE_ID);
        return value == null ? "unknown" : value.toString();
    }

    /** 取当前请求已耗时（毫秒） */
    public static long costMs(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_START_NS);
        if (value == null) {
            return 0L;
        }
        return (System.nanoTime() - (Long) value) / 1_000_000L;
    }

    /** 生成 traceId：trace- + 12 位十六进制（可读、够短、够随机） */
    private String generateTraceId() {
        return "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}