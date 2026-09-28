package com.demo.common.metrics;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口指标采集器（进程内、无第三方依赖）。
 *
 * <p>数据结构设计（为什么是「分钟桶 + 时延样本」）：
 * <ul>
 *   <li><b>分钟桶</b>：指标工具需要按 window（5m/30m/1h）统计 QPS/错误率，
 *       并对「上一同长窗口」做环比。按 1 分钟粒度分桶后，
 *       任意窗口都能用「取若干桶求和」实现，无需维护复杂滑动窗口结构；</li>
 *   <li><b>时延样本</b>：P95/P99 需要原始样本（或用直方图近似）。
 *       为了口径简单直观，每分钟每接口最多保留 {@value #MAX_SAMPLES_PER_MINUTE} 个样本，
 *       超出后覆盖最旧样本（近似分位，误差可接受，且内存有硬上限）；</li>
 *   <li><b>保留时长</b>：{@value #RETENTION_MINUTES} 分钟 =
 *       最大窗口 1h + 上一窗口 1h + 10 分钟余量，保证 1h 窗口的环比基线一定存在。</li>
 * </ul>
 *
 * <p>内存上限估算：接口数 × 130 分钟 × 100 样本 × 8 字节 ≈ 接口数 × 104KB。
 * 演示环境接口数为个位数，完全可控。
 */
@Component
public class MetricsCollector {

    /** 保留最近 130 分钟的分钟桶（1h 窗口 + 1h 环比基线 + 10 分钟余量） */
    public static final int RETENTION_MINUTES = 130;

    /** 每分钟每接口最多保留的时延样本数（超出后覆盖最旧样本） */
    public static final int MAX_SAMPLES_PER_MINUTE = 100;

    private static final long MINUTE_MS = 60_000L;

    /** key = "HTTP方法 路径模板"，例如 "GET /api/orders/{id}" */
    private final Map<String, EndpointSeries> seriesMap = new ConcurrentHashMap<>();

    /**
     * 记录一次接口调用。
     *
     * @param method HTTP 方法，如 GET
     * @param path   路径模板（由 Spring 的 bestMatchingPattern 提供，如 /api/orders/{id}），
     *               避免把 /api/orders/1、/api/orders/2 统计成两个接口
     * @param costMs 本次耗时（毫秒，含被注入的延迟）
     * @param error  是否错误（口径：抛出未捕获异常，或 HTTP 状态 >= 500）
     */
    public void record(String method, String path, long costMs, boolean error) {
        String key = method + " " + path;
        seriesMap.computeIfAbsent(key, k -> new EndpointSeries(method, path)).record(costMs, error);
    }

    /** 自启动以来累计请求数（所有接口） */
    public long totalRequests() {
        long total = 0L;
        for (EndpointSeries series : seriesMap.values()) {
            total += series.totalCount;
        }
        return total;
    }

    /** 自启动以来累计错误数（所有接口） */
    public long totalErrors() {
        long total = 0L;
        for (EndpointSeries series : seriesMap.values()) {
            total += series.totalErrors;
        }
        return total;
    }

    /**
     * 导出快照（供 GET /internal/metrics 返回，指标工具汇总用）。
     * 结构：endpoints[] = {method, path, totalCount, totalErrors, buckets[]}
     *       buckets[]   = {minuteStart, count, errors, samples[]}
     */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> endpoints = new ArrayList<>(seriesMap.size());
        for (EndpointSeries series : seriesMap.values()) {
            endpoints.add(series.toMap());
        }
        return endpoints;
    }

    // ============================================================
    // 内部结构
    // ============================================================

    /** 某个接口（方法 + 路径模板）的时间序列 */
    private static final class EndpointSeries {

        private final String method;
        private final String path;
        /** 分钟桶队列，按时间升序；超出保留窗口时从头部淘汰 */
        private final ArrayDeque<MinuteBucket> buckets = new ArrayDeque<>();
        private long totalCount;
        private long totalErrors;

        private EndpointSeries(String method, String path) {
            this.method = method;
            this.path = path;
        }

        /** 记录一次调用（加锁保护：桶结构非线程安全，单接口维度竞争很低，开销可忽略） */
        private synchronized void record(long costMs, boolean error) {
            long minuteStart = System.currentTimeMillis() / MINUTE_MS * MINUTE_MS;
            MinuteBucket last = buckets.peekLast();
            if (last == null || last.minuteStart != minuteStart) {
                last = new MinuteBucket(minuteStart);
                buckets.addLast(last);
                while (buckets.size() > RETENTION_MINUTES) {
                    buckets.pollFirst();
                }
            }
            last.add(costMs, error);
            totalCount++;
            if (error) {
                totalErrors++;
            }
        }

        private synchronized Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("method", method);
            map.put("path", path);
            map.put("totalCount", totalCount);
            map.put("totalErrors", totalErrors);
            List<Map<String, Object>> bucketList = new ArrayList<>(buckets.size());
            for (MinuteBucket bucket : buckets) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("minuteStart", bucket.minuteStart);
                b.put("count", bucket.count);
                b.put("errors", bucket.errors);
                b.put("samples", bucket.samplesArray());
                bucketList.add(b);
            }
            map.put("buckets", bucketList);
            return map;
        }
    }

    /** 单个分钟桶：计数 + 错误数 + 有界时延样本（环形覆盖） */
    private static final class MinuteBucket {

        private final long minuteStart;
        private long count;
        private long errors;
        private final long[] samples = new long[MAX_SAMPLES_PER_MINUTE];
        /** 已写入的样本数（上限 MAX_SAMPLES_PER_MINUTE） */
        private int sampleCount;
        /** 下一个写入位置（环形：写满后覆盖最旧样本） */
        private int nextIndex;

        private MinuteBucket(long minuteStart) {
            this.minuteStart = minuteStart;
        }

        private void add(long costMs, boolean error) {
            count++;
            if (error) {
                errors++;
            }
            samples[nextIndex] = costMs;
            nextIndex = (nextIndex + 1) % MAX_SAMPLES_PER_MINUTE;
            if (sampleCount < MAX_SAMPLES_PER_MINUTE) {
                sampleCount++;
            }
        }

        private long[] samplesArray() {
            long[] result = new long[sampleCount];
            System.arraycopy(samples, 0, result, 0, sampleCount);
            return result;
        }
    }
}