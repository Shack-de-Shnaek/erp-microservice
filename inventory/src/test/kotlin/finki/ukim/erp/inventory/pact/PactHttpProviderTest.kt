package finki.ukim.erp.inventory.pact

import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.product.ProductName
import finki.ukim.erp.inventory.domain.product.ProductStatus
import finki.ukim.erp.inventory.domain.product.Sku
import finki.ukim.erp.inventory.domain.product.UnitOfMeasure
import finki.ukim.erp.inventory.domain.stockitem.ProductRef
import finki.ukim.erp.inventory.readmodel.ProductView
import finki.ukim.erp.inventory.readmodel.ProductViewRepository
import finki.ukim.erp.inventory.readmodel.StockItemView
import finki.ukim.erp.inventory.readmodel.StockItemViewRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class, PactVerificationInvocationContextProvider::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Provider("inventory")
@PactFolder("pacts/http")
class PactHttpProviderTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var productViewRepository: ProductViewRepository

    @Autowired
    lateinit var stockItemViewRepository: StockItemViewRepository

    @BeforeEach
    fun setUp(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port)
    }

    @State("a product with id exists")
    fun `setup product`() {
        productViewRepository.save(
            ProductView(
                productId = "11111111-1111-1111-1111-111111111111",
                sku = "SKU-001",
                name = "Widget",
                unitOfMeasure = "pcs",
                status = ProductStatus.ACTIVE,
            ),
        )
    }

    @State("a stock item for the product exists")
    fun `setup stock item`() {
        stockItemViewRepository.save(
            StockItemView(
                stockItemId = "22222222-2222-2222-2222-222222222222",
                productId = "11111111-1111-1111-1111-111111111111",
                onHand = 100,
                reserved = 0,
                reorderThreshold = 10,
            ),
        )
    }

    @TestTemplate
    fun pactVerificationTestTemplate(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
