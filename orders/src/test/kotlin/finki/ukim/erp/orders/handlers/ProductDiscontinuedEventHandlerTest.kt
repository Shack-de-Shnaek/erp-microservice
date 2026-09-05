package finki.ukim.erp.orders.handlers

import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.commands.RejectOrderCommand
import finki.ukim.erp.orders.services.OrderViewReadService
import finki.ukim.erp.orders.views.OrderItemView
import finki.ukim.erp.orders.views.OrderView
import org.axonframework.commandhandling.gateway.CommandGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.math.BigDecimal

/**
 * The cross-service reaction, tested without a broker anywhere in sight - which is the payoff of
 * the consumer holding the Kafka parts and this class holding the decision.
 */
class ProductDiscontinuedEventHandlerTest {

    private val commandGateway: CommandGateway = mock(CommandGateway::class.java)

    private fun orderFor(id: String, productId: Long): OrderView = OrderView(
        id = OrderId(id),
        customer = CustomerName("John", "Doe"),
        customerId = "customer-1",
        status = OrderStatus.PENDING,
        items = listOf(
            OrderItemView(
                id = 1L,
                productId = ProductId(productId),
                quantity = Quantity(1),
                price = Money(BigDecimal("10.00"))
            )
        )
    )

    private fun readServiceReturning(orders: List<OrderView>) = object : OrderViewReadService {
        override fun findAll() = orders
        override fun findById(orderId: OrderId) = orders.first { it.id == orderId }
        override fun findByStatus(status: OrderStatus) = orders.filter { it.status == status }
        override fun findByCustomerId(customerId: String) = orders
        override fun findByStatusAndProduct(status: OrderStatus, productId: ProductId) =
            orders.filter { order -> order.status == status && order.items.any { it.productId == productId } }
    }

    @Test
    fun `every pending order for the discontinued product is rejected`() {
        val affected = listOf(orderFor("Order:a", 7L), orderFor("Order:b", 7L))
        val handler = ProductDiscontinuedEventHandler(commandGateway, readServiceReturning(affected))

        handler.handle(ProductId(7L))

        val dispatched = ArgumentCaptor.forClass(Any::class.java)
        verify(commandGateway, times(2)).sendAndWait<Any>(dispatched.capture())
        assertEquals(
            listOf(RejectOrderCommand(OrderId("Order:a")), RejectOrderCommand(OrderId("Order:b"))),
            dispatched.allValues
        )
    }

    @Test
    fun `orders for other products are left alone`() {
        val handler = ProductDiscontinuedEventHandler(
            commandGateway,
            readServiceReturning(listOf(orderFor("Order:a", 1L)))
        )

        handler.handle(ProductId(7L))

        verifyNoInteractions(commandGateway)
    }
}
