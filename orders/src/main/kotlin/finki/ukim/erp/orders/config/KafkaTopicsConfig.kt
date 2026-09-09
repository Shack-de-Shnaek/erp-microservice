package finki.ukim.erp.orders.config

import finki.ukim.erp.orders.infrastructure.kafka.KafkaEventConsumer
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * Declares the topics this service consumes.
 *
 * The producing service owns the contract; this is only about the topic existing. A consumer that
 * subscribes to a topic no broker has heard of yet is not told when it appears - it finds out on
 * the next metadata refresh, five minutes later by default - so on a cold start, where orders
 * happens to come up before whoever publishes to it, the first messages sit unread for minutes.
 * Declaring it here means the topic is there the moment this service is, and the subscription takes
 * effect immediately.
 *
 * Topics this service *publishes* to are not declared: Kafka creates them on first send, and their
 * names come from the events themselves, so listing them here would be a second place to keep in
 * step with `AbstractEvent.eventTopic()`.
 */
@Configuration
class KafkaTopicsConfig {

    @Bean
    fun productDeactivatedTopic(): NewTopic = consumed(KafkaEventConsumer.PRODUCT_DEACTIVATED_TOPIC)

    @Bean
    fun stockConfirmedTopic(): NewTopic = consumed(KafkaEventConsumer.STOCK_CONFIRMED_TOPIC)

    @Bean
    fun stockReleasedTopic(): NewTopic = consumed(KafkaEventConsumer.STOCK_RELEASED_TOPIC)

    @Bean
    fun stockReturnedTopic(): NewTopic = consumed(KafkaEventConsumer.STOCK_RETURNED_TOPIC)

    private fun consumed(name: String): NewTopic =
        TopicBuilder.name(name).partitions(1).replicas(1).build()
}
