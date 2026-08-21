package finki.ukim.erp.inventory.domain.stockitem

data class StockReservedExternalEvent(
    val stockItemId: String,
    val orderRef: String,
    val quantity: Int,
)

data class StockConfirmedExternalEvent(
    val stockItemId: String,
    val orderRef: String,
    val quantity: Int,
)