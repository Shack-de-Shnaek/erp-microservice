package finki.ukim.erp.inventory.query.stockitem

data object FindAllStockItemsQuery

data class FindStockItemByProductIdQuery(
    val productId: String,
)

data object FindLowStockItemsQuery