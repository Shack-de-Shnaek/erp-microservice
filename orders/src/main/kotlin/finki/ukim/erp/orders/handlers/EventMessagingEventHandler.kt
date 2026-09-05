package finki.ukim.erp.orders.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import finki.ukim.erp.orders.events.AbstractEvent
import finki.ukim.erp.orders.services.EventMessagingService
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * The one handler that publishes everything.
 *
 * It listens for [AbstractEvent], not for any particular event, which is what makes it able to
 * stay still: adding an event to this service means writing the event, and if it should be public,
 * overriding `toExternalEvent` on it. Nothing here changes, and nothing has to be remembered - the
 * old arrangement, a publisher method plus a forwarder method per event, had two places to forget.
 *
 * Events with nothing public to say return null and are dropped here, so an event is internal by
 * default and public only by decision.
 *
 * This runs on a tracking processor (see application.yaml): the event store is the record of what
 * happened, so a broker outage should delay delivery and catch up afterwards, not fail the command
 * that produced the event.
 */
@Component
@ProcessingGroup(EventMessagingEventHandler.PROCESSING_GROUP)
@ConditionalOnProperty(
    prefix = "orders.kafka",
    name = ["publishing-enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class EventMessagingEventHandler(
    val eventMessagingService: EventMessagingService
) {

    companion object {
        const val PROCESSING_GROUP = "event-messaging"
    }

    /**
     * Its own mapper, not the application's: this JSON is a contract with other services, and it
     * should not change shape because somebody adjusted how the REST API renders dates.
     */
    private val objectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @EventHandler
    fun on(event: AbstractEvent) {
        val externalEvent = event.toExternalEvent() ?: return

        eventMessagingService.send(
            topic = event.eventTopic(),
            key = event.identifier.value.toString(),
            payload = objectMapper.writeValueAsString(externalEvent)
        )
    }
}
