package finki.ukim.erp.inventory.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import finki.ukim.erp.inventory.domain.base.Identifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

interface EventMessagingRepository {
    fun send(topic: String, key: String, payload: String)
}

interface EventMessagingService {
    fun publish(topic: String, identifier: Identifier<*>, payload: Any)
}

@Service
class DefaultEventMessagingService(
    private val eventMessagingRepository: EventMessagingRepository,
    private val objectMapper: ObjectMapper,
) : EventMessagingService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(topic: String, identifier: Identifier<*>, payload: Any) {
        val json = objectMapper.writeValueAsString(payload)
        log.info("Publishing event topic={} key={} payload={}", topic, identifier.value, json)
        eventMessagingRepository.send(topic, identifier.value.toString(), json)
    }
}