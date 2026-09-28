package com.demo.common.fault;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障注入管理器：保存「当前活动故障」。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>同一时刻只允许一个活动故障（用 CAS 保证），避免多个故障叠加后
 *       无法判断指标异常到底由哪个注入引起——注入验证要求「单一变量」；</li>
 *   <li>故障状态保存在 JVM 内存里（单实例生命周期内有效），
 *       进程重启即清空——这是刻意的：重启后环境必须回到"干净"状态。</li>
 * </ul>
 */
@Component
public class FaultInjectionManager {

    /** 当前活动故障（null 表示无故障） */
    private final AtomicReference<FaultSpec> activeFault = new AtomicReference<>();

    /**
     * 激活一个故障。
     *
     * @throws IllegalStateException 已有活动故障时抛出（Controller 转为 409 响应）
     */
    public FaultSpec activate(FaultSpec spec) {
        spec.compile(); // 先编译正则，非法正则会抛 IllegalArgumentException
        if (!activeFault.compareAndSet(null, spec)) {
            throw new IllegalStateException("已有活动故障，请先清除");
        }
        return spec;
    }

    /** 返回当前活动故障；无故障返回 null */
    public FaultSpec current() {
        return activeFault.get();
    }

    /**
     * 清除当前故障。
     *
     * @return true 表示确实清除了一个活动故障；false 表示本来就没有故障
     */
    public boolean clear() {
        return activeFault.getAndSet(null) != null;
    }
}