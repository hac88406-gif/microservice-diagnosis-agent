package com.demo.tool.web;

/**
 * 工具业务异常：携带契约错误码 / 是否可重试 / 给模型的 hint。
 *
 * <p>抛出点：工具服务的业务层（如服务不存在、窗口内无数据、下游全部超时）；
 * 捕获点：{@link ToolExceptionHandler} 统一转成契约错误响应（HTTP 200 + 错误 body）。
 */
public class ToolException extends RuntimeException {

    /** 错误码（{@link ErrorCodes} 中的常量） */
    private final String code;

    /** 是否建议重试（超时=true；参数错/无数据/服务不存在=false） */
    private final boolean retryable;

    /** 给模型的下一步建议（必须可执行，禁止"请重试"这种空话） */
    private final String hint;

    public ToolException(String code, String message, boolean retryable, String hint) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.hint = hint;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getHint() {
        return hint;
    }
}