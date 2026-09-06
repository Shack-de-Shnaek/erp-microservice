package finki.ukim.erp.inventory.pact

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import finki.ukim.erp.inventory.domain.product.ProductCreatedExternalEvent
import finki.ukim.erp.inventory.domain.product.ProductDeactivatedExternalEvent
import finki.ukim.erp.inventory.domain.product.ProductUpdatedExternalEvent
import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedExternalEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservedExternalEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class, PactVerificationInvocationContextProvider::class)
@Provider("inventory")
@PactFolder("pacts/async")
class PactAsyncProviderTest {

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp(context: PactVerificationContext) {
        context.target = MessageTestTarget()
    }

    @TestTemplate
    fun pactVerificationTestTemplate(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    @PactVerifyProvider("product created event")
    fun productCreatedEvent(): String {
        val event = ProductCreatedExternalEvent(
            productId = "11111111-1111-1111-1111-111111111111",
            sku = "SKU-001",
            name = "Widget",
            unitOfMeasure = "pcs",
        )
        return objectMapper.writeValueAsString(event)
    }

    @PactVerifyProvider("product updated event")
    fun productUpdatedEvent(): String {
        val event = ProductUpdatedExternalEvent(
            productId = "11111111-1111-1111-1111-111111111111",
            sku = "SKU-002",
            name = "Gadget",
            unitOfMeasure = "box",
        )
        return objectMapper.writeValueAsString(event)
    }

    @PactVerifyProvider("product deactivated event")
    fun productDeactivatedEvent(): String {
        val event = ProductDeactivatedExternalEvent(
            productId = "11111111-1111-1111-1111-111111111111",
        )
        return objectMapper.writeValueAsString(event)
    }

    @PactVerifyProvider("stock reserved event")
    fun stockReservedEvent(): String {
        val event = StockReservedExternalEvent(
            stockItemId = "22222222-2222-2222-2222-222222222222",
            orderRef = "order-1",
            quantity = 5,
        )
        return objectMapper.writeValueAsString(event)
    }

    @PactVerifyProvider("stock confirmed event")
    fun stockConfirmedEvent(): String {
        val event = StockConfirmedExternalEvent(
            stockItemId = "22222222-2222-2222-2222-222222222222",
            orderRef = "order-1",
            quantity = 5,
        )
        return objectMapper.writeValueAsString(event)
    }
}
