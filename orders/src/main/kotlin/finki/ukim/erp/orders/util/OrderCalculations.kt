package finki.ukim.erp.orders.util

import finki.ukim.erp.orders.Invoice
import finki.ukim.erp.orders.Order
import java.math.BigDecimal

fun Order.total(): BigDecimal =
    orderItems.fold(BigDecimal.ZERO) { acc, item -> acc + item.price.multiply(BigDecimal(item.quantity)) }

fun Invoice.total(): BigDecimal =
    invoiceLineItems.fold(BigDecimal.ZERO) { acc, item -> acc + item.price.multiply(BigDecimal(item.quantity)) }
