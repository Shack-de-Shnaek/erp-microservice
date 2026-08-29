package finki.ukim.erp.orders.services

import finki.ukim.erp.orders.Invoice
import finki.ukim.erp.orders.InvoiceLineItem
import finki.ukim.erp.orders.Order
import finki.ukim.erp.orders.OrderItem
import finki.ukim.erp.orders.OrderStatus
import finki.ukim.erp.orders.clients.InventoryClient
import finki.ukim.erp.orders.dto.InvoiceLineItemRequest
import finki.ukim.erp.orders.events.InvoiceEventPublisher
import finki.ukim.erp.orders.events.InvoiceGeneratedEvent
import finki.ukim.erp.orders.events.InvoiceLineItemsUpdatedEvent
import finki.ukim.erp.orders.events.InvoiceReversedEvent
import finki.ukim.erp.orders.exceptions.InsufficientStockException
import finki.ukim.erp.orders.exceptions.InvalidOrderStateException
import finki.ukim.erp.orders.exceptions.InvoiceAlreadyExistsException
import finki.ukim.erp.orders.exceptions.InvoiceAlreadyReversedException
import finki.ukim.erp.orders.exceptions.OrderNotFoundException
import finki.ukim.erp.orders.repositories.InvoiceRepository
import finki.ukim.erp.orders.repositories.OrderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class InvoiceServiceTest {

    @Mock
    lateinit var invoiceRepository: InvoiceRepository

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var transactionService: TransactionService

    @Mock
    lateinit var inventoryClient: InventoryClient

    @Mock
    lateinit var invoiceEventPublisher: InvoiceEventPublisher

    lateinit var invoiceService: InvoiceService

    @BeforeEach
    fun setUp() {
        invoiceService =
            InvoiceService(invoiceRepository, orderRepository, transactionService, inventoryClient, invoiceEventPublisher)
        given(invoiceRepository.save(any(Invoice::class.java))).thenAnswer {
            val invoice = it.arguments[0] as Invoice
            if (invoice.id == null) invoice.id = 5L
            invoice
        }
    }

    private fun approvedOrder(): Order {
        val order = Order(id = 1L, name = "John", surname = "Doe", customerId = "c1", status = OrderStatus.APPROVED)
        order.addOrderItem(OrderItem(productId = 1L, quantity = 2, price = BigDecimal("25.00")))
        return order
    }

    @Test
    fun `generateInvoice throws when order is not found`() {
        given(orderRepository.findById(anyLong())).willReturn(Optional.empty())

        assertThrows(OrderNotFoundException::class.java) { invoiceService.generateInvoice(1L, "1234567890123") }
    }

    @Test
    fun `generateInvoice throws when order is not approved`() {
        val order = approvedOrder()
        order.status = OrderStatus.PENDING
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvalidOrderStateException::class.java) { invoiceService.generateInvoice(1L, "1234567890123") }
    }

    @Test
    fun `generateInvoice throws when an invoice already exists`() {
        val order = approvedOrder()
        order.invoice = Invoice(id = 5L, order = order)
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))

        assertThrows(InvoiceAlreadyExistsException::class.java) { invoiceService.generateInvoice(1L, "1234567890123") }
    }

    @Test
    fun `generateInvoice throws when order is not yet fully paid`() {
        val order = approvedOrder()
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.isStockAvailable(1L, 2)).willReturn(true)
        given(transactionService.getTotalPaid(order)).willReturn(BigDecimal("30.00"))

        assertThrows(InvalidOrderStateException::class.java) { invoiceService.generateInvoice(1L, "1234567890123") }
    }

    @Test
    fun `generateInvoice throws when stock is no longer available`() {
        val order = approvedOrder()
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.isStockAvailable(1L, 2)).willReturn(false)

        assertThrows(InsufficientStockException::class.java) { invoiceService.generateInvoice(1L, "1234567890123") }
    }

    @Test
    fun `generateInvoice copies order item prices into invoice line items without altering the order`() {
        val order = approvedOrder()
        given(orderRepository.findById(1L)).willReturn(Optional.of(order))
        given(inventoryClient.isStockAvailable(1L, 2)).willReturn(true)
        given(transactionService.getTotalPaid(order)).willReturn(BigDecimal("50.00"))

        val invoice = invoiceService.generateInvoice(1L, "1234567890123")

        assertEquals(1, invoice.invoiceLineItems.size)
        assertEquals(1L, invoice.invoiceLineItems[0].inventoryItemId)
        assertEquals(2, invoice.invoiceLineItems[0].quantity)
        assertEquals(BigDecimal("25.00"), invoice.invoiceLineItems[0].price)
        assertEquals(1, order.orderItems.size)
        assertEquals(BigDecimal("25.00"), order.orderItems[0].price)
        verify(invoiceEventPublisher).publish(any(InvoiceGeneratedEvent::class.java))
    }

    @Test
    fun `updateInvoiceLineItems replaces line items and never touches the order`() {
        val order = approvedOrder()
        val invoice = Invoice(id = 5L, order = order)
        invoice.addInvoiceLineItem(InvoiceLineItem(inventoryItemId = 1L, quantity = 2, price = BigDecimal("25.00")))
        given(invoiceRepository.findById(5L)).willReturn(Optional.of(invoice))

        val updated = invoiceService.updateInvoiceLineItems(
            5L,
            listOf(InvoiceLineItemRequest(inventoryItemId = 2L, quantity = 1, price = BigDecimal("99.00")))
        )

        assertEquals(1, updated.invoiceLineItems.size)
        assertEquals(2L, updated.invoiceLineItems[0].inventoryItemId)
        assertEquals(1, order.orderItems.size)
        assertEquals(1L, order.orderItems[0].productId)
        assertEquals(2, order.orderItems[0].quantity)
        verify(invoiceEventPublisher).publish(any(InvoiceLineItemsUpdatedEvent::class.java))
    }

    @Test
    fun `updateInvoiceLineItems throws when invoice was already reversed`() {
        val order = approvedOrder()
        val invoice = Invoice(id = 5L, order = order, isRefunded = true)
        given(invoiceRepository.findById(5L)).willReturn(Optional.of(invoice))

        assertThrows(InvalidOrderStateException::class.java) {
            invoiceService.updateInvoiceLineItems(
                5L,
                listOf(InvoiceLineItemRequest(inventoryItemId = 2L, quantity = 1, price = BigDecimal("99.00")))
            )
        }
    }

    @Test
    fun `reverseInvoice creates a negative payment equal to the invoice total and marks it refunded`() {
        val order = approvedOrder()
        val invoice = Invoice(id = 5L, order = order)
        invoice.addInvoiceLineItem(InvoiceLineItem(inventoryItemId = 1L, quantity = 2, price = BigDecimal("25.00")))
        given(invoiceRepository.findById(5L)).willReturn(Optional.of(invoice))

        val reversed = invoiceService.reverseInvoice(5L)

        assertEquals(true, reversed.isRefunded)
        verify(transactionService).reverse(order, BigDecimal("50.00"))
        verify(invoiceEventPublisher).publish(any(InvoiceReversedEvent::class.java))
    }

    @Test
    fun `reverseInvoice throws when invoice was already reversed`() {
        val order = approvedOrder()
        val invoice = Invoice(id = 5L, order = order, isRefunded = true)
        given(invoiceRepository.findById(5L)).willReturn(Optional.of(invoice))

        assertThrows(InvoiceAlreadyReversedException::class.java) { invoiceService.reverseInvoice(5L) }
    }
}
