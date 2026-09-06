package finki.ukim.erp.inventory.query.reservation

data object FindAllReservationsQuery

data class FindReservationByOrderRefQuery(
    val orderRef: String,
)
