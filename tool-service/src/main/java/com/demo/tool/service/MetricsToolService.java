package com.demo.tool.service;

import com.demo.tool.client.DownstreamClient;
import com.demo.tool.config.ToolServiceProperties;
import com.demo.tool.config.ToolServiceProperties.InstanceDef;
import com.demo.tool.config.ToolServiceProperties.ServiceDef;
import com.demo.tool.web.ErrorCodes;
import com.demo.tool.web.ToolException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get_api_metrics}：接口指标汇总（QPS / P95 / P99 / 错误率 / 环比）。
 *
 * <p>工具契约要点：
 * <ul>
 *   <li>入参：{@code service}（必填）、{@code api?}、{@code window?}（5m/30m/1h，默认 30m）；</li>
 *   <li>出参：接口列表（按异常度排序），每接口 QPS、P95、P99、错误率、环比变化；</li>
 *   <li>裁剪：Top5 接口；每接口仅 6 个核心字段（api / qps / p95Ms / p99Ms / errorRate / change）；
 *       时间序列只给「趋势方向 + 峰值」，不给全量数据点；</li>
 *   <li>错误：无数据 → 不可重试（hint 建议换窗口）；超时 → 可重试。</li>
 * </ul>
 *
 * <p>计算口径（集中在这一处，便于对照维护）：
 * <ul>
 *   <li>窗口按「分钟边界」对齐：当前窗口 = 最近 N 个完整/进行中的分钟桶；上一窗口 = 再往前 N 个分钟桶；</li>
 *   <li>QPS = 窗口内请求总数 / 窗口秒数；错误率 = 错误数 / 总数 × 100；</li>
 *   <li>P95/P99 来自各分钟桶的时延样本合并后取分位（demo 侧每分钟最多保留 100 个样本，属近似分位）；</li>
 *   <li>异常度 = 时延分（P95/500ms，封顶 50）+ 错误分（错误率/5%，封顶 50）+ 环比恶化分（封顶 60），
 *       仅用于排序，不代表绝对严重程度。</li>
 * </ul>
 */
@Service
public class MetricsToolService {

    private static final Logger log = LoggerFactory.getLogger(MetricsToolService.class);

    private static final long MINUTE_MS = 60_000L;

    /** 支持的窗口 → 分钟数 */
    private static final Map<String, Integer> SUPPORTED_WINDOWS = Map.of(
            "5m", 5,
            "30m", 30,
            "1h", 60);

    /** 缺省窗口 */
    private static final String DEFAULT_WINDOW = "30m";

    /** 契约裁剪：最多返回 5 个接口 */
    private static final int TOP_N = 5;

    private final ToolServiceProperties properties;
    private final DownstreamClient downstreamClient;

    public MetricsToolService(ToolServiceProperties properties, DownstreamClient downstreamClient) {
        this.properties = properties;
        this.downstreamClient = downstreamClient;
    }

    /**
     * 汇总接口指标。
     *
     * @param service 服务名（必填）
     * @param api     接口过滤（可选，按路径或"方法 路径"做包含匹配）
     * @param window  时间窗口（5m/30m/1h，缺省 30m）
     * @param traceId 链路 ID
     */
    public Map<String, Object> getApiMetrics(String service, String api, String window, String traceId) {
        // ---------- 1) 参数校验（错误码 + 可执行 hint，防盲目重试） ----------
        if (service == null || service.isBlank()) {
            throw new ToolException(ErrorCodes.INVALID_PARAM,
                    "service 为必填参数",
                    false,
                    "可用服务：" + String.join("、", properties.serviceNames())
                            + "；请指定其中一个，例如 service=order-service");
        }
        ServiceDef serviceDef = properties.findService(service)
                .orElseThrow(() -> new ToolException(ErrorCodes.SERVICE_NOT_FOUND,
                        "服务不存在: " + service,
                        false,
                        "可用服务：" + String.join("、", properties.serviceNames())
                                + "；请用其中之一重试"));

        String win = (window == null || window.isBlank()) ? DEFAULT_WINDOW : window.trim();
        Integer windowMinutes = SUPPORTED_WINDOWS.get(win);
        if (windowMinutes == null) {
            throw new ToolException(ErrorCodes.INVALID_PARAM,
                    "window 仅支持 5m / 30m / 1h，当前值: " + window,
                    false,
                    "请改用 5m / 30m / 1h（缺省 30m）后重试");
        }

        // ---------- 2) 拉取所有实例的 /internal/metrics（逐实例容错） ----------
        long deadline = System.currentTimeMillis() + properties.getTool().getTotalTimeoutMs();
        List<InstanceMetrics> okList = new ArrayList<>();
        List<Map<String, Object>> instanceStates = new ArrayList<>();
        List<DownstreamClient.FailureReason> failureReasons = new ArrayList<>();

        for (InstanceDef instance : serviceDef.getInstances()) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", instance.getId());
            if (System.currentTimeMillis() > deadline) {
                state.put("ok", false);
                state.put("reason", "TOTAL_TIMEOUT");
                failureReasons.add(DownstreamClient.FailureReason.TIMEOUT);
                instanceStates.add(state);
                continue;
            }
            try {
                JsonNode metrics = downstreamClient.getJson(instance.getBaseUrl(), "/internal/metrics", traceId);
                okList.add(new InstanceMetrics(instance.getId(), metrics));
                state.put("ok", true);
            } catch (DownstreamClient.DownstreamException e) {
                state.put("ok", false);
                state.put("reason", e.getReason().name());
                failureReasons.add(e.getReason());
            }
            instanceStates.add(state);
        }

        // ---------- 3) 全部实例失败：按原因给错误（超时可重试，其余不可重试） ----------
        if (okList.isEmpty()) {
            if (failureReasons.contains(DownstreamClient.FailureReason.TIMEOUT)) {
                throw new ToolException(ErrorCodes.DOWNSTREAM_TIMEOUT,
                        "服务实例响应超时，指标采集失败",
                        true,
                        "可稍后重试一次；若持续超时，先调用 get_service_health(service=" + serviceDef.getName()
                                + ") 检查实例是否假死（进程在、接口不响应）");
            }
            throw new ToolException(ErrorCodes.DOWNSTREAM_UNAVAILABLE,
                    "服务实例不可达，指标采集失败",
                    false,
                    "不要重试本工具；先调用 get_service_health(service=" + serviceDef.getName()
                            + ") 确认实例状态，实例不可用时指标必然缺失");
        }

        // ---------- 4) 合并分钟桶 + 双窗口统计 ----------
        long now = System.currentTimeMillis();
        long nowMinuteIndex = now / MINUTE_MS;
        long currentStart = (nowMinuteIndex - windowMinutes + 1) * MINUTE_MS;   // 含当前进行中的分钟
        long currentEndExclusive = (nowMinuteIndex + 1) * MINUTE_MS;
        long previousStart = (nowMinuteIndex - 2L * windowMinutes + 1) * MINUTE_MS;
        long previousEndExclusive = currentStart;

        Map<String, EndpointAgg> aggregate = merge(okList);
        List<ApiStat> stats = new ArrayList<>();
        for (EndpointAgg endpoint : aggregate.values()) {
            String apiKey = endpoint.method + " " + endpoint.path;
            if (api != null && !api.isBlank()
                    && !endpoint.path.contains(api.trim()) && !apiKey.contains(api.trim())) {
                continue; // api 过滤：按路径 / "方法 路径" 包含匹配
            }
            WindowStat current = computeWindow(endpoint, currentStart, currentEndExclusive);
            if (current.count == 0) {
                continue; // 当前窗口无流量的接口不返回
            }
            WindowStat previous = computeWindow(endpoint, previousStart, previousEndExclusive);
            stats.add(buildStat(endpoint, current, previous, windowMinutes));
        }

        // ---------- 5) 无数据：不可重试 + 明确 hint（"没流量"本身是有效结论） ----------
        if (stats.isEmpty()) {
            throw new ToolException(ErrorCodes.NO_DATA,
                    "最近 " + win + " 内没有匹配的接口请求数据",
                    false,
                    "可放大 window（如 30m / 1h）再试一次；若仍为空，说明该服务当前窗口内确实没有流量——"
                            + "'没有流量'本身是有效结论，不要编造指标或直接指向其他层");
        }

        // ---------- 6) 按异常度排序 + Top5 裁剪 ----------
        stats.sort(Comparator.comparingDouble(ApiStat::getAnomalyScore).reversed());
        List<Map<String, Object>> apiViews = new ArrayList<>();
        for (ApiStat stat : stats.subList(0, Math.min(TOP_N, stats.size()))) {
            apiViews.add(stat.toView());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", serviceDef.getName());
        data.put("window", win);
        data.put("windowStart", currentStart);
        data.put("windowEnd", currentEndExclusive); // 半开区间 [start, end)
        data.put("instanceTotal", serviceDef.getInstances().size());
        data.put("instancesOk", okList.size());
        data.put("instances", instanceStates);
        data.put("totalApis", stats.size());
        data.put("returnedApis", apiViews.size());
        data.put("sortedBy", "anomalyScore 降序（异常度：时延分 + 错误分 + 环比恶化分）");
        data.put("units", Map.of("qps", "次/秒", "latency", "毫秒", "errorRate", "百分比(0~100)"));
        data.put("apis", apiViews);
        if (okList.size() < serviceDef.getInstances().size()) {
            data.put("note", "部分实例指标缺失（仅 " + okList.size() + "/" + serviceDef.getInstances().size()
                    + " 个实例参与汇总），指标可能偏低；实例明细见 instances 字段");
        }

        log.info("[get_api_metrics] service={}, window={}, apis={}, instances={}/{}, traceId={}",
                serviceDef.getName(), win, stats.size(), okList.size(), serviceDef.getInstances().size(), traceId);
        return data;
    }

    // ============================================================
    // 合并与统计
    // ============================================================

    /** 把多实例的分钟桶按「接口」合并（QPS/错误数天然相加，时延样本合并后统一取分位） */
    private Map<String, EndpointAgg> merge(List<InstanceMetrics> instanceMetricsList) {
        Map<String, EndpointAgg> result = new HashMap<>();
        for (InstanceMetrics instanceMetrics : instanceMetricsList) {
            for (JsonNode endpoint : instanceMetrics.metrics().path("endpoints")) {
                String method = endpoint.path("method").asText();
                String path = endpoint.path("path").asText();
                String key = method + " " + path;
                EndpointAgg agg = result.computeIfAbsent(key, k -> new EndpointAgg(method, path));
                for (JsonNode bucket : endpoint.path("buckets")) {
                    List<Long> samples = new ArrayList<>();
                    for (JsonNode sample : bucket.path("samples")) {
                        samples.add(sample.asLong());
                    }
                    agg.addBucket(bucket.path("minuteStart").asLong(),
                            bucket.path("count").asLong(),
                            bucket.path("errors").asLong(),
                            samples);
                }
            }
        }
        return result;
    }

    /** 统计一个窗口内的指标（含该窗口内"最差一分钟"的峰值信息） */
    private WindowStat computeWindow(EndpointAgg endpoint, long startMs, long endExclusiveMs) {
        WindowStat stat = new WindowStat();
        List<Long> allSamples = new ArrayList<>();
        for (MergedBucket bucket : endpoint.buckets.values()) {
            // 分钟桶按「桶起点落于窗口内」归入（窗口已按分钟边界对齐，无跨窗歧义）
            if (bucket.minuteStart < startMs || bucket.minuteStart >= endExclusiveMs) {
                continue;
            }
            stat.count += bucket.count;
            stat.errors += bucket.errors;
            allSamples.addAll(bucket.samples);

            // 峰值追踪：以"该分钟 P95 最高"作为最差一分钟（并列时取错误率更高的）
            long bucketP95 = percentile(bucket.samples, 0.95);
            double bucketErrorRate = bucket.count == 0 ? 0 : bucket.errors * 100.0 / bucket.count;
            if (bucketP95 > stat.peakP95Ms
                    || (bucketP95 == stat.peakP95Ms && bucketErrorRate > stat.peakErrorRate)) {
                stat.peakMinuteStart = bucket.minuteStart;
                stat.peakP95Ms = bucketP95;
                stat.peakCountInMinute = bucket.count;
                stat.peakErrorRate = bucketErrorRate;
            }
        }
        stat.p95Ms = Math.max(percentile(allSamples, 0.95), 0);
        stat.p99Ms = Math.max(percentile(allSamples, 0.99), 0);
        stat.errorRate = stat.count == 0 ? 0 : stat.errors * 100.0 / stat.count;
        stat.peakQps = stat.peakCountInMinute / 60.0;
        return stat;
    }

    /** 组装单接口的统计结果（含环比与异常度） */
    private ApiStat buildStat(EndpointAgg endpoint, WindowStat current, WindowStat previous, int windowMinutes) {
        ApiStat stat = new ApiStat();
        stat.method = endpoint.method;
        stat.path = endpoint.path;
        double windowSeconds = windowMinutes * 60.0;
        stat.qps = current.count / windowSeconds;
        stat.p95Ms = current.p95Ms;
        stat.p99Ms = current.p99Ms;
        stat.errorRate = current.errorRate;
        stat.peakMinuteStart = current.peakMinuteStart;
        stat.peakQps = current.peakQps;
        stat.peakP95Ms = current.peakP95Ms;
        stat.peakErrorRate = current.peakErrorRate;

        if (previous.count > 0) {
            double previousQps = previous.count / windowSeconds;
            stat.qpsPct = pctChange(stat.qps, previousQps);
            stat.p95Pct = pctChange(stat.p95Ms, previous.p95Ms);
            stat.errorRatePct = pctChange(stat.errorRate, previous.errorRate);
            stat.direction = directionOf(stat.p95Ms, previous.p95Ms);
        } else {
            stat.direction = "unknown"; // 上一窗口无数据：如实标注，不硬算环比
        }

        // 异常度 = 时延分 + 错误分 + 环比恶化分（仅用于排序）
        double latencyScore = Math.min(stat.p95Ms / 500.0, 5.0) * 10;
        double errorScore = Math.min(stat.errorRate / 5.0, 5.0) * 10;
        double changeScore = 0;
        if (previous.count > 0) {
            double p95Ratio = previous.p95Ms > 0 ? (double) stat.p95Ms / previous.p95Ms : (stat.p95Ms > 0 ? 3.0 : 1.0);
            changeScore += Math.min(Math.max(p95Ratio - 1, 0), 3.0) * 10;
            changeScore += Math.min(Math.max(stat.errorRate - previous.errorRate, 0) / 5.0, 3.0) * 10;
        }
        stat.anomalyScore = latencyScore + errorScore + changeScore;
        return stat;
    }

    /** 分位计算：升序排列后取 ceil(p*n) 位置（样本为空返回 -1） */
    private static long percentile(List<Long> samples, double p) {
        if (samples == null || samples.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** 环比变化百分比；上期为 0 时无法计算，返回 null（如实缺省，不编造） */
    private static Double pctChange(double current, double previous) {
        if (previous <= 0) {
            return null;
        }
        return round1((current - previous) / previous * 100.0);
    }

    /** 趋势方向：P95 环比 ≥ +20% 为 up，≤ -20% 为 down，其余 flat；无基线为 unknown */
    private static String directionOf(long currentP95, long previousP95) {
        if (previousP95 <= 0) {
            return "unknown";
        }
        double ratio = (double) currentP95 / previousP95;
        if (ratio >= 1.2) {
            return "up";
        }
        if (ratio <= 0.8) {
            return "down";
        }
        return "flat";
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ============================================================
    // 内部数据结构
    // ============================================================

    /** 单个实例的指标快照 */
    private record InstanceMetrics(String instanceId, JsonNode metrics) {
    }

    /** 某接口在所有实例上的合并桶集合（minuteStart -> 合并桶） */
    private static final class EndpointAgg {

        private final String method;
        private final String path;
        private final Map<Long, MergedBucket> buckets = new HashMap<>();

        private EndpointAgg(String method, String path) {
            this.method = method;
            this.path = path;
        }

        private void addBucket(long minuteStart, long count, long errors, List<Long> samples) {
            MergedBucket bucket = buckets.computeIfAbsent(minuteStart, k -> new MergedBucket(minuteStart));
            bucket.count += count;
            bucket.errors += errors;
            bucket.samples.addAll(samples);
        }
    }

    /** 合并后的分钟桶 */
    private static final class MergedBucket {

        private final long minuteStart;
        private long count;
        private long errors;
        private final List<Long> samples = new ArrayList<>();

        private MergedBucket(long minuteStart) {
            this.minuteStart = minuteStart;
        }
    }

    /** 单个窗口的统计结果 */
    private static final class WindowStat {

        private long count;
        private long errors;
        private long p95Ms;
        private long p99Ms;
        private double errorRate;
        private long peakMinuteStart = -1;
        private long peakCountInMinute;
        private long peakP95Ms = -1;
        private double peakQps;
        private double peakErrorRate;
    }

    /** 单接口最终统计（当前 vs 上一窗口） */
    private static final class ApiStat {

        private String method;
        private String path;
        private double qps;
        private long p95Ms;
        private long p99Ms;
        private double errorRate;
        private String direction = "unknown";
        private Double qpsPct;
        private Double p95Pct;
        private Double errorRatePct;
        private double anomalyScore;
        private long peakMinuteStart;
        private double peakQps;
        private long peakP95Ms;
        private double peakErrorRate;

        private double getAnomalyScore() {
            return anomalyScore;
        }

        /** 按契约输出：6 个核心字段（change 内聚环比/趋势/峰值/异常度） */
        private Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("api", method + " " + path);
            view.put("qps", round2(qps));
            view.put("p95Ms", p95Ms);
            view.put("p99Ms", p99Ms);
            view.put("errorRate", round2(errorRate));
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("direction", direction);
            change.put("qpsPct", qpsPct);
            change.put("p95Pct", p95Pct);
            change.put("errorRatePct", errorRatePct);
            change.put("anomalyScore", round2(anomalyScore));
            Map<String, Object> peak = new LinkedHashMap<>();
            peak.put("minuteStart", peakMinuteStart);
            peak.put("qps", round2(peakQps));
            peak.put("p95Ms", Math.max(peakP95Ms, 0));
            peak.put("errorRate", round2(peakErrorRate));
            change.put("peak", peak);
            view.put("change", change);
            return view;
        }
    }
}