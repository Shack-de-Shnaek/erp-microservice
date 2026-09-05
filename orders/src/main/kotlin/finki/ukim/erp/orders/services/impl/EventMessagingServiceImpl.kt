package finki.ukim.erp.orders.services.impl

import finki.ukim.erp.orders.repositories.EventMessagingRepository
import finki.ukim.erp.orders.services.EventMessagingService
import org.springframework.stereotype.Service

/**
 * The service layer's side of publishing. It has nothing to add today beyond handing the message
 * on, and that is fine - it is the seam where anything that belongs to *this service's* policy
 * would go (a message the whole system should never emit, a header stamped on everything, a metric)
 * without that policy ending up inside the Kafka adapter, where it would be lost the moment the
 * transport changed.
 */
@Service
class EventMessagingServiceImpl(
    val eventMessagingRepository: EventMessagingRepository
) : EventMessagingService {

    override fun send(topic: String, key: String, payload: String) {
        eventMessagingRepository.send(topic, key, payload)
    }
}
