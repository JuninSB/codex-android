package local.codex.mobile.core

internal data class LocalArtifact(
    val name: String,
    val image: Boolean,
    val truncated: Boolean,
    val bytes: ByteArray,
    val directory: String?,
    val size: Long
)
