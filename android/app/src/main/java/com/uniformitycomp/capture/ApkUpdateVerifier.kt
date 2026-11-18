package com.uniformitycomp.capture

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.Signature
import android.util.Log
import java.io.File
import java.security.MessageDigest

object ApkUpdateVerifier {
    fun verifyPackage(context: Context, file: File, manifest: ApkUpdateManifest) {
        require(file.isFile) { "downloaded APK is missing" }
        require(file.length() == manifest.sizeBytes) { "downloaded APK length changed" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == manifest.sha256.lowercase()) {
            "downloaded APK SHA-256 changed"
        }

        val packageManager = context.packageManager
        require(manifest.packageName == context.packageName) {
            "downloaded APK is not an update for this application"
        }
        val diagnostics = mutableListOf("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            "APK size/SHA-256 verified; expected ${manifest.packageName} build ${manifest.versionCode}")
        val report: (String) -> Unit = { diagnostics.add(it) }
        try {
            val candidate = lookupSigners("downloaded APK", manifest.packageName, manifest.versionCode,
                Build.VERSION.SDK_INT, { flags ->
                    @Suppress("DEPRECATION")
                    val info = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                    info?.let { signingPackage(it, flags) }
                }, report)
            val installed = lookupSigners("installed app", context.packageName, null,
                Build.VERSION.SDK_INT, { flags ->
                    @Suppress("DEPRECATION")
                    val info = packageManager.getPackageInfo(context.packageName, flags)
                    signingPackage(info, flags)
                }, report)
            require(signersMatch(installed, candidate)) {
                "Downloaded APK signing certificates do not match the installed app. " +
                    "Legacy lookup requires identical signer sets and cannot authorize key rotation."
            }
            Log.i("OpenCaptureUpdate", diagnostics.joinToString("\n") + "\nSigning verification passed")
        } catch (error: Exception) {
            throw IllegalStateException("${error.message}\n\n${diagnostics.joinToString("\n")}\n\n" +
                "Try opening the official APK download with Android's installer. Do not uninstall the existing app.", error)
        }
    }

    /**
     * The server-provided hash is an integrity check, not the trust anchor.
     * Updates must remain on the installed package's Android signing lineage.
     * Exact signer-set equality handles normal debug/release updates; the
     * lineage branch permits Android-recognized signing-key rotation only when
     * the candidate carries the installed current signer in its history.
     */
    internal fun hasSignerContinuity(
        installedCurrent: Set<String>,
        installedHistory: Set<String>,
        candidateCurrent: Set<String>,
        candidateHistory: Set<String>
    ): Boolean {
        val installed = installedCurrent.map { it.lowercase() }.toSet()
        val installedLineage = (installedHistory + installed).map { it.lowercase() }.toSet()
        val candidate = candidateCurrent.map { it.lowercase() }.toSet()
        val candidateLineage = (candidateHistory + candidate).map { it.lowercase() }.toSet()
        if (installed.isEmpty() || candidate.isEmpty()) return false
        if (installed == candidate) return true
        return installed.all { it in candidateLineage } &&
            installedLineage.all { it in candidateLineage }
    }

    internal data class SignerData(
        val current: Set<String>,
        val history: Set<String>,
        val legacy: Boolean = false
    )

    internal data class SigningPackage(val name: String, val version: Long,
        val modern: SignerData?, val legacy: Set<String>)

    internal fun signersMatch(installed: SignerData, candidate: SignerData): Boolean {
        if (installed.legacy || candidate.legacy) {
            // Legacy signatures can describe an old rotation certificate. Never use
            // them to infer a lineage or override known modern rotation metadata.
            return installed.current.isNotEmpty() &&
                installed.current.map(String::lowercase).toSet() == candidate.current.map(String::lowercase).toSet() &&
                installed.history == installed.current && candidate.history == candidate.current
        }
        return hasSignerContinuity(installed.current, installed.history, candidate.current, candidate.history)
    }

    internal fun lookupSigners(role: String, expectedPackage: String, expectedVersion: Long?, sdk: Int,
        lookup: (Int) -> SigningPackage?, report: (String) -> Unit): SignerData {
        @Suppress("DEPRECATION")
        val legacyFlags = PackageManager.GET_SIGNATURES
        val attempts = if (sdk >= 28) listOf(PackageManager.GET_SIGNING_CERTIFICATES, legacyFlags) else listOf(legacyFlags)
        var observedVersion = expectedVersion
        for (flags in attempts) {
            val method = if (flags == legacyFlags) "GET_SIGNATURES" else "GET_SIGNING_CERTIFICATES"
            val info = try { lookup(flags) } catch (error: Exception) {
                report("$role $method: ${error.javaClass.simpleName}")
                null
            }
            if (info == null) {
                report("$role $method: no package metadata")
                continue
            }
            report("$role $method: ${info.name} build ${info.version}; " +
                "signingInfo=${info.modern != null}, current=${info.modern?.current?.size ?: 0}, legacy=${info.legacy.size}")
            require(info.name == expectedPackage) { "$role package does not match $expectedPackage" }
            require(observedVersion == null || info.version == observedVersion) { "$role version changed or does not match $observedVersion" }
            observedVersion = info.version
            val modern = info.modern?.takeIf { it.current.isNotEmpty() }
            if (modern != null) return modern
            if (flags == legacyFlags && info.legacy.isNotEmpty()) {
                report("$role: using legacy certificate compatibility lookup (exact signer-set match only)")
                return SignerData(info.legacy, info.legacy, legacy = true)
            }
        }
        throw IllegalStateException("Cannot verify signing certificates for $role: Android returned no usable signer information.")
    }

    private fun signingPackage(packageInfo: PackageInfo, flags: Int): SigningPackage {
        val modern = if (flags == PackageManager.GET_SIGNING_CERTIFICATES) modernSigners(packageInfo) else null
        @Suppress("DEPRECATION")
        val legacy = packageInfo.signatures.orEmpty().map(::signatureDigest).toSet()
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        return SigningPackage(packageInfo.packageName, version, modern, legacy)
    }

    private fun modernSigners(packageInfo: PackageInfo): SignerData? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return null
            val current = signingInfo.apkContentsSigners.orEmpty().map(::signatureDigest).toSet()
            val history = if (signingInfo.hasPastSigningCertificates()) {
                signingInfo.signingCertificateHistory.orEmpty().map(::signatureDigest).toSet()
            } else {
                current
            }
            return SignerData(current, history)
        }
        return null
    }

    private fun signatureDigest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
