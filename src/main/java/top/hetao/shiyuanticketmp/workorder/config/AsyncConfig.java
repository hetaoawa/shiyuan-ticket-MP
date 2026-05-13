package top.hetao.shiyuanticketmp.workorder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 *
 * <p>为 WebHook 投递提供独立的线程池 {@code webhookExecutor}，
 * 与业务主线程池隔离，避免 WebHook 重试占满公共线程池影响主业务响应。
 *
 * <p><b>线程池参数调优建议：</b>
 * <ul>
 *   <li>核心线程数 = CPU 核心数，WebHook 为 IO 密集型，可适当调大（2×CPU）</li>
 *   <li>队列容量根据峰值 TPS 估算：队列容量 ≈ 峰值 TPS × 最大单次投递耗时（秒）</li>
 *   <li>拒绝策略：{@code CallerRunsPolicy} 降级由调用线程执行，提供背压，防止事件丢失</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * WebHook 专用线程池，Bean 名称对应 {@code @Async("webhookExecutor")} 的 value 参数。
     */
    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：常驻线程，应对正常并发
        executor.setCorePoolSize(4);
        // 最大线程数：突发流量时临时扩容
        executor.setMaxPoolSize(16);
        // 队列容量：核心线程满后进入排队
        executor.setQueueCapacity(500);
        // 线程名前缀，便于日志和 dump 定位
        executor.setThreadNamePrefix("webhook-");
        // 线程空闲超过 60 秒自动回收（超出核心数部分）
        executor.setKeepAliveSeconds(60);
        // 拒绝策略：由调用线程（@Async 方法的调用者）直接执行，提供背压防止丢事件
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待队列中任务处理完毕，不强制中断 WebHook 投递
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
