package de.moritzf.opencodewebpanel.server

enum class OpenCodeWireProtocol {
    V1_18,
    V2_CLI,
    V1_18_EMBEDDED_V2,
    UNKNOWN;

    fun statusLabel(): String? =
        when (this) {
            V1_18,
            V1_18_EMBEDDED_V2 -> "1.18"
            V2_CLI -> "2.x"
            UNKNOWN -> null
        }
}

internal sealed interface OpenCodeProtocolResult<out T> {
    data class Success<T>(val value: T) : OpenCodeProtocolResult<T>

    data class Failure(
        val kind: Kind,
        val statusCode: Int? = null,
    ) : OpenCodeProtocolResult<Nothing> {
        enum class Kind {
            INVALID_IDENTIFIER,
            HTTP,
            TIMEOUT,
            IO,
            TOO_LARGE,
            INVALID_BODY,
        }
    }
}

/** Shared wire vocabulary; independent of transport, session operations and the protocol facade. */
internal object OpenCodeApiContract {
    const val HEALTH_PATH = "/api/health"
    const val GLOBAL_HEALTH_PATH = "/global/health"
    const val STATUS_PATH = "/api/status"
    /** CLI 2.0.8+ renamed [STATUS_PATH] to this. Same `{version,pid,urls}` identity JSON. */
    const val INFO_PATH = "/api/info"
    const val GLOBAL_EVENT_PATH = "/global/event"
    const val CLI_EVENT_PATH = "/api/event"
    const val DISPOSE_PATH = "/global/dispose"
    const val PERMISSION_LIST_PATH = "/permission"
    const val QUESTION_LIST_PATH = "/question"
    const val CLI_PERMISSION_LIST_PATH = "/api/permission/request"

    fun buildServerRootUrl(serverUrl: String): String = serverUrl.trimEnd('/')

    fun eventPath(protocol: OpenCodeWireProtocol): String? =
        when (protocol) {
            OpenCodeWireProtocol.V1_18,
            OpenCodeWireProtocol.V1_18_EMBEDDED_V2 -> GLOBAL_EVENT_PATH
            OpenCodeWireProtocol.V2_CLI -> CLI_EVENT_PATH
            OpenCodeWireProtocol.UNKNOWN -> null
        }

    fun usesCliHttpApi(protocol: OpenCodeWireProtocol): Boolean =
        protocol == OpenCodeWireProtocol.V2_CLI

    /** IDs are URL-safe by construction; endpoint-specific helpers validate kind. */
    fun isOpenCodeRecordId(value: String): Boolean =
        value.isNotBlank() && Regex("^[A-Za-z0-9_-]+$").matches(value)

    fun isSessionId(value: String): Boolean = value.startsWith("ses_") && isOpenCodeRecordId(value)

    fun isMessageId(value: String): Boolean = value.startsWith("msg_") && isOpenCodeRecordId(value)

    fun isPartId(value: String): Boolean = value.startsWith("prt_") && isOpenCodeRecordId(value)

    fun isPermissionId(value: String): Boolean =
        value.startsWith("per_") && isOpenCodeRecordId(value)
}
