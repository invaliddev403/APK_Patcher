package dev.apkpatcher

internal data class MorpheGenerationResult(
    val pack: PatchPack,
    val applied: Int,
    val adjustedSignatures: Int,
    val missing: Int,
    val unsupported: Int,
    val retainedBasePatches: Int,
)

internal object MorphePackGenerator {
    fun generate(
        base: PatchPack,
        fingerprintsSource: String,
        patchSource: String,
        availableMethods: Set<MethodSignature>,
        targetVersion: String,
        commitSha: String,
    ): MorpheGenerationResult {
        val patches = LinkedHashMap<MethodSignature, MethodPatchSpec>()
        base.patches.filter { it.signature in availableMethods }.forEach { patches[it.signature] = it }
        val retained = patches.size
        var applied = 0
        var adjusted = 0
        var missing = 0
        var unsupported = 0
        val usedFingerprints = Regex("\\w+Fingerprint").findAll(patchSource).map { it.value }.toSet()

        parseFingerprints(fingerprintsSource).forEach { (name, signature) ->
            if (name !in usedFingerprints) return@forEach
            val replacement = operationFor(name)
            if (replacement == null) {
                unsupported++
                return@forEach
            }
            val resolved = when {
                signature in availableMethods -> signature
                else -> availableMethods.filter {
                    it.classType == signature.classType && it.name == signature.name &&
                        it.returnType == signature.returnType
                }.singleOrNull()
            }
            if (resolved == null) {
                missing++
                return@forEach
            }
            patches[resolved] = MethodPatchSpec(resolved, replacement)
            applied++
            if (resolved != signature) adjusted++
        }
        require(patches.isNotEmpty()) { "No compatible patches were found in the selected APK." }
        val id = "citizen-morphe-${commitSha.take(12).lowercase()}"
        return MorpheGenerationResult(
            PatchPack(
                base.formatVersion,
                id,
                "Citizen Morphe ${commitSha.take(12)}",
                base.application.copy(versionName = targetVersion),
                base.inputs,
                patches.values.toList(),
            ),
            applied,
            adjusted,
            missing,
            unsupported,
            retained,
        )
    }

    private fun parseFingerprints(source: String): List<Pair<String, MethodSignature>> =
        Regex("val\\s+(\\w+)\\s*=\\s*Fingerprint\\((.*?)\\n\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).map { match ->
                val name = match.groupValues[1]
                val body = match.groupValues[2]
                fun string(field: String): String {
                    val value = Regex("$field\\s*=\\s*\"((?:\\\\.|[^\"])*)\"")
                        .find(body)?.groupValues?.get(1)
                    require(value != null) { "Morphe fingerprint $name is missing $field." }
                    return value.replace("\\$", "$")
                }
                val parameters = Regex("parameters\\s*=\\s*listOf\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1).orEmpty()
                val parameterTypes = Regex("\"((?:\\\\.|[^\"])*)\"")
                    .findAll(parameters).map { it.groupValues[1].replace("\\$", "$") }.toList()
                name to MethodSignature(string("definingClass"), string("name"), parameterTypes, string("returnType"))
            }.toList()

    private fun operationFor(name: String): ReplacementSpec? = when {
        name == "SafetyNetworkEducationFlowCollectorFingerprint" -> ReplacementSpec.LaunchActivityFromField(
            "Lsp0n/citizen/social/safetynetwork/SafetyNetworkActivity;",
            "d",
            "Lsp0n/citizen/social/safetynetwork/SafetyNetworkEducationActivity;",
        )
        name in TRUE_FLAGS -> ReplacementSpec.ReturnBoolean(true)
        name in FALSE_FLAGS -> ReplacementSpec.ReturnBoolean(false)
        name in NULL_RETURNS -> ReplacementSpec.ReturnNull
        name in VOID_RETURNS -> ReplacementSpec.ReturnVoid
        name == "ClarityEntrypointVisibleFingerprint" -> ReplacementSpec.ReturnStaticField(
            "Ljava/lang/Boolean;",
            "TRUE",
            "Ljava/lang/Boolean;",
        )
        name.endsWith("OnCreateFingerprint") -> ReplacementSpec.DismissActivity
        name.endsWith("FlowCollectorFingerprint") || name.endsWith("PaywallCollectorFingerprint") ||
            name.contains("PaywallFlowCollector") || name.startsWith("NavigationType") -> UNIT
        else -> null
    }

    private val UNIT = ReplacementSpec.ReturnStaticField("Lkotlin/Unit;", "a", "Lkotlin/Unit;")

    private val TRUE_FLAGS = setOf(
        "CitizenPlusInfoGetActiveFingerprint",
        "CitizenProtectInfoGetActiveFingerprint",
        "CitizenProtectInfoDomainGetActiveFingerprint",
        "PrivateUserIsPlusActiveFingerprint",
        "PrivateUserIsProtectActiveFingerprint",
        "PrivateUserIsProtectActiveOrInSetupFingerprint",
        "PrivateUserIsPaidFingerprint",
        "PrivateUserIsProtectEligibleFingerprint",
        "PrivateUserIsProtectSubscriberFingerprint",
        "ShowPaywallUseCaseAFingerprint",
        "ShowPaywallUseCaseCFingerprint",
        "ShowPaywallUseCaseDFingerprint",
        "MonoSubscriptionGetEnabledFingerprint",
        "MonoSubscriptionIsSafetyToolAvailableFingerprint",
        "MonoSubscriptionGetHidePremiumOnboardingFingerprint",
        "MonoSubscriptionIsSafetyNetworkAvailableFingerprint",
        "ClarityMapTooltipUpsellEnabledFingerprint",
        "ClarityRadioClipsUpsellEnabledFingerprint",
        "ClaritySettingsUpsellEnabledFingerprint",
        "PlusV1NeighborhoodTrendsEnabledFingerprint",
        "PlusV1RadioClipsEnabledFingerprint",
        "ClarityProfileEntrypointEnabledFingerprint",
        "TrustedContactsConfigGetEnabledFingerprint",
    )

    private val FALSE_FLAGS = setOf(
        "AndroidSuperwallGetEnabledFingerprint",
        "ShowPaywallUseCaseEFingerprint",
        "ShowPaywallUseCaseFFingerprint",
        "SafetyCenterPaywallVMGateFingerprint",
        "SafetyNetworkPaywallVMGateFingerprint",
        "MonoSubscriptionGetShowPlusToPremiumEducationFingerprint",
        "MonoSubscriptionGetShowPlusToPremiumProfileBannerFingerprint",
        "PaywallHomescreenTriggerConfigGetEnabledFingerprint",
    )

    private val NULL_RETURNS = setOf(
        "SuperwallInitializerCreateFingerprint",
        "SafetyNetworkRemoveExpiredFingerprint",
        "PurchasePremiumHelperCreateIntentFingerprint",
        "PurchasePremiumHelperC3DFingerprint",
    )

    private val VOID_RETURNS = setOf(
        "SuperwallSetSubscriptionStatusFingerprint",
        "ProtectFabHelperPaywallFingerprint",
    )
}
