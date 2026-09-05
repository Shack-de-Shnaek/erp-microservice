package finki.ukim.erp.orders.infrastructure.kafka

import finki.ukim.erp.orders.ProductId
import org.springframework.stereotype.Service

/**
 * The anti-corruption layer proper: the one place where inventory's vocabulary becomes this
 * service's.
 *
 * On the way in it is a [ProductDiscontinuedExternalEventDTO], a mirror of somebody else's JSON. On
 * the way out it is a [ProductId], which is a type this domain defined and whose rules it enforces.
 * Nothing downstream of this method knows a Kafka message was involved.
 *
 * A note on where this stops. The exercise's translator returns a command; this one returns a
 * domain value, because a discontinued product does not name the thing to change - it takes a query
 * to find which orders are affected, and one message can produce any number of commands. Putting
 * that lookup here would mean giving the translator a repository, and it would stop being a
 * translation. The lookup lives in [finki.ukim.erp.orders.handlers.ProductDiscontinuedEventHandler]
 * instead, and this class stays what it claims to be: types in, types out, no dependencies.
 */
@Service
class ProductDiscontinuedTranslator {

    fun toDiscontinuedProduct(event: ProductDiscontinuedExternalEventDTO): ProductId =
        ProductId(event.productId.value)
}
