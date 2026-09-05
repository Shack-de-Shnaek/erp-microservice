package finki.ukim.erp.orders.repositories.impl

import finki.ukim.erp.orders.repositories.EventMessagingRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Repository

/**
 * The adapter, and the only class in this service that mentions Kafka.
 *
 * The send is asynchronous and its result is logged rather than waited on: a broker that is slow or
 * briefly unreachable must not hold up the command that produced the event, or an outage over
 * there would become an outage over here. The trade is that a failure is a log line rather than an
 * exception the caller sees - acceptable while the event store still holds the event, which is the
 * record that something happened whether or not the announcement got out.
 */
@Repository
class KafkaMessagingRepositoryImpl(
    private val kafkaTemplate: KafkaTemplate<String, String>
) : EventMessagingRepository {

    private val logger = LoggerFactory.getLogger(KafkaMessagingRepositoryImpl::class.java)

    override fun send(topic: String, key: String, payload: String) {
        kafkaTemplate.send(topic, key, payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    logger.error("Failed to publish event [{}] to Kafka: {}", key, ex.message, ex)
                } else {
                    logger.info(
                        "Published event [{}] to topic {} partition {} offset {}",
                        key,
                        result.recordMetadata.topic(),
                        result.recordMetadata.partition(),
                        result.recordMetadata.offset()
                    )
                }
            }
    }
}
