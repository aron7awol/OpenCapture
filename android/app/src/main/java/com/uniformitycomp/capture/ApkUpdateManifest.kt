// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.capture

data class ApkUpdateManifest(
    val protocolVersion: Int,
    val enabled: Boolean,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val downloadPath: String
)
