package finki.ukim.erp.orders.infrastructure.correlation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.FilterChain

class CorrelationIdFilterTest {

    private val filter = CorrelationIdFilter()

    @Test
    fun `an id sent by the caller is adopted, so the trace continues rather than restarting`() {
        val request = MockHttpServletRequest().apply { addHeader(CorrelationId.HEADER, "upstream-id") }
        val response = MockHttpServletResponse()
        var seenInsideChain: String? = null

        filter.doFilter(request, response, chain { seenInsideChain = CorrelationId.current() })

        assertEquals("upstream-id", seenInsideChain)
        assertEquals("upstream-id", response.getHeader(CorrelationId.HEADER))
    }

    @Test
    fun `a request that arrives without one starts a new trace`() {
        val response = MockHttpServletResponse()
        var seenInsideChain: String? = null

        filter.doFilter(MockHttpServletRequest(), response, chain { seenInsideChain = CorrelationId.current() })

        assertTrue(!seenInsideChain.isNullOrBlank())
        assertEquals(seenInsideChain, response.getHeader(CorrelationId.HEADER))
    }

    @Test
    fun `the id does not outlive the request, so a pooled thread cannot leak it into the next one`() {
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())

        assertNull(CorrelationId.current())
    }

    @Test
    fun `two requests without an id get different ones`() {
        var first: String? = null
        var second: String? = null

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain { first = CorrelationId.current() })
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain { second = CorrelationId.current() })

        assertNotEquals(first, second)
    }

    private fun chain(body: () -> Unit): FilterChain =
        FilterChain { _: jakarta.servlet.ServletRequest, _: jakarta.servlet.ServletResponse -> body() }
}
