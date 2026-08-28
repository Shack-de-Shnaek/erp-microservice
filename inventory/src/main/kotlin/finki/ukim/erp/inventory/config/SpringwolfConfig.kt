package finki.ukim.erp.inventory.config

import finki.ukim.erp.inventory.domain.product.ProductCreatedExternalEvent
import finki.ukim.erp.inventory.domain.product.ProductDeactivatedExternalEvent
import finki.ukim.erp.inventory.domain.product.ProductReactivatedExternalEvent
import finki.ukim.erp.inventory.domain.product.ProductUpdatedExternalEvent
import finki.ukim.erp.inventory.domain.stockitem.StockAdjustedExternalEvent
import finki.ukim.erp.inventory.domain.stockitem.StockConfirmedExternalEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservationReleasedExternalEvent
import finki.ukim.erp.inventory.domain.stockitem.StockReservedExternalEvent
import io.github.springwolf.bindings.kafka.annotations.KafkaAsyncOperationBinding
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CompletableFuture

@Configuration
class SpringwolfConfig {

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "product.created",
            description = "Published when a product is created",
            payloadType = ProductCreatedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun productCreatedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "product.updated",
            description = "Published when a product is updated",
            payloadType = ProductUpdatedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun productUpdatedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "product.deactivated",
            description = "Published when a product is deactivated",
            payloadType = ProductDeactivatedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun productDeactivatedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "product.reactivated",
            description = "Published when a product is reactivated",
            payloadType = ProductReactivatedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun productReactivatedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "stock.reserved",
            description = "Published when stock is reserved for an order",
            payloadType = StockReservedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun stockReservedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "stock.confirmed",
            description = "Published when a stock reservation is confirmed",
            payloadType = StockConfirmedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun stockConfirmedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "stock.adjusted",
            description = "Published when stock is manually adjusted",
            payloadType = StockAdjustedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun stockAdjustedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    @Bean
    @AsyncPublisher(
        operation = AsyncOperation(
            channelName = "stock.released",
            description = "Published when a stock reservation is released",
            payloadType = StockReservationReleasedExternalEvent::class,
        ),
    )
    @KafkaAsyncOperationBinding
    fun stockReleasedPublisher(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
}