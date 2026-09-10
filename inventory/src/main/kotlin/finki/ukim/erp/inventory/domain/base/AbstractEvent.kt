package finki.ukim.erp.inventory.domain.base

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

abstract class AbstractEvent(
    /**
     * What this event is about - the Kafka message key, so everything concerning one product or
     * one stock item lands on one partition and stays in order.
     *
     * Not serialized: the concrete event already carries the same id under its own name, and a
     * second copy would only be something for a consumer to disagree with.
     */
    @get:JsonIgnore
    @field:JsonIgnore
    val identifier: Identifier<*>,
) {

    /**
     * Written into the JSON as `_eventType` so a reader can tell what it received before deciding
     * how to read it - the one piece of the envelope that is part of the contract.
     *
     * It is declared on the internal event rather than on the external one because it is derived
     * from the event's own class name, which is what actually happened; an external event is a
     * projection of that and has no name of its own to report.
     */
    @JsonProperty("_eventType")
    fun eventType(): String = this::class.simpleName
        ?: throw IllegalStateException("Event class without a name cannot report a type")

    /**
     * The topic this event belongs on, derived from its own name: `ProductCreatedEvent` ->
     * `product.created`. Deriving it means a new event cannot be published to a topic somebody
     * mistyped, and the name of the topic is always the name of the thing that happened.
     */
    @JsonIgnore
    fun eventTopic(): String {
        val simpleName = this::class.simpleName
            ?: throw IllegalStateException("Event class without a name cannot derive a topic")
        return simpleName
            .removeSuffix("Event")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1.$2")
            .lowercase()
    }

    /**
     * The public version of this event, or null to keep it to ourselves.
     *
     * Overriding this is the whole decision to publish. What comes back is a separate class rather
     * than this one, because what other services are promised has to be able to stay still while
     * the internal event changes.
     */
    @JsonIgnore
    open fun toExternalEvent(): Any? = null
}
