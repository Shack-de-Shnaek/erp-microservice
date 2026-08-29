package finki.ukim.erp.orders.events

import java.time.LocalDateTime

interface DomainEvent {
    val occurredAt: LocalDateTime
}
