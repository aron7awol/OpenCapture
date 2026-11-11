package com.uniformitycomp.capture

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.Signature
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
        val packageFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, packageFlags)
            ?: throw IllegalStateException("downloaded APK is not a readable package")
        require(packageInfo.packageName == manifest.packageName) {
            "downloaded APK package ${packageInfo.packageName} does not match ${manifest.packageName}"
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        require(versionCode == manifest.versionCode) {
            "downloaded APK version $versionCode does not match manifest ${manifest.versionCode}"
        }
        require(packageInfo.packageName == context.packageName) {
            "downloaded APK is not an update for this application"
        }

        @Suppress("DEPRECATION")
        val installedPackageInfo = packageManager.getPackageInfo(context.packageName, packageFlags)
        val installedSigners = signerData(installedPackageInfo)
        val candidateSigners = signerData(packageInfo)
        require(
            hasSignerContinuity(
                installedCurrent = installedSigners.current,
                installedHistory = installedSigners.history,
                candidateCurrent = candidateSigners.current,
                candidateHistory = candidateSigners.history
            )
        ) {
            "downloaded APK signer is not continuous with the installed application"
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

    private data class SignerData(
        val current: Set<String>,
        val history: Set<String>
    )

    private fun signerData(packageInfo: PackageInfo): SignerData {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
                ?: throw IllegalStateException("APK does not expose Android signing information")
            val current = signingInfo.apkContentsSigners.orEmpty().map(::signatureDigest).toSet()
            val history = if (signingInfo.hasPastSigningCertificates()) {
                signingInfo.signingCertificateHistory.orEmpty().map(::signatureDigest).toSet()
            } else {
                current
            }
            return SignerData(current, history)
        }
        @Suppress("DEPRECATION")
        val current = packageInfo.signatures.orEmpty().map(::signatureDigest).toSet()
        return SignerData(current, current)
    }

    private fun signatureDigest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
