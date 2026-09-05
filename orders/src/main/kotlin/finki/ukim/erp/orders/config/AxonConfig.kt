package finki.ukim.erp.orders.config

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.axonframework.serialization.json.JacksonSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Axon is configured to serialize commands, events and snapshots with Jackson rather than the
 * default XStream, so what lands in the event store is readable JSON.
 *
 * Note the Jackson version split: Spring Boot 4's web layer uses Jackson 3 (`tools.jackson`),
 * while Axon 4 is built against Jackson 2 (`com.fasterxml.jackson`). The mapper built below is a
 * Jackson 2 mapper used *only* by Axon, and is deliberately not exposed as a bean so it can never
 * be picked up as the application's HTTP mapper.
 */
@Configuration
class AxonConfig {

    @Bean
    @Primary
    fun axonSerializer(): JacksonSerializer =
        JacksonSerializer.builder().objectMapper(axonObjectMapper()).build()

    private fun axonObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Events are Kotlin data classes: val properties, no setters.
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            // Events carry `_eventType` from AbstractEvent, which is written for consumers rather
            // than for us and has no constructor parameter to read back into. Stored events also
            // outlive the code that wrote them, so a field added since is not a reason to fail
            // reading history.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
