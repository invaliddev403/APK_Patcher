package dev.apkpatcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MorphePackGeneratorTest {
    @Test
    fun generatesPackWithExactAdjustedMissingAndUnsupportedTargets() {
        val base = PatchPack(
            1,
            "base",
            "Base",
            PatchTarget("sp0n.citizen", "Citizen", "old"),
            emptyList(),
            listOf(MethodPatchSpec(
                MethodSignature("Lexample/Core;", "enabled", returnType = "Z"),
                ReplacementSpec.ReturnBoolean(true),
            )),
        )
        val fingerprints = """
            val CitizenPlusInfoGetActiveFingerprint = Fingerprint(
                definingClass = "Lsp0n/citizen/data/user/dto/CitizenPlusInfoDTO;",
                name = "getActive",
                returnType = "Z",
            )
            val MainActivityPaywallFlowCollectorDFingerprint = Fingerprint(
                definingClass = "Lsp0n/citizen/main/MainActivity${'$'}d${'$'}a${'$'}a;",
                name = "emit", parameters = listOf("Ljava/lang/Object;", "Lnq3;"),
                returnType = "Ljava/lang/Object;",
            )
            val TrustedContactsConfigGetEnabledFingerprint = Fingerprint(
                definingClass = "Lsp0n/citizen/data/variablesettings/FeatureConfigValue${'$'}TrustedContactsConfig;",
                name = "getEnabled", returnType = "Z",
            )
            val PremiumEducationalPaywallActivityCreateIntentFingerprint = Fingerprint(
                definingClass = "Lsp0n/citizen/paywall/superwall/PremiumEducationalPaywallActivity${'$'}a;",
                name = "a", parameters = listOf("Landroid/content/Context;", "Z", "Ljava/lang/String;"),
                returnType = "Landroid/content/Intent;",
            )
            val UnusedConstructorFingerprint = Fingerprint(
                definingClass = "Lexample/Unused;", name = "<init>", returnType = "V",
            )
        """.trimIndent()
        val used = listOf(
            "CitizenPlusInfoGetActiveFingerprint",
            "MainActivityPaywallFlowCollectorDFingerprint",
            "TrustedContactsConfigGetEnabledFingerprint",
            "PremiumEducationalPaywallActivityCreateIntentFingerprint",
        ).joinToString("\n")
        val available = setOf(
            MethodSignature("Lexample/Core;", "enabled", returnType = "Z"),
            MethodSignature("Lsp0n/citizen/data/user/dto/CitizenPlusInfoDTO;", "getActive", returnType = "Z"),
            MethodSignature(
                "Lsp0n/citizen/main/MainActivity${'$'}d${'$'}a${'$'}a;",
                "emit",
                listOf("Ljava/lang/Object;", "Lzp3;"),
                "Ljava/lang/Object;",
            ),
        )

        val result = MorphePackGenerator.generate(
            base,
            fingerprints,
            used,
            available,
            "2.0",
            "0123456789abcdef0123456789abcdef01234567",
        )

        assertEquals(2, result.applied)
        assertEquals(1, result.adjustedSignatures)
        assertEquals(1, result.missing)
        assertEquals(1, result.unsupported)
        assertEquals(1, result.retainedBasePatches)
        assertEquals(3, result.pack.patches.size)
        assertEquals("2.0", result.pack.application.versionName)
        assertEquals("citizen-morphe-0123456789ab", result.pack.id)
        assertEquals(result.pack, PatchPackParser.parse(PatchPackWriter.write(result.pack)))
    }
}
