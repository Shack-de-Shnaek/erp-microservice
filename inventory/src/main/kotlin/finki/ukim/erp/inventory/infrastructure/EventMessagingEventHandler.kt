package finki.ukim.erp.inventory.infrastructure

import finki.ukim.erp.inventory.domain.base.AbstractEvent
import org.axonframework.eventhandling.EventHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EventMessagingEventHandler(
    private val eventMessagingService: EventMessagingService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventHandler
    fun on(event: AbstractEvent) {
        val external = event.toExternalEvent() ?: return
        log.debug("Externalizing {} to topic {}", event::class.simpleName, event.eventTopic())
        eventMessagingService.publish(event.eventTopic(), event.identifier, external)
    }
}