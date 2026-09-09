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

data class StockAdjustedExternalEvent(
    val stockItemId: String,
    val adjustment: Int,
    val reason: String,
)

data class StockReservationAmendedExternalEvent(
    val stockItemId: String,
    val orderRef: String,
    val previousQuantity: Int,
    val quantity: Int,
)

data class StockReservationReleasedExternalEvent(
    val stockItemId: String,
    val orderRef: String,
    val quantity: Int,
    /** A [ReleaseReason] by name - whether the order asked for this, or this service decided it. */
    val reason: String,
)

data class StockReturnedExternalEvent(
    val stockItemId: String,
    val orderRef: String,
    val quantity: Int,
    val reason: String,
)