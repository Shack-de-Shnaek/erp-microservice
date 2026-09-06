package finki.ukim.erp.orders.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import finki.ukim.erp.orders.CustomerName
import finki.ukim.erp.orders.Money
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.config.TestSecurityConfig
import finki.ukim.erp.orders.exceptions.OrderNotFoundException
import finki.ukim.erp.orders.services.OrderViewReadService
import finki.ukim.erp.orders.views.OrderItemView
import finki.ukim.erp.orders.views.OrderView
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * The provider side of the inventory -> orders HTTP contract.
 *
 * Inventory reads an order to find out what was committed to a customer, and
 * `src/test/resources/pacts/http/inventory-orders.json` is the contract its own consumer test
 * produced. This class replays every interaction in that file against a real instance of this
 * service and fails if any of them no longer holds - so a rename in [OrderView] or a change to
 * `/orders/{id}` breaks this build rather than inventory's runtime.
 *
 * Nothing here describes the expected responses. That is the point of a consumer-driven contract:
 * the expectations are inventory's, they arrive in the pact file, and all this side supplies is the
 * data each `given(...)` promises.
 *
 * What is mocked and what is not: only [OrderViewReadService], the very bottom of the read side, is
 * stubbed. The HTTP layer, the JSON serialization, the query bus and the query handlers are all
 * real, because every one of them can break the contract - and a test that mocked the controller's
 * own collaborator would be verifying the pact against a fixture rather than against this service.
 *
 * The pacts live in `pacts/http` rather than `pacts` so that the message contract with the same two
 * parties can be `pacts/messages/inventory-orders.json`. A pact file is named
 * `<consumer>-<provider>.json`, and both contracts are between the same consumer and provider, so
 * without the subdirectories the second would overwrite the first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig::class)
@Provider("orders")
@PactFolder("pacts/http")
class OrdersHttpProviderPactTest {

    @LocalServerPort
    var port: Int = 0

    @MockitoBean
    lateinit var orderViewReadService: OrderViewReadService

    @BeforeEach
    fun before(context: PactVerificationContext?) {
        // Null when the pact folder yields no interactions; Pact still runs @BeforeEach once.
        context?.target = HttpTestTarget("localhost", port)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun pactVerificationTestTemplate(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * Each state string has to match the pact's `given(...)` character for character - Pact matches
     * them by string equality, and a state it cannot find is reported as a missing state rather
     * than being skipped.
     */
    @State("order Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4 is approved and has two lines")
    fun approvedOrderExists() {
        val orderId = OrderId("Order:9f1c0a4e-2f6b-4d1e-9c33-7a5b2e8d10f4")
        val order = OrderView(
            id = orderId,
            customer = CustomerName("Ana", "Petrovska"),
            customerId = "b7d1c2e3-4f5a-6789-abcd-ef0123456789",
            status = OrderStatus.APPROVED,
            date = LocalDateTime.of(2026, 9, 5, 10, 15, 30),
            items = listOf(
                OrderItemView(
                    id = 1L,
                    productId = ProductId("11111111-1111-1111-1111-111111111111"),
                    quantity = Quantity(5),
                    price = Money(BigDecimal("19.99"))
                ),
                OrderItemView(
                    id = 2L,
                    productId = ProductId("22222222-2222-2222-2222-222222222222"),
                    quantity = Quantity(2),
                    price = Money(BigDecimal("49.50"))
                )
            )
        )

        Mockito.`when`(orderViewReadService.findById(orderId)).thenReturn(order)
    }

    /**
     * The 404 half of the contract. It is in the pact because "this order does not exist" is an
     * answer inventory has to be able to act on, and a service that started returning 200 with an
     * empty body here would break it just as surely as a renamed field.
     */
    @State("no order with id Order:00000000-0000-0000-0000-000000000000 has been placed")
    fun unknownOrder() {
        val missing = OrderId("Order:00000000-0000-0000-0000-000000000000")

        Mockito.`when`(orderViewReadService.findById(missing)).thenThrow(OrderNotFoundException(missing))
    }
}
