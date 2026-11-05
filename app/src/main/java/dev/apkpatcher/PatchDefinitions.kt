package dev.apkpatcher

import android.content.Context

internal data class MethodSignature(
    val classType: String,
    val name: String,
    val parameters: List<String> = emptyList(),
    val returnType: String,
) {
    val displayName: String get() = "$classType->$name"
}

internal sealed interface MethodReplacement {
    data class ReturnBoolean(val value: Boolean) : MethodReplacement
    data class ReturnString(val value: String) : MethodReplacement
    data object ReturnVoid : MethodReplacement
    data object ReturnNull : MethodReplacement
    data class ReturnStaticField(
        val classType: String,
        val name: String,
        val type: String,
    ) : MethodReplacement
    data object DismissActivity : MethodReplacement
    data class LaunchActivityFromField(
        val targetClass: String,
        val fieldName: String,
        val fieldType: String,
    ) : MethodReplacement
}

internal data class MethodPatch(
    val signature: MethodSignature,
    val replacement: MethodReplacement,
)

internal data class PatchProfile(
    val id: String,
    val patches: List<MethodPatch>,
) {
    init {
        require(patches.map { it.signature }.distinct().size == patches.size) {
            "Patch profile $id contains duplicate method signatures."
        }
    }
}

internal enum class PatchInputType {
    SECRET_STRING,
}

internal data class PatchInput(
    val id: String,
    val type: PatchInputType,
    val required: Boolean,
)

internal sealed interface ReplacementSpec {
    data class ReturnBoolean(val value: Boolean) : ReplacementSpec
    data class ReturnString(val value: String) : ReplacementSpec
    data class ReturnInput(val inputId: String) : ReplacementSpec
    data object ReturnVoid : ReplacementSpec
    data object ReturnNull : ReplacementSpec
    data class ReturnStaticField(
        val classType: String,
        val name: String,
        val type: String,
    ) : ReplacementSpec
    data object DismissActivity : ReplacementSpec
    data class LaunchActivityFromField(
        val targetClass: String,
        val fieldName: String,
        val fieldType: String,
    ) : ReplacementSpec
}

internal data class MethodPatchSpec(
    val signature: MethodSignature,
    val replacement: ReplacementSpec,
    val requiresInputs: Set<String> = emptySet(),
)

internal data class PatchTarget(
    val packageName: String,
    val displayName: String,
    val versionName: String? = null,
) {
    fun matches(packageName: String, versionName: String): Boolean =
        this.packageName == packageName && (this.versionName == null || this.versionName == versionName)
}

internal data class PatchPack(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val application: PatchTarget,
    val inputs: List<PatchInput>,
    val patches: List<MethodPatchSpec>,
) {
    init {
        require(patches.map { it.signature }.distinct().size == patches.size) {
            "Patch pack $id contains duplicate method signatures."
        }
    }

    fun resolve(values: Map<String, String>): PatchProfile {
        val supplied = values.filterValues { it.isNotEmpty() }
        require(values.keys.all { key -> inputs.any { it.id == key } }) {
            "Patch pack $id received an unknown input."
        }
        inputs.filter { it.required }.forEach { input ->
            require(!supplied[input.id].isNullOrEmpty()) { "Input ${input.id} is required by $id." }
        }
        val resolved = patches.filter { patch ->
            patch.requiresInputs.all { !supplied[it].isNullOrEmpty() }
        }.map { patch ->
            val replacement = when (val spec = patch.replacement) {
                is ReplacementSpec.ReturnBoolean -> MethodReplacement.ReturnBoolean(spec.value)
                is ReplacementSpec.ReturnString -> MethodReplacement.ReturnString(spec.value)
                is ReplacementSpec.ReturnInput -> MethodReplacement.ReturnString(
                    supplied.getValue(spec.inputId),
                )
                ReplacementSpec.ReturnVoid -> MethodReplacement.ReturnVoid
                ReplacementSpec.ReturnNull -> MethodReplacement.ReturnNull
                is ReplacementSpec.ReturnStaticField -> MethodReplacement.ReturnStaticField(
                    spec.classType,
                    spec.name,
                    spec.type,
                )
                ReplacementSpec.DismissActivity -> MethodReplacement.DismissActivity
                is ReplacementSpec.LaunchActivityFromField -> MethodReplacement.LaunchActivityFromField(
                    spec.targetClass,
                    spec.fieldName,
                    spec.fieldType,
                )
            }
            MethodPatch(patch.signature, replacement)
        }
        return PatchProfile(id, resolved)
    }
}

internal object BuiltInPatchPacks {
    const val CITIZEN_PACKAGE_NAME = "sp0n.citizen"
    const val CITIZEN_PROFILE_ID = "citizen-extended-v2"

    fun all(context: Context): List<PatchPack> = listOf(
        PatchPackParser.parse(
            context.resources.openRawResource(R.raw.citizen_extended_v2).bufferedReader().use { it.readText() },
        ),
    )
}
