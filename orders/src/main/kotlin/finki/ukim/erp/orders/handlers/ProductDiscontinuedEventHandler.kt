package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.commands.RejectOrderCommand
import finki.ukim.erp.orders.services.OrderViewReadService
import org.axonframework.commandhandling.gateway.CommandGateway
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What this service does when another service withdraws a product.
 *
 * Every order still *waiting* to be approved that asks for that product has become unfulfillable,
 * so those orders are rejected. It is the same outcome they would get the moment anyone tried to
 * approve them - the difference is that the customer finds out now instead of after waiting.
 *
 * The rule cannot live in either aggregate. Inventory's product has no idea orders exist; an
 * `Order` cannot notice something that happened in another service and another database. It belongs
 * to neither, so it lives between them.
 *
 * It also has no Kafka in it. [finki.ukim.erp.orders.infrastructure.kafka.KafkaEventConsumer] deals
 * with the transport and hands this a [ProductId], which means this class - the part that carries
 * the actual decision - can be read and tested without a broker anywhere in sight.
 *
 * Only PENDING orders are touched. An approved order has had stock committed against it, and a
 * rejected or cancelled one is finished; reopening either from here would be this handler
 * overruling a decision the aggregate already made.
 */
@Component
class ProductDiscontinuedEventHandler(
    private val commandGateway: CommandGateway,
    private val orderViewReadService: OrderViewReadService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(productId: ProductId) {
        // The read service, never the aggregate's repository: finding out *which* orders are
        // affected is a query, and an Axon repository loads one aggregate so a command can change
        // it - it cannot search at all.
        val affected = orderViewReadService.findByStatusAndProduct(OrderStatus.PENDING, productId)
        if (affected.isEmpty()) {
            return
        }

        logger.info("Product {} was discontinued; rejecting {} pending order(s)", productId, affected.size)
        affected.forEach { order ->
            // sendAndWait, not send: each rejection has to have actually happened before the next
            // is dispatched. With fire-and-forget the consumer would return having only queued the
            // work, and Kafka would commit the offset as though every order had been dealt with -
            // so any that failed would never be retried. Blocking means a failure stops the batch
            // and the message is redelivered.
            commandGateway.sendAndWait<Any>(RejectOrderCommand(order.id))
        }
    }
}
