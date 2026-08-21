package finki.ukim.erp.inventory.config

import jakarta.persistence.EntityManagerFactory
import org.axonframework.common.jpa.EntityManagerProvider
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.eventhandling.tokenstore.TokenStore
import org.axonframework.eventhandling.tokenstore.inmemory.InMemoryTokenStore
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.PlatformTransactionManager

@Configuration
class AxonConfig {

    @Bean
    fun eventStorageEngine(): EventStorageEngine = InMemoryEventStorageEngine()

    @Bean
    fun tokenStore(): TokenStore = InMemoryTokenStore()

    @Bean
    fun axonTransactionManager(platformTransactionManager: PlatformTransactionManager): TransactionManager =
        SpringTransactionManager(platformTransactionManager)

    @Bean
    fun entityManagerProvider(entityManagerFactory: EntityManagerFactory): EntityManagerProvider =
        EntityManagerProvider {
            EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
                ?: throw IllegalStateException("No transactional EntityManager available")
        }
}