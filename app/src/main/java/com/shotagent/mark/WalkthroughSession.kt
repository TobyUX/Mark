package com.shotagent.mark

import java.io.File

data class WalkthroughSession(
    val videoFile: File?,
    val transcript: String,
    val stillFiles: List<File>,
) {
    val stillBytes: List<ByteArray>
        get() = stillFiles.mapNotNull { file ->
            runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    val videoBytes: ByteArray?
        get() = videoFile?.let { runCatching { it.readBytes() }.getOrNull() }?.takeIf { it.isNotEmpty() }
}
