package com.demo.common.fault;

/**
 * 注入异常：故障注入类型为 exception 时由过滤器抛出。
 *
 * <p>刻意做成独立的异常类型（而不是直接抛 RuntimeException），原因：
 * <ul>
 *   <li>日志里能一眼区分「注入的故障」与「真实代码缺陷」，避免诊断时误判；</li>
 *   <li>后续日志检索按异常指纹聚合时依赖清晰的异常类型，
 *       独立类型让指纹更清晰。</li>
 * </ul>
 */
public class InjectedException extends RuntimeException {

    public InjectedException(String message) {
        super(message);
    }
}