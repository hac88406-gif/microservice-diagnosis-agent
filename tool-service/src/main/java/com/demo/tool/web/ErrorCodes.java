package com.demo.tool.web;

/**
 * 工具契约错误码（统一外层 {@code code} 字段的取值）。
 *
 * <p>设计原则：错误码是给「模型」看的分类标签，不是给机器看的 HTTP 状态——
 * 每个码必须能对应一个明确的"下一步动作"（写进 hint 字段）：
 * <ul>
 *   <li>{@link #OK}                    成功</li>
 *   <li>{@link #INVALID_PARAM}         参数不合法 → hint 给出合法取值列表，直接纠正参数</li>
 *   <li>{@link #SERVICE_NOT_FOUND}     服务不存在 → hint 列出可用服务名，纠正后重试</li>
 *   <li>{@link #NO_DATA}               无数据（可能是窗口内确实没流量）→ hint 建议换时间窗口，
 *                                      并提示"SQL/接口层可能不是瓶颈"这类结论本身有效，避免模型硬凑答案</li>
 *   <li>{@link #DOWNSTREAM_TIMEOUT}    下游超时（可重试）</li>
 *   <li>{@link #DOWNSTREAM_UNAVAILABLE}下游不可达（不可重试，先查实例状态，防盲目重试）</li>
 *   <li>{@link #INTERNAL_ERROR}        工具服务内部错误</li>
 * </ul>
 */
public final class ErrorCodes {

    /** 成功 */
    public static final String OK = "OK";

    /** 参数不合法（缺少必填 / 取值越界） */
    public static final String INVALID_PARAM = "INVALID_PARAM";

    /** 服务不存在 */
    public static final String SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND";

    /** 时间窗口内无数据 */
    public static final String NO_DATA = "NO_DATA";

    /** 下游响应超时（可重试） */
    public static final String DOWNSTREAM_TIMEOUT = "DOWNSTREAM_TIMEOUT";

    /** 下游不可达（连接被拒等） */
    public static final String DOWNSTREAM_UNAVAILABLE = "DOWNSTREAM_UNAVAILABLE";

    /** 工具服务内部错误 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}