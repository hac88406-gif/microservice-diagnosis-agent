package com.demo.tool.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具统一响应构造器（工具契约的落地）。
 *
 * <p>成功外层：{@code {code, data, traceId, costMs}}
 * <br>错误外层：{@code {code, message, retryable, hint, traceId, costMs}}
 *
 * <p>两个刻意的设计决定：
 * <ol>
 *   <li><b>业务错误一律 HTTP 200</b>：错误语义放在 body 的 code/hint 里。
 *       Agent 消费端只需解析 JSON、不用区分"HTTP 层错误"与"业务层错误"两套逻辑，
 *       也避免了 4xx/5xx 被中间件（网关/重试器）拦截重试，掩盖真实语义；</li>
 *   <li><b>hint 一律提供</b>：它是"给模型的下一步建议"，用于替代"盲目重试"——
 *       模型看到 retryable=false + 明确 hint 就会改参数或换工具，
 *       而不是原地重试同一个调用。</li>
 * </ol>
 */
public final class ApiResponse {

    private ApiResponse() {
    }

    /**
     * 成功响应。
     *
     * @param data    工具结果（已完成裁剪：TopN / 字段白名单 / 无敏感信息）
     * @param traceId 本次调用链路 ID（透传或生成）
     * @param costMs  工具执行耗时（毫秒）
     */
    public static Map<String, Object> ok(Object data, String traceId, long costMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCodes.OK);
        body.put("data", data);
        body.put("traceId", traceId);
        body.put("costMs", costMs);
        return body;
    }

    /**
     * 错误响应。
     *
     * @param code      错误码（见 {@link ErrorCodes}）
     * @param message   中文错误描述（面向人/日志）
     * @param retryable 是否建议重试：true=可重试（如超时）；false=不可重试（如参数错、无数据）
     * @param hint      给模型的下一步建议（防盲目重试；必须可执行）
     */
    public static Map<String, Object> error(String code, String message,
                                            boolean retryable, String hint,
                                            String traceId, long costMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("retryable", retryable);
        body.put("hint", hint);
        body.put("traceId", traceId);
        body.put("costMs", costMs);
        return body;
    }
}