package com.demo.common.fault;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 故障注入规格：描述「对哪条路径、以多大比例、注入什么故障」。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code type}      故障类型：latency（加延迟）/ exception（抛异常）</li>
 *   <li>{@code pathRegex} 路径正则，使用「整体匹配」语义（{@code matches()}），
 *       例如 {@code /api/.*} 命中所有业务接口，{@code /api/orders/1} 精确命中单条接口</li>
 *   <li>{@code percent}   命中概率（0~100），100 表示每次都命中（100 表示每次都命中，便于复现）</li>
 *   <li>{@code latencyMs} latency 类型专用：注入的延迟毫秒数（上限 10s，防止把实例卡死）</li>
 *   <li>{@code message}   exception 类型专用：注入异常的消息（用于日志指纹识别）</li>
 * </ul>
 *
 * <p>本对象同时充当 {@code POST /internal/fault} 的请求体 DTO；
 * {@link #compile()} 需要在生效前调用一次（把正则字符串编译成 Pattern）。
 */
public class FaultSpec {

    /** 故障类型：加延迟 */
    public static final String TYPE_LATENCY = "latency";

    /** 故障类型：抛异常 */
    public static final String TYPE_EXCEPTION = "exception";

    /** 延迟上限（毫秒）：防止误注入导致实例被长时间卡死 */
    public static final long MAX_LATENCY_MS = 10_000L;

    private String type;
    private String pathRegex;
    private Integer percent = 100;
    private Long latencyMs = 1000L;
    private String message;

    /** 运行时字段：编译后的正则（不参与序列化） */
    private transient Pattern compiledPattern;

    /** 编译正则；正则非法时抛 IllegalArgumentException（由 Controller 转为 400 响应） */
    public void compile() {
        this.compiledPattern = Pattern.compile(pathRegex);
    }

    /** 请求路径是否命中本故障（整体匹配语义） */
    public boolean matchesPath(String path) {
        return compiledPattern != null && compiledPattern.matcher(path).matches();
    }

    /** 按 percent 概率决定本次请求是否真的注入故障（percent=100 时必中） */
    public boolean shouldHit() {
        int p = percent == null ? 100 : percent;
        return p >= 100 || ThreadLocalRandom.current().nextInt(100) < p;
    }

    /** 输出给注入端点的可读描述（不含任何敏感信息） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("pathRegex", pathRegex);
        map.put("percent", percent);
        if (TYPE_LATENCY.equals(type)) {
            map.put("latencyMs", latencyMs);
        } else {
            map.put("message", message);
        }
        return map;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPathRegex() {
        return pathRegex;
    }

    public void setPathRegex(String pathRegex) {
        this.pathRegex = pathRegex;
    }

    public Integer getPercent() {
        return percent;
    }

    public void setPercent(Integer percent) {
        this.percent = percent;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}