package finki.ukim.erp.inventory.config

import finki.ukim.erp.inventory.infrastructure.kafka.KafkaEventConsumer
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * Declares the topics this service consumes.
 *
 * The producing service owns the contract; this is only about the topics existing. A consumer that
 * subscribes to a topic no broker has heard of yet is not told when it appears - it finds out on the
 * next metadata refresh, five minutes later by default - so on a cold start, where inventory comes
 * up before orders has published anything, the first messages sit unread for minutes.
 *
 * Topics this service publishes to are not declared: Kafka creates them on first send, and their
 * names are derived from the event classes, so listing them here would be a second place to keep in
 * step with `AbstractEvent.eventTopic()`.
 */
@Configuration
class KafkaTopicsConfig {

    @Bean
    fun orderApprovedTopic(): NewTopic =
        TopicBuilder.name(KafkaEventConsumer.ORDER_APPROVED_TOPIC).partitions(1).replicas(1).build()

    @Bean
    fun orderNullifiedTopic(): NewTopic =
        TopicBuilder.name(KafkaEventConsumer.ORDER_NULLIFIED_TOPIC).partitions(1).replicas(1).build()
}
