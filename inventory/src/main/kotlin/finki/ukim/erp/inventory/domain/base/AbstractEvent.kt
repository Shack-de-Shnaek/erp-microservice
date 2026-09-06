package finki.ukim.erp.inventory.domain.base

abstract class AbstractEvent(
    val identifier: Identifier<*>,
) {
    fun eventTopic(): String {
        val simpleName = this::class.simpleName
            ?: throw IllegalStateException("Event class without a name cannot derive a topic")
        return simpleName
            .removeSuffix("Event")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1.$2")
            .lowercase()
    }

    open fun toExternalEvent(): Any? = null
}