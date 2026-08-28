package finki.ukim.erp.inventory.query.stockitem

data object FindAllStockItemsQuery

data class FindStockItemByProductIdQuery(
    val productId: String,
)

data object FindLowStockItemsQuery

data object FindStockSummaryQuery

data class FindStockItemByStockItemIdQuery(
    val stockItemId: String,
)