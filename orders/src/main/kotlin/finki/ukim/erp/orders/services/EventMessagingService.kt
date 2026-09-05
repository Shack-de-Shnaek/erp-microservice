package finki.ukim.erp.orders.services

/**
 * Publishing an event to the outside world, said in terms of nothing in particular: a topic, a
 * key, and a body of text.
 *
 * There is no Kafka in this signature, and that is the point. Everything above this line - the
 * events, the aggregate, the handler that publishes them - can be read, tested and changed without
 * knowing what carries the message. Only one class in the service knows that, and it sits two
 * steps below here.
 */
interface EventMessagingService {
    fun send(topic: String, key: String, payload: String)
}
