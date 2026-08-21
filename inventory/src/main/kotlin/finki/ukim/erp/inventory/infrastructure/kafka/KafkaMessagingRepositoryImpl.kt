package finki.ukim.erp.inventory.infrastructure.kafka

import finki.ukim.erp.inventory.infrastructure.EventMessagingRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class KafkaMessagingRepositoryImpl(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : EventMessagingRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(topic: String, key: String, payload: String) {
        kafkaTemplate.send(topic, key, payload).whenComplete { result, ex ->
            if (ex != null) {
                log.error("Failed to publish to topic={} key={}", topic, key, ex)
            } else if (result != null) {
                val metadata = result.recordMetadata
                log.info(
                    "Published to topic={} partition={} offset={} key={}",
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset(),
                    key,
                )
            }
        }
    }
}