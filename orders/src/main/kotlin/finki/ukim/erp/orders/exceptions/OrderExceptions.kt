package finki.ukim.erp.orders.exceptions

class OrderNotFoundException(orderId: Long) : RuntimeException("Order with id $orderId not found")

class InvoiceNotFoundException(invoiceId: Long) : RuntimeException("Invoice with id $invoiceId not found")

class InsufficientStockException(productId: Long, quantity: Int) :
    RuntimeException("Insufficient stock for product $productId to fulfil quantity $quantity")

class ProductNotFoundException(productId: Long) :
    RuntimeException("Product with id $productId does not exist in inventory")

class InvalidOrderStateException(message: String) : RuntimeException(message)

class OrderNotOwnedException(orderId: Long) :
    RuntimeException("Order with id $orderId does not belong to the current user")

class OverpaymentException(message: String) : RuntimeException(message)

class InvoiceAlreadyExistsException(orderId: Long) :
    RuntimeException("Order with id $orderId already has an invoice")

class InvoiceAlreadyReversedException(invoiceId: Long) :
    RuntimeException("Invoice with id $invoiceId has already been reversed")
