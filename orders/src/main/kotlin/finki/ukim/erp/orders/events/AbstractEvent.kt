package finki.ukim.erp.orders.events

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import finki.ukim.erp.orders.Identifier
import finki.ukim.erp.orders.OrderId
import java.time.LocalDateTime

/**
 * The base every domain event extends.
 *
 * Its purpose is to let one publisher handle all of them. Without a common type, publishing means
 * a method per event and a handler per event - which is what this service used to have, three
 * publisher classes and a forwarder listing every event twice. With it, there is a single
 * `@EventHandler` for `AbstractEvent` that can ask any event three questions: what are you, where
 * do you belong, and is there anything about you the outside world should know.
 *
 * [toExternalEvent] answers the third, and answers "no" by default. That default is the important
 * one: an event is internal unless somebody deliberately decides otherwise, so nothing leaks onto
 * a topic by being written.
 */
abstract class AbstractEvent(
    /**
     * What this event is about - the Kafka message key, so everything concerning one order lands
     * on one partition and stays in order.
     *
     * Not serialized: the concrete event already carries the same id under its own name, and a
     * second copy would only be something for a consumer to disagree with.
     */
    @get:JsonIgnore
    @field:JsonIgnore
    open val identifier: Identifier<Any>
) {

    /**
     * Written into the JSON as `_eventType` so a consumer can tell what it received before
     * deciding how to read it - the one piece of the envelope that is part of the contract.
     */
    @JsonProperty("_eventType")
    fun eventType(): String = this.javaClass.simpleName

    /**
     * The topic this event belongs on, derived from its own name:
     * `OrderApprovedEvent` -> `order.approved`. Deriving it means a new event cannot be published
     * to a topic somebody mistyped, and the name of the topic is always the name of the thing that
     * happened.
     */
    @JsonIgnore
    fun eventTopic(): String = topicFor(this.javaClass.simpleName)

    /**
     * The public version of this event, or null to keep it to ourselves.
     *
     * Overriding this is the whole decision to publish. What comes back is a separate class rather
     * than this one, because what other services are promised has to be able to stay still while
     * the internal event changes.
     */
    @JsonIgnore
    open fun toExternalEvent(): Any? = null

    companion object {
        /**
         * `OrderApprovedEvent` -> `order.approved`. Kept here rather than inline in [eventTopic] so
         * that the AsyncAPI documentation derives topic names the same way the publisher does,
         * from one implementation - two would eventually disagree, and the documentation would be
         * the one that was wrong.
         */
        fun topicFor(eventClassName: String): String =
            eventClassName
                .removeSuffix("Event")
                .replace(Regex("([a-z])([A-Z])"), "$1.$2")
                .lowercase()
    }
}

/**
 * Everything that happens to an order, its payments and its invoice. They share this class because
 * they share an aggregate: all of them are keyed by the order they happened to.
 */
abstract class OrderEvent(
    open val orderId: OrderId,
    open val occurredAt: LocalDateTime
) : AbstractEvent(orderId)
