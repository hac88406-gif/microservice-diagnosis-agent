package com.demo.common.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 指标采集过滤器：把每次业务接口调用的「耗时 + 是否错误」写入 {@link MetricsCollector}。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>最外层执行</b>（{@code HIGHEST_PRECEDENCE}）：比故障注入过滤器更外层，
 *       这样注入的延迟会计入耗时——指标工具才能看到 P95 升高；</li>
 *   <li><b>跳过 /internal/\*\*</b>：健康检查、指标查询、故障注入三类内部端点
 *       自身不计入业务指标（否则工具自身的高频调用会污染接口统计）；</li>
 *   <li><b>路径取「模板」而非真实 URI</b>：使用 Spring 的 bestMatchingPattern
 *       （如 /api/orders/{id}），避免每个订单 ID 变成独立接口；</li>
 *   <li><b>错误口径</b>：抛出未捕获异常（如注入的 exception 故障）或 HTTP 状态 &gt;= 500。
 *       注入异常时异常在过滤器链中向上抛，此刻响应状态还没被容器改成 500，
 *       因此必须在 catch 分支单独标记——这正是 finally 里 {@code thrown != null} 判断的用途。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MetricsFilter extends OncePerRequestFilter {

    private final MetricsCollector metricsCollector;

    public MetricsFilter(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /** /internal/** 不计入 metrics */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNs = System.nanoTime();
        Throwable thrown = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            // 记录后原样抛出：容器仍然按 500 处理，不改变业务语义
            thrown = e;
            throw e;
        } finally {
            long costMs = (System.nanoTime() - startNs) / 1_000_000L;
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = pattern != null ? pattern.toString() : request.getRequestURI();
            boolean error = thrown != null || response.getStatus() >= 500;
            metricsCollector.record(request.getMethod(), path, costMs, error);
        }
    }
}