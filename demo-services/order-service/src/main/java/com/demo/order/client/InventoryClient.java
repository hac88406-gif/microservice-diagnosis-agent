package com.demo.order.client;

import com.demo.order.config.OrderServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * 库存服务调用客户端（order → inventory 的真实跨服务链路）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>超时必须显式配置</b>：读超时（默认 1.5s）小于下游 3s 注入延迟，
 *       保证下游变慢时能被快速感知，而不是无限等待；</li>
 *   <li><b>异常分类</b>：把底层网络异常映射为「超时 / 不可达 / 业务错误 / 响应异常」四类，
 *       让 Controller 能返回精确的错误码与 HTTP 状态（诊断时"错误类型"是重要证据）；</li>
 *   <li><b>业务错误透传</b>：下游 404/409（SKU 不存在 / 库存不足）属于正常业务语义，
 *       解析下游 error 字段后原样透传，不当作系统故障。</li>
 * </ul>
 */
@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(OrderServiceProperties properties) {
        // 显式设置连接/读取超时：诊断环境的"慢"必须能被感知，而不是被无限等待吞掉
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getInventory().getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getInventory().getReadTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getInventory().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 调用库存服务扣减库存。
     *
     * @return 扣减结果（含剩余库存与单价，供订单计算金额）
     * @throws TimeoutException     下游响应超时（对应错误码 INVENTORY_TIMEOUT，HTTP 504）
     * @throws UnavailableException 下游不可达/连接被拒（INVENTORY_UNAVAILABLE，HTTP 503）
     * @throws BusinessException    下游返回 4xx 业务错误（如 SKU_NOT_FOUND / INSUFFICIENT_STOCK）
     * @throws ServerErrorException 下游返回 5xx 服务端错误（如注入的 exception 故障）
     * @throws BadResponseException 下游响应无法解析（INVENTORY_BAD_RESPONSE，HTTP 502）
     */
    public DeductResult deduct(String sku, int quantity) {
        try {
            JsonNode body = restClient.post()
                    .uri("/api/inventory/deduct")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sku", sku, "quantity", quantity))
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new BadResponseException("库存服务返回空响应体");
            }
            return new DeductResult(
                    body.path("sku").asText(sku),
                    body.path("remaining").asInt(),
                    new BigDecimal(body.path("price").asText("0")));
        } catch (HttpClientErrorException e) {
            // 下游 4xx：业务语义错误（SKU 不存在 / 库存不足），透传状态码与 error 字段
            throw new BusinessException(e.getStatusCode().value(),
                    parseErrorCode(e.getResponseBodyAsString()), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            // 下游 5xx：服务端故障（如注入的 exception 故障），必须与 4xx 业务拒绝区分——
            // 混在一起会让诊断 Agent 误判成"业务被拒绝"而不是"下游服务出错"
            throw new ServerErrorException("库存服务返回 5xx: " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // 连接阶段失败（连接超时/连接被拒），尚未开始读取响应
            if (isCausedBy(e, SocketTimeoutException.class)) {
                throw new TimeoutException("调用库存服务超时", e);
            }
            throw new UnavailableException("库存服务不可达: " + rootCauseSimpleName(e), e);
        } catch (RestClientException e) {
            // 其余 RestClient 异常统一按「异常链根因」分类。
            // 实现要点：读超时可能在响应消息转换阶段被包装成普通 RestClientException
            // （"Error while extracting response for type ..."），只判断最外层类型会误判为"响应异常"，
            // 因此必须沿异常链找根因（SocketTimeoutException / ConnectException）。
            if (isCausedBy(e, SocketTimeoutException.class)) {
                throw new TimeoutException("调用库存服务超时", e);
            }
            if (isCausedBy(e, ConnectException.class)) {
                throw new UnavailableException("库存服务不可达: ConnectException", e);
            }
            throw new BadResponseException("库存服务响应异常: " + rootCauseSimpleName(e));
        }
    }

    // ---------------- 内部辅助 ----------------

    /** 从下游错误响应体中提取 error 字段（如 SKU_NOT_FOUND），失败则给 UNKNOWN */
    private String parseErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "UNKNOWN";
        }
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            return node.path("error").asText("UNKNOWN");
        } catch (Exception ignored) {
            return "UNKNOWN";
        }
    }

    /** 异常链中是否存在指定类型的根因 */
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

    /** 取最底层异常类名（如 ConnectException），便于日志定位且不含地址信息 */
    private String rootCauseSimpleName(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }

    // ============================================================
    // 返回值与异常定义（就地为调用方提供精确语义）
    // ============================================================

    /** 扣减结果 */
    public record DeductResult(String sku, int remaining, BigDecimal price) {
    }

    /** 调用库存服务失败的基类异常 */
    public static class InventoryCallException extends RuntimeException {
        public InventoryCallException(String message) {
            super(message);
        }

        public InventoryCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 下游响应超时 */
    public static class TimeoutException extends InventoryCallException {
        public TimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 下游不可达（连接被拒 / 网络不可达） */
    public static class UnavailableException extends InventoryCallException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 下游业务错误（4xx/5xx，携带下游状态码与错误码） */
    public static class BusinessException extends InventoryCallException {
        private final int downstreamStatus;
        private final String errorCode;

        public BusinessException(int downstreamStatus, String errorCode, String message) {
            super("下游业务错误 status=" + downstreamStatus + ", error=" + errorCode + ", body=" + message);
            this.downstreamStatus = downstreamStatus;
            this.errorCode = errorCode;
        }

        public int getDownstreamStatus() {
            return downstreamStatus;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /** 下游响应异常（无法解析的响应体） */
    public static class BadResponseException extends InventoryCallException {
        public BadResponseException(String message) {
            super(message);
        }
    }

    /** 下游 5xx 服务端错误（如注入的 exception 故障） */
    public static class ServerErrorException extends InventoryCallException {
        public ServerErrorException(String message) {
            super(message);
        }
    }
}