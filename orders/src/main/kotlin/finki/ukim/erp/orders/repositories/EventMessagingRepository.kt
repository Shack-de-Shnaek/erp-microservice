package finki.ukim.erp.orders.repositories

/**
 * The port out of the application. An implementation of this is the only thing in the service that
 * is allowed to know how a message actually travels.
 */
interface EventMessagingRepository {
    fun send(topic: String, key: String, payload: String)
}
