package finki.ukim.erp.orders.config

import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderId
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.axonframework.common.jpa.SimpleEntityManagerProvider
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.eventhandling.EventBus
import org.axonframework.messaging.annotation.ParameterResolverFactory
import org.axonframework.modelling.command.GenericJpaRepository
import org.axonframework.modelling.command.Repository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Where the order aggregate is loaded from.
 *
 * [Order] is a state-stored aggregate: its current state is the row in `orders` and the rows
 * hanging off it, not a replay of its event history. [GenericJpaRepository] is what makes that so
 * - it loads the aggregate through the `EntityManager` and lets JPA flush the changes a command
 * made at the end of the unit of work.
 *
 * The `identifierConverter` is the piece that makes typed identifiers work here. A command's
 * `@TargetAggregateIdentifier` reaches Axon as a plain string - "Order:9f1c..." - because that is
 * all a routing key can be. Without being told otherwise, Axon would hand that string to
 * `EntityManager.find` for an entity whose id is an [OrderId] and fail with
 * "entity 'Order' has id type 'OrderId' but supplied id was of type 'String'". The lambda below
 * closes that gap: it says how to rebuild the typed id from its string form.
 *
 * The aggregate points back at this bean by name, with `@Aggregate(repository = "axonOrderRepository")`.
 *
 * Concurrent commands against the same order are kept apart by JPA's own optimistic locking - the
 * `@Version` column on [Order] - rather than by Axon taking a pessimistic lock of its own before
 * loading. Two mechanisms guarding the same thing would only be one more than necessary, and the
 * database is the one that has the final say here anyway, so [NullLockFactory] stands Axon's down.
 */
@Configuration("orderRepositoriesConfiguration")
class AxonRepositoriesConfiguration(@PersistenceContext val entityManager: EntityManager) {

    @Bean("axonOrderRepository")
    fun orderGenericJpaRepository(
        eventBus: EventBus,
        parameterResolverFactory: ParameterResolverFactory
    ): Repository<Order> =
        GenericJpaRepository.builder(Order::class.java)
            .entityManagerProvider(SimpleEntityManagerProvider(entityManager))
            .parameterResolverFactory(parameterResolverFactory)
            .eventBus(eventBus)
            .identifierConverter { OrderId(it) }
            .lockFactory(NullLockFactory.INSTANCE)
            .build()
}
