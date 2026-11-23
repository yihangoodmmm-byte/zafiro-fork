package com.niki914.zafiro.platform.contract

/** 工件类型。[path] 可以为空：纯远程工件只保留 id 与来源。 */
enum class ArtifactKind {
    FILE,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    TERMINAL_OUTPUT,
    SCREENSHOT,
    SCREEN_RECORDING,
    BUILD_OUTPUT,
    MODEL_ASSET,
}

/**
 * 工件引用。字节不在这里，这里只描述「去哪找、多大、谁产生」。
 *
 * [sha256] 是可选的完整性证明：有就校验，没有不假装校验过。
 */
data class ArtifactRef(
    val id: String,
    val kind: ArtifactKind,
    val path: String?,
    val mimeType: String,
    val byteCount: Long,
    val sha256: String? = null,
    val producedByTaskId: String? = null,
    val createdAtMillis: Long,
)
