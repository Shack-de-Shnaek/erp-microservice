package finki.ukim.erp.orders

enum class PaymentType(val value: String) {
    CASH("CASH"),
    CARD("CARD"),
    BANK_TRANSFER("BANK_TRANSFER"),
    MOBILE_PAYMENT("MOBILE_PAYMENT")
}
