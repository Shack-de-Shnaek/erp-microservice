package finki.ukim.erp.inventory.config

import finki.ukim.erp.inventory.domain.product.Product
import finki.ukim.erp.inventory.domain.product.ProductId
import finki.ukim.erp.inventory.domain.stockitem.StockItem
import finki.ukim.erp.inventory.domain.stockitem.StockItemId
import org.axonframework.common.jpa.EntityManagerProvider
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.config.Configuration as AxonConfiguration
import org.axonframework.modelling.command.GenericJpaRepository
import org.axonframework.modelling.command.RepositoryProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AxonRepositoriesConfig {

    @Bean
    fun productRepository(
        configuration: AxonConfiguration,
        entityManagerProvider: EntityManagerProvider,
    ): GenericJpaRepository<Product> =
        GenericJpaRepository.builder(Product::class.java)
            .parameterResolverFactory(configuration.parameterResolverFactory())
            .handlerDefinition(configuration.handlerDefinition(Product::class.java))
            .lockFactory(NullLockFactory.INSTANCE)
            .entityManagerProvider(entityManagerProvider)
            .eventBus(configuration.eventBus())
            .repositoryProvider(repositoryProvider(configuration))
            .identifierConverter { ProductId.fromString(it) }
            .build()

    @Bean
    fun stockItemRepository(
        configuration: AxonConfiguration,
        entityManagerProvider: EntityManagerProvider,
    ): GenericJpaRepository<StockItem> =
        GenericJpaRepository.builder(StockItem::class.java)
            .parameterResolverFactory(configuration.parameterResolverFactory())
            .handlerDefinition(configuration.handlerDefinition(StockItem::class.java))
            .lockFactory(NullLockFactory.INSTANCE)
            .entityManagerProvider(entityManagerProvider)
            .eventBus(configuration.eventBus())
            .repositoryProvider(repositoryProvider(configuration))
            .identifierConverter { StockItemId.fromString(it) }
            .build()

    private fun repositoryProvider(configuration: AxonConfiguration): RepositoryProvider =
        object : RepositoryProvider {
            override fun <T : Any> repositoryFor(type: Class<T>): org.axonframework.modelling.command.Repository<T> =
                configuration.repository(type)
        }
}