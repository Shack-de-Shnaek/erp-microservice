package finki.ukim.erp.orders.exceptions

import finki.ukim.erp.orders.InvoiceId
import finki.ukim.erp.orders.OrderId
import finki.ukim.erp.orders.ProductId
import finki.ukim.erp.orders.Quantity
import finki.ukim.erp.orders.TransactionId

class OrderNotFoundException(orderId: OrderId) : RuntimeException("Order with id $orderId not found")

class InvoiceNotFoundException private constructor(message: String) : RuntimeException(message) {

    constructor(invoiceId: InvoiceId) : this("Invoice with id $invoiceId not found")

    companion object {
        /** The order exists, but nothing has been invoiced against it yet. */
        fun forOrder(orderId: OrderId) = InvoiceNotFoundException("Order with id $orderId has no invoice")
    }
}

class TransactionNotFoundException(transactionId: TransactionId) :
    RuntimeException("Transaction with id $transactionId not found")

class InsufficientStockException(productId: ProductId, quantity: Quantity) :
    RuntimeException("Insufficient stock for product $productId to fulfil quantity $quantity")

class ProductNotFoundException(productId: ProductId) :
    RuntimeException("Product with id $productId does not exist in inventory")

class InvalidOrderStateException(message: String) : RuntimeException(message)

class OrderNotOwnedException(orderId: OrderId) :
    RuntimeException("Order with id $orderId does not belong to the current user")

class OverpaymentException(message: String) : RuntimeException(message)

class InvoiceAlreadyExistsException(orderId: OrderId) :
    RuntimeException("Order with id $orderId already has an invoice")

class InvoiceAlreadyReversedException(invoiceId: InvoiceId) :
    RuntimeException("Invoice with id $invoiceId has already been reversed")

/** The inventory service could not be reached or answered with an error. */
class InventoryUnavailableException(cause: Throwable) :
    RuntimeException("The inventory service is currently unavailable", cause)
