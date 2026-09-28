package com.demo.tool.client;

import com.demo.tool.config.ToolServiceProperties;
import com.demo.tool.web.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * 被诊断服务调用客户端（工具服务 → 业务服务 /internal/\*\*）。
 *
 * <p>三个关键约定：
 * <ol>
 *   <li><b>超时必须显式</b>：连接 500ms / 读 1500ms（可配）。诊断工具最怕"被假死实例拖死"——
 *       没有超时，一次调用就会挂满整个工具预算；</li>
 *   <li><b>失败原因枚举化</b>：底层异常统一映射为 {@link FailureReason}（TIMEOUT / CONNECTION_REFUSED /
 *       HTTP_ERROR / UNEXPECTED）。向上层只传枚举，不传原始异常消息——
 *       原始消息里可能带 IP/端口，违反"敏感字段不返回"的契约；完整详情只进服务端日志；</li>
 *   <li><b>TraceId 透传</b>：请求头带 X-Trace-Id，为后续"Agent → 工具服务 → 业务服务"全链路串联打底。</li>
 * </ol>
 */
@Component
public class DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);

    /** 共享的超时工厂（每个实例一个 RestClient，但共用同一套超时策略） */
    private final SimpleClientHttpRequestFactory requestFactory;

    public DownstreamClient(ToolServiceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getHttp().getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getHttp().getReadTimeoutMs()));
        this.requestFactory = factory;
    }

    /**
     * GET 一个下游 JSON 端点并解析为 JsonNode。
     *
     * @param baseUrl 实例基础地址（仅服务端使用，绝不返回给模型）
     * @param path    端点路径，如 /internal/health
     * @param traceId 链路 ID
     * @throws DownstreamException 任何失败（原因见 {@link FailureReason}）
     */
    public JsonNode getJson(String baseUrl, String path, String traceId) {
        try {
            JsonNode body = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory)
                    .build()
                    .get()
                    .uri(path)
                    .header(TraceIdFilter.TRACE_HEADER, traceId)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new DownstreamException(FailureReason.HTTP_ERROR, 0, "下游返回空响应体");
            }
            return body;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 下游明确返回了错误状态码（地址与详情只进日志）
            log.warn("[下游 HTTP 错误] {} {} -> status={}, traceId={}", baseUrl, path, e.getStatusCode().value(), traceId);
            throw new DownstreamException(FailureReason.HTTP_ERROR, e.getStatusCode().value(),
                    "下游返回 HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // 网络层异常：区分「超时」与「不可达」，两者对诊断的含义完全不同
            if (isCausedBy(e, SocketTimeoutException.class)) {
                log.warn("[下游超时] {} {}, traceId={}", baseUrl, path, traceId);
                throw new DownstreamException(FailureReason.TIMEOUT, 0, "下游响应超时");
            }
            log.warn("[下游不可达] {} {}, traceId={}, err={}", baseUrl, path, traceId, e.toString());
            throw new DownstreamException(FailureReason.CONNECTION_REFUSED, 0, "下游连接失败");
        } catch (RestClientException e) {
            log.warn("[下游响应异常] {} {}, traceId={}, err={}", baseUrl, path, traceId, e.toString());
            throw new DownstreamException(FailureReason.UNEXPECTED, 0, "下游响应异常");
        }
    }

    /** 异常链中是否存在指定类型（SimpleClientHttpRequestFactory 的读超时会被包在 ResourceAccessException 里） */
    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> type) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    // ============================================================
    // 失败原因与异常
    // ============================================================

    /** 下游调用失败的原因分类（枚举化，避免向上层泄露地址等细节） */
    public enum FailureReason {
        /** 响应超时（实例可能假死/被注入延迟） */
        TIMEOUT,
        /** 连接被拒 / 网络不可达（实例可能已下线） */
        CONNECTION_REFUSED,
        /** HTTP 错误状态码 */
        HTTP_ERROR,
        /** 其他异常 */
        UNEXPECTED
    }

    /** 下游调用异常：携带原因枚举与（可选的）HTTP 状态码 */
    public static class DownstreamException extends RuntimeException {

        private final FailureReason reason;
        private final int httpStatus;

        public DownstreamException(FailureReason reason, int httpStatus, String message) {
            super(message);
            this.reason = reason;
            this.httpStatus = httpStatus;
        }

        public FailureReason getReason() {
            return reason;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}