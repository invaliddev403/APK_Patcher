package dev.apkpatcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object PatchPackParser {
    const val FORMAT_VERSION = 1

    fun parse(json: String): PatchPack = try {
        parsePack(JSONObject(json))
    } catch (error: JSONException) {
        throw IllegalArgumentException("Invalid patch pack JSON: ${error.message}", error)
    }

    private fun parsePack(root: JSONObject): PatchPack {
        root.allowOnly("formatVersion", "id", "name", "application", "inputs", "patches")
        val formatVersion = root.get("formatVersion")
        require(formatVersion == FORMAT_VERSION) { "Only patch-pack format $FORMAT_VERSION is supported." }
        val id = root.requiredString("id")
        require(ID.matches(id)) { "Patch-pack ID must use lowercase letters, numbers, dots, dashes, or underscores." }
        val application = parseTarget(root.getObject("application"))
        val inputs = root.getArray("inputs").mapObjects("inputs", ::parseInput)
        require(inputs.map { it.id }.distinct().size == inputs.size) {
            "Patch pack $id contains duplicate input IDs."
        }
        val patches = root.getArray("patches").mapObjects("patches") { parsePatch(it, inputs) }
        require(patches.isNotEmpty()) { "Patch pack $id must contain at least one patch." }
        return PatchPack(FORMAT_VERSION, id, root.requiredString("name"), application, inputs, patches)
    }

    private fun parseTarget(json: JSONObject): PatchTarget {
        json.allowOnly("packageName", "displayName", "versionName")
        val packageName = json.requiredString("packageName")
        require(PACKAGE_NAME.matches(packageName)) { "Invalid application package name: $packageName" }
        return PatchTarget(
            packageName,
            json.requiredString("displayName"),
            json.optionalString("versionName"),
        )
    }

    private fun parseInput(json: JSONObject): PatchInput {
        json.allowOnly("id", "type", "required")
        val id = json.requiredString("id")
        require(INPUT_ID.matches(id)) { "Invalid input ID: $id" }
        val type = when (json.requiredString("type")) {
            "secretString" -> PatchInputType.SECRET_STRING
            else -> throw IllegalArgumentException("Unsupported input type for $id.")
        }
        val required = if (json.has("required")) json.getBoolean("required") else false
        return PatchInput(id, type, required)
    }

    private fun parsePatch(json: JSONObject, inputs: List<PatchInput>): MethodPatchSpec {
        json.allowOnly("dexClass", "method", "parameters", "returnType", "requiresInputs", "replacement")
        val classType = json.requiredString("dexClass")
        require(CLASS_TYPE.matches(classType)) { "Invalid DEX class descriptor: $classType" }
        val parameters = json.getArray("parameters").stringList("parameters").mapIndexed { index, value ->
            require(isType(value)) { "Invalid parameter descriptor at index $index." }
            value
        }
        val returnType = json.requiredString("returnType")
        require(isType(returnType, allowVoid = true)) { "Invalid return descriptor: $returnType" }
        val requiresInputs = if (json.has("requiresInputs")) {
            json.getArray("requiresInputs").stringList("requiresInputs")
        } else {
            emptyList()
        }
        require(requiresInputs.distinct().size == requiresInputs.size) {
            "A patch contains duplicate required inputs."
        }
        require(inputs.map { it.id }.containsAll(requiresInputs)) {
            "A patch references an undeclared required input."
        }
        val signature = MethodSignature(
            classType,
            json.requiredString("method"),
            parameters,
            returnType,
        )
        val replacement = parseReplacement(json.getObject("replacement"), returnType, inputs, requiresInputs)
        if (replacement == ReplacementSpec.DismissActivity) {
            require(signature.name == "onCreate" && signature.parameters == listOf("Landroid/os/Bundle;")) {
                "dismissActivity supports only onCreate(Bundle)."
            }
        }
        return MethodPatchSpec(signature, replacement, requiresInputs.toSet())
    }

    private fun parseReplacement(
        json: JSONObject,
        returnType: String,
        inputs: List<PatchInput>,
        requiresInputs: List<String>,
    ): ReplacementSpec = when (json.requiredString("type")) {
        "returnBoolean" -> {
            json.allowOnly("type", "value")
            require(returnType == "Z") { "returnBoolean requires a Z method return type." }
            ReplacementSpec.ReturnBoolean(json.getBoolean("value"))
        }
        "returnString" -> {
            json.allowOnly("type", "value")
            require(returnType == STRING_TYPE) { "returnString requires a String method return type." }
            ReplacementSpec.ReturnString(json.requiredString("value"))
        }
        "returnInput" -> {
            json.allowOnly("type", "inputId")
            require(returnType == STRING_TYPE) { "returnInput requires a String method return type." }
            val inputId = json.requiredString("inputId")
            require(inputs.any { it.id == inputId }) { "Replacement references undeclared input $inputId." }
            require(inputId in requiresInputs) { "Input $inputId must also be listed in requiresInputs." }
            ReplacementSpec.ReturnInput(inputId)
        }
        "returnVoid" -> {
            json.allowOnly("type")
            require(returnType == "V") { "returnVoid requires a V method return type." }
            ReplacementSpec.ReturnVoid
        }
        "returnNull" -> {
            json.allowOnly("type")
            require(returnType.startsWith("L") || returnType.startsWith("[")) {
                "returnNull requires an object or array return type."
            }
            ReplacementSpec.ReturnNull
        }
        "returnStaticField" -> {
            json.allowOnly("type", "classType", "fieldName", "fieldType")
            val classType = json.requiredString("classType")
            val fieldType = json.requiredString("fieldType")
            require(CLASS_TYPE.matches(classType) && CLASS_TYPE.matches(fieldType)) {
                "returnStaticField requires object class and field descriptors."
            }
            require(returnType == fieldType || returnType == "Ljava/lang/Object;") {
                "Static field type must match the method return type or Object."
            }
            ReplacementSpec.ReturnStaticField(classType, json.requiredString("fieldName"), fieldType)
        }
        "dismissActivity" -> {
            json.allowOnly("type")
            require(returnType == "V") { "dismissActivity requires a V method return type." }
            ReplacementSpec.DismissActivity
        }
        "launchActivityFromField" -> {
            json.allowOnly("type", "targetClass", "fieldName", "fieldType")
            val targetClass = json.requiredString("targetClass")
            val fieldType = json.requiredString("fieldType")
            require(CLASS_TYPE.matches(targetClass) && CLASS_TYPE.matches(fieldType)) {
                "launchActivityFromField requires object class descriptors."
            }
            require(returnType == "Ljava/lang/Object;") {
                "launchActivityFromField requires an Object method return type."
            }
            ReplacementSpec.LaunchActivityFromField(
                targetClass,
                json.requiredString("fieldName"),
                fieldType,
            )
        }
        else -> throw IllegalArgumentException("Unsupported replacement type.")
    }

    private fun JSONObject.allowOnly(vararg allowed: String) {
        val unknown = keys().asSequence().toSet() - allowed.toSet()
        require(unknown.isEmpty()) { "Unknown patch-pack field(s): ${unknown.joinToString()}" }
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = get(key)
        require(value is String && value.isNotEmpty()) { "Field $key must be a non-empty string." }
        return value
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (has(key)) requiredString(key) else null

    private fun JSONObject.getObject(key: String): JSONObject =
        get(key) as? JSONObject ?: throw IllegalArgumentException("Field $key must be an object.")

    private fun JSONObject.getArray(key: String): JSONArray =
        get(key) as? JSONArray ?: throw IllegalArgumentException("Field $key must be an array.")

    private fun <T> JSONArray.mapObjects(path: String, transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index ->
            val value = get(index) as? JSONObject
                ?: throw IllegalArgumentException("$path[$index] must be an object.")
            transform(value)
        }

    private fun JSONArray.stringList(path: String): List<String> = (0 until length()).map { index ->
        get(index) as? String ?: throw IllegalArgumentException("$path[$index] must be a string.")
    }

    private fun isType(value: String, allowVoid: Boolean = false): Boolean =
        TYPE.matches(value) || allowVoid && value == "V"

    private val ID = Regex("[a-z0-9][a-z0-9._-]{0,79}")
    private val INPUT_ID = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val CLASS_TYPE = Regex("L[\\w/$-]+;")
    private val TYPE = Regex("\\[*(?:[ZBSCIJFD]|L[\\w/$-]+;)")
    private const val STRING_TYPE = "Ljava/lang/String;"
}
