package com.feelime.ime

/** One immutable model choice for an entire recording. A downloaded optional
 * role becoming available, backend switch, or manifest revision invalidates
 * the cached recognizer chain before the next recording starts. */
internal data class AsrModelSnapshot(
    val backend: ModelBackend,
    val roles: List<Role>,
) {
    data class Role(
        val name: String,
        val source: ModelSource?,
        val modelId: String?,
        val version: String?,
        val files: List<ModelFileSpec>,
    )

    fun sourceFor(role: String): ModelSource? = roles.firstOrNull { it.name == role }?.source

    companion object {
        val ROLES = listOf("asr-streaming", "asr-final", "punctuation")

        fun capture(
            backend: ModelBackend,
            manifest: ModelManifest,
            sourceFor: (String) -> ModelSource?,
        ): AsrModelSnapshot = AsrModelSnapshot(
            backend,
            ROLES.map { name ->
                val spec = manifest.byRole(name)
                Role(name, sourceFor(name), spec?.id, spec?.version, spec?.files?.toList().orEmpty())
            },
        )
    }
}
