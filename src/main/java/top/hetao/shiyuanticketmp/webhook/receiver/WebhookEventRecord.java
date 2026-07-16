package top.hetao.shiyuanticketmp.webhook.receiver;

/**
 * A webhook event claimed from the Redis Stream consumer group.
 *
 * @param recordId       immutable Redis Stream record id
 * @param eventId        stable event id supplied by the webhook sender
 * @param eventType      event type supplied by the webhook sender
 * @param timestamp      sender timestamp header
 * @param payload        original signed request body
 * @param deliveryAttempt one-based delivery attempt
 */
public record WebhookEventRecord(
        String recordId,
        String eventId,
        String eventType,
        String timestamp,
        String payload,
        long deliveryAttempt) {
}
