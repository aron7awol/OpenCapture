package com.uniformitycomp.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApkUpdateVerifierTest {
    private val modernFlag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
    @Suppress("DEPRECATION")
    private val legacyFlag = android.content.pm.PackageManager.GET_SIGNATURES
    private val signer = ApkUpdateVerifier.SignerData(setOf("aa"), setOf("aa"))
    private fun record(modern: ApkUpdateVerifier.SignerData? = signer, legacy: Set<String> = emptySet(),
        name: String = "example.app", version: Long = 17) = ApkUpdateVerifier.SigningPackage(name, version, modern, legacy)

    @Test
    fun modernLookupDoesNotDowngradeToLegacy() {
        val flags = mutableListOf<Int>()
        val actual = ApkUpdateVerifier.lookupSigners("downloaded APK", "example.app", 17, 29,
            { flags.add(it); record() }, {})
        assertEquals(signer, actual)
        assertEquals(listOf(modernFlag), flags)
    }

    @Test
    fun modernSignerMismatchCannotBeOverriddenByMatchingLegacyData() {
        val flags = mutableListOf<Int>()
        val actual = ApkUpdateVerifier.lookupSigners("downloaded APK", "example.app", 17, 29,
            { flags.add(it); record(ApkUpdateVerifier.SignerData(setOf("bb"), setOf("bb")), setOf("aa")) }, {})
        assertFalse(ApkUpdateVerifier.signersMatch(signer, actual))
        assertEquals(listOf(modernFlag), flags)
    }

    @Test
    fun missingOrEmptyModernMetadataUsesExplicitLegacyLookup() {
        for (modern in listOf(null, ApkUpdateVerifier.SignerData(emptySet(), emptySet()))) {
            val flags = mutableListOf<Int>()
            val log = mutableListOf<String>()
            val actual = ApkUpdateVerifier.lookupSigners("downloaded APK", "example.app", 17, 29,
                { flag -> flags.add(flag); record(modern, if (flag == legacyFlag) setOf("aa") else emptySet()) }, log::add)
            assertEquals(listOf(modernFlag, legacyFlag), flags)
            assertTrue(actual.legacy)
            assertTrue(ApkUpdateVerifier.signersMatch(signer, actual))
            assertTrue(log.any { it.contains("downloaded APK") && it.contains("legacy certificate") })
        }
    }

    @Test
    fun unavailableModernPackageOrApiCanUseLegacyButStillChecksIdentity() {
        for (throws in listOf(false, true)) {
            val actual = ApkUpdateVerifier.lookupSigners("installed app", "example.app", null, 29,
                { if (it == legacyFlag) record(null, setOf("aa")) else {
                    if (throws) throw UnsupportedOperationException("vendor failure") else null
                } }, {})
            assertTrue(actual.legacy)
        }
    }

    @Test
    fun legacyOnlyPlatformDoesNotRequestModernFlags() {
        val flags = mutableListOf<Int>()
        ApkUpdateVerifier.lookupSigners("installed app", "example.app", null, 27,
            { flags.add(it); record(null, setOf("aa")) }, {})
        assertEquals(listOf(legacyFlag), flags)
    }

    @Test
    fun absentCertificatesIdentifyWhichPackageFailed() {
        for (role in listOf("installed app", "downloaded APK")) {
            val log = mutableListOf<String>()
            val error = assertThrows(IllegalStateException::class.java) {
                ApkUpdateVerifier.lookupSigners(role, "example.app", 17, 29, { record(null) }, log::add)
            }
            assertTrue(error.message!!.contains(role))
            assertTrue(log.any { it.contains("GET_SIGNING_CERTIFICATES") && it.contains("signingInfo=false") })
            assertTrue(log.any { it.contains("GET_SIGNATURES") && it.contains("legacy=0") })
        }
    }

    @Test
    fun wrongPackageOrVersionNeverFallsBack() {
        for (wrong in listOf(record(null, name = "other.app"), record(null, version = 18))) {
            val flags = mutableListOf<Int>()
            assertThrows(IllegalArgumentException::class.java) {
                ApkUpdateVerifier.lookupSigners("downloaded APK", "example.app", 17, 29,
                    { flags.add(it); wrong }, {})
            }
            assertEquals(listOf(modernFlag), flags)
        }
    }

    @Test
    fun fallbackCannotChangePackageOrVersion() {
        for (wrong in listOf(record(null, setOf("aa"), name = "other.app"), record(null, setOf("aa"), version = 18))) {
            assertThrows(IllegalArgumentException::class.java) {
                ApkUpdateVerifier.lookupSigners("installed app", "example.app", null, 29,
                    { if (it == modernFlag) record(null) else wrong }, {})
            }
        }
    }

    @Test
    fun legacyRequiresExactNonemptySetsAndCannotUseRotationHistory() {
        val legacy = signer.copy(legacy = true)
        assertTrue(ApkUpdateVerifier.signersMatch(legacy, legacy))
        assertTrue(ApkUpdateVerifier.signersMatch(legacy, signer))
        assertFalse(ApkUpdateVerifier.signersMatch(legacy, ApkUpdateVerifier.SignerData(setOf("bb"), setOf("aa", "bb"))))
        assertFalse(ApkUpdateVerifier.signersMatch(legacy, signer.copy(history = setOf("old", "aa"))))
        assertFalse(ApkUpdateVerifier.signersMatch(signer.copy(current = emptySet(), history = emptySet()), legacy))
        assertFalse(ApkUpdateVerifier.signersMatch(legacy, legacy.copy(current = setOf("aa", "bb"))))
        assertFalse(ApkUpdateVerifier.signersMatch(legacy.copy(current = setOf("bb"), history = setOf("bb")), legacy))
    }

    @Test
    fun exactSignerSetIsAccepted() {
        assertTrue(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = setOf("aa"),
                installedHistory = setOf("aa"),
                candidateCurrent = setOf("AA"),
                candidateHistory = setOf("aa")
            )
        )
    }

    @Test
    fun androidSigningLineageRotationIsAccepted() {
        assertTrue(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = setOf("old"),
                installedHistory = setOf("old"),
                candidateCurrent = setOf("new"),
                candidateHistory = setOf("old", "new")
            )
        )
    }

    @Test
    fun unrelatedOrIncompleteSignerDataIsRejected() {
        assertFalse(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = setOf("installed"),
                installedHistory = setOf("installed"),
                candidateCurrent = setOf("attacker"),
                candidateHistory = setOf("attacker")
            )
        )
        assertFalse(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = emptySet(),
                installedHistory = emptySet(),
                candidateCurrent = setOf("candidate"),
                candidateHistory = setOf("candidate")
            )
        )
    }
}
