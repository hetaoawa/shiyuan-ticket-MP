package top.hetao.shiyuanticketmp.webhook.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * WebHook 事件队列服务（Redis List 实现）。
 *
 * <p>采用 Queue-First 模式：
 * <ol>
 *   <li>接收端收到 WebHook 请求后立即写入 Redis 队列</li>
 *   <li>快速返回 200 给调用方</li>
 *   <li>后台 Worker 异步消费队列中的事件</li>
 * </ol>
 *
 * <p>队列 Key：{@code webhook:events:queue}
 * <p>处理中 Set Key：{@code webhook:events:processing}
 */
@Service
public class WebhookEventQueueService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventQueueService.class);

    private static final String QUEUE_KEY = "webhook:events:queue";
    private static final String PROCESSING_KEY = "webhook:events:processing";

    private final StringRedisTemplate redisTemplate;

    public WebhookEventQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将事件推入队列。
     *
     * @param eventJson 事件 JSON 字符串
     */
    public void push(String eventJson) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, eventJson);
        log.debug("[WebHook接收] 事件已入队, 队列长度={}", redisTemplate.opsForList().size(QUEUE_KEY));
    }

    /**
     * 从队列左侧阻塞弹出一个事件（Worker 消费用）。
     *
     * <p>阻塞等待最多 5 秒，超时返回 null。
     *
     * @return 事件 JSON，超时返回 null
     */
    public String popBlocking() {
        String event = redisTemplate.opsForList().leftPop(QUEUE_KEY, 5, TimeUnit.SECONDS);
        if (event != null) {
            // 标记为处理中（用于重试和监控）
            redisTemplate.opsForSet().add(PROCESSING_KEY, event);
        }
        return event;
    }

    /**
     * 确认事件处理完成，从处理中集合移除。
     *
     * @param eventJson 事件 JSON
     */
    public void ack(String eventJson) {
        redisTemplate.opsForSet().remove(PROCESSING_KEY, eventJson);
    }

    /**
     * 获取当前队列长度（监控用）。
     */
    public long queueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * 获取处理中事件数量（监控用）。
     */
    public long processingSize() {
        Long size = redisTemplate.opsForSet().size(PROCESSING_KEY);
        return size != null ? size : 0;
    }
}
