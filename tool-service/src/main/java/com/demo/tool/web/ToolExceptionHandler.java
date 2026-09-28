package com.demo.tool.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：把任何异常都转换成契约格式的响应（HTTP 200 + 错误 body）。
 *
 * <p>两类异常：
 * <ul>
 *   <li>{@link ToolException}：业务层主动抛出的"预期内错误"（服务不存在 / 无数据 / 超时），
 *       直接按契约输出，不回显堆栈（避免把内部细节喂给模型）；</li>
 *   <li>其他未预期异常：记录完整堆栈到服务端日志，对外返回 INTERNAL_ERROR +
 *       明确的 hint（提示模型不要盲目重试、可换工具获取环境信息）。</li>
 * </ul>
 */
@RestControllerAdvice
public class ToolExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolExceptionHandler.class);

    /** 业务层预期错误：按契约输出 */
    @ExceptionHandler(ToolException.class)
    public Map<String, Object> handleToolException(ToolException e, HttpServletRequest request) {
        String traceId = TraceIdFilter.currentTraceId(request);
        long costMs = TraceIdFilter.costMs(request);
        log.warn("[工具业务异常] traceId={}, code={}, message={}", traceId, e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage(), e.isRetryable(), e.getHint(), traceId, costMs);
    }

    /** 未预期异常：日志留全量堆栈，对外只给稳定语义 */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleUnexpectedException(Exception e, HttpServletRequest request) {
        String traceId = TraceIdFilter.currentTraceId(request);
        long costMs = TraceIdFilter.costMs(request);
        log.error("[工具内部异常] traceId={}", traceId, e);
        return ApiResponse.error(
                ErrorCodes.INTERNAL_ERROR,
                "工具服务内部错误: " + e.getClass().getSimpleName(),
                false,
                "不要重复调用同一工具；可先用 get_service_health 获取环境整体状态，"
                        + "若本错误持续出现，请改用其他工具或检查工具服务日志",
                traceId, costMs);
    }
}