package com.demo.common.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 故障注入过滤器：请求进入业务逻辑前，按当前活动故障决定是否注入延迟/异常。
 *
 * <p>关键设计：
 * <ul>
 *   <li>执行顺序在 MetricsFilter <b>之内层</b>（{@code HIGHEST_PRECEDENCE + 10}），
 *       保证注入的延迟被指标统计到——否则指标里的 P95 不会升高，故障就"隐身"了；</li>
 *   <li>永远跳过 {@code /internal/**}：注入端点/健康检查/指标本身必须始终可用，
 *       否则注入故障后连"清除故障"都做不到；</li>
 *   <li>异常注入：先打印带堆栈的 ERROR 日志（供日志检索按异常指纹聚合），
 *       再把异常抛给容器 → 容器返回 500，与"真实代码缺陷"的表现一致。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FaultInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    private final FaultInjectionManager faultInjectionManager;

    public FaultInjectionFilter(FaultInjectionManager faultInjectionManager) {
        this.faultInjectionManager = faultInjectionManager;
    }

    /** /internal/** 永不受故障影响（保证注入与清除能力自身可用） */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        FaultSpec fault = faultInjectionManager.current();
        String path = request.getRequestURI();

        if (fault != null && fault.matchesPath(path) && fault.shouldHit()) {
            if (FaultSpec.TYPE_LATENCY.equals(fault.getType())) {
                // 注入延迟：模拟下游变慢 / 线程阻塞
                long sleepMs = Math.min(fault.getLatencyMs() == null ? 1000L : fault.getLatencyMs(),
                        FaultSpec.MAX_LATENCY_MS);
                log.warn("[故障注入] latency 命中: path={}, 注入延迟={}ms", path, sleepMs);
                sleepQuietly(sleepMs);
            } else {
                // 注入异常：模拟代码缺陷抛错（先记日志再抛出，保证异常指纹可检索）
                String message = fault.getMessage() == null ? "injected-exception" : fault.getMessage();
                InjectedException ex = new InjectedException("注入异常: " + message);
                log.error("[故障注入] exception 命中: path={}, 抛出注入异常", path, ex);
                throw ex;
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 睡眠辅助方法：被中断时恢复中断标记并提前返回，避免吞掉中断信号 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}