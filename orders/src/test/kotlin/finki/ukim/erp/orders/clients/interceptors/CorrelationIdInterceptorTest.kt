package finki.ukim.erp.orders.clients.interceptors

import feign.RequestTemplate
import finki.ukim.erp.orders.infrastructure.correlation.CorrelationId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrelationIdInterceptorTest {

    private val interceptor = CorrelationIdInterceptor()

    @AfterEach
    fun clear() = CorrelationId.clear()

    @Test
    fun `the id of the request being served is the id that goes out`() {
        CorrelationId.set("abc-123")
        val template = RequestTemplate()

        interceptor.apply(template)

        assertEquals(listOf("abc-123"), template.headers()[CorrelationId.HEADER]?.toList())
    }

    @Test
    fun `a call that started outside a request still gets an id`() {
        val template = RequestTemplate()

        interceptor.apply(template)

        val sent = template.headers()[CorrelationId.HEADER]?.single()
        assertTrue(!sent.isNullOrBlank(), "expected a generated id, got '$sent'")
    }

    @Test
    fun `an id already on the request is left alone`() {
        CorrelationId.set("from-mdc")
        val template = RequestTemplate().header(CorrelationId.HEADER, "set-by-caller")

        interceptor.apply(template)

        assertEquals(listOf("set-by-caller"), template.headers()[CorrelationId.HEADER]?.toList())
    }
}
