package finki.ukim.erp.inventory.web

fun <T : Any> fetchWithRetry(
    attempts: Int = 50,
    delayMillis: Long = 100,
    changedFrom: T? = null,
    fetch: () -> T?,
): T? {
    var latest: T? = null
    repeat(attempts) {
        latest = fetch()
        if (latest != null && latest != changedFrom) return latest
        try {
            Thread.sleep(delayMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return latest
        }
    }
    return latest
}