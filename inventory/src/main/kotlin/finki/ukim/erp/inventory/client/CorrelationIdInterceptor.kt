package finki.ukim.erp.inventory.client

import feign.RequestInterceptor
import feign.RequestTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CorrelationIdInterceptor : RequestInterceptor {

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"
    }

    override fun apply(requestTemplate: RequestTemplate) {
        val existing = requestTemplate.headers()[CORRELATION_ID_HEADER]?.firstOrNull()
        if (existing == null || existing.isBlank()) {
            requestTemplate.header(CORRELATION_ID_HEADER, UUID.randomUUID().toString())
        }
    }
}