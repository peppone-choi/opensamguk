package opensamguk.gateway.publication.domain

data class ChangeServerVisibility(val serverId: String, val publiclyVisible: Boolean, val expectedRevision: Long) {
    init {
        require(serverId.matches(Regex("[a-z0-9]{1,48}")) && expectedRevision > 0)
    }
}

interface ServerVisibilityWriter {
    fun change(command: ChangeServerVisibility): ServerPublication
}
