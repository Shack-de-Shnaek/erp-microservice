package finki.ukim.erp.orders.seed

import finki.ukim.erp.orders.repositories.OrderViewJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * The orders half of the seed. It currently writes nothing, and that is the intended starting
 * state: the system is meant to come up with a stocked catalogue and no orders, no invoices and
 * no reservations against it, so that the first order placed is a real one and every reservation
 * in inventory can be traced back to it.
 *
 * It exists anyway because this service owns no products - it reads them from inventory over
 * Feign - so the only thing that could ever be seeded here is orders themselves, and when that
 * day comes this is where they go. [seedOrders] is the place to write them, and the guard above
 * it is the contract: seed only an empty database, so a boot against real data is a no-op.
 */
@Component
class OrdersDataSeeder(
    private val orderViewRepository: OrderViewJpaRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val existing = orderViewRepository.count()
        if (existing > 0) {
            log.info("Orders database already holds {} order(s); skipping seed", existing)
            return
        }
        seedOrders()
    }

    /**
     * Deliberately empty - see the note on the class. Anything added here should go through the
     * command gateway rather than the repositories, so the aggregate's rules are enforced and the
     * events other services listen for are actually published.
     */
    private fun seedOrders() {
        log.info("Orders database is empty; nothing to seed (the system starts with no orders)")
    }
}
