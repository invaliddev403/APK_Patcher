package dev.apkpatcher

import org.json.JSONArray
import org.json.JSONObject

internal object PatchPackWriter {
    fun write(pack: PatchPack): String = JSONObject().apply {
        put("formatVersion", pack.formatVersion)
        put("id", pack.id)
        put("name", pack.name)
        put("application", JSONObject().apply {
            put("packageName", pack.application.packageName)
            put("displayName", pack.application.displayName)
            pack.application.versionName?.let { put("versionName", it) }
        })
        put("inputs", JSONArray().apply {
            pack.inputs.forEach { input ->
                put(JSONObject().apply {
                    put("id", input.id)
                    put("type", "secretString")
                    put("required", input.required)
                })
            }
        })
        put("patches", JSONArray().apply {
            pack.patches.forEach { patch ->
                put(JSONObject().apply {
                    put("dexClass", patch.signature.classType)
                    put("method", patch.signature.name)
                    put("parameters", JSONArray(patch.signature.parameters))
                    put("returnType", patch.signature.returnType)
                    if (patch.requiresInputs.isNotEmpty()) {
                        put("requiresInputs", JSONArray(patch.requiresInputs.sorted()))
                    }
                    put("replacement", replacement(patch.replacement))
                })
            }
        })
    }.toString(2)

    private fun replacement(replacement: ReplacementSpec): JSONObject = when (replacement) {
        is ReplacementSpec.ReturnBoolean -> JSONObject()
            .put("type", "returnBoolean")
            .put("value", replacement.value)
        is ReplacementSpec.ReturnString -> JSONObject()
            .put("type", "returnString")
            .put("value", replacement.value)
        is ReplacementSpec.ReturnInput -> JSONObject()
            .put("type", "returnInput")
            .put("inputId", replacement.inputId)
        ReplacementSpec.ReturnVoid -> JSONObject().put("type", "returnVoid")
        ReplacementSpec.ReturnNull -> JSONObject().put("type", "returnNull")
        is ReplacementSpec.ReturnStaticField -> JSONObject()
            .put("type", "returnStaticField")
            .put("classType", replacement.classType)
            .put("fieldName", replacement.name)
            .put("fieldType", replacement.type)
        ReplacementSpec.DismissActivity -> JSONObject().put("type", "dismissActivity")
        is ReplacementSpec.LaunchActivityFromField -> JSONObject()
            .put("type", "launchActivityFromField")
            .put("targetClass", replacement.targetClass)
            .put("fieldName", replacement.fieldName)
            .put("fieldType", replacement.fieldType)
    }
}
