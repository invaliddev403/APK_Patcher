package dev.apkpatcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PatchPackParserTest {
    @Test
    fun builtInCitizenPackResolvesCoreAndCredentialProfiles() {
        val json = File("src/main/res/raw/citizen_extended_v2.json").readText()
        val pack = PatchPackParser.parse(json)

        assertEquals(42, pack.patches.size)
        assertEquals(39, pack.resolve(emptyMap()).patches.size)
        val resolved = pack.resolve(mapOf("userId" to "test-user", "userToken" to "test-token"))
        assertEquals(42, resolved.patches.size)
        assertEquals(
            "test-token",
            (resolved.patches.first { it.signature.name == "getUserToken" }.replacement
                as MethodReplacement.ReturnString).value,
        )
        assertFalse(json.contains("test-token"))
        assertTrue(pack.application.matches("sp0n.citizen", "0.1316.0"))
        assertFalse(pack.application.matches("sp0n.citizen", "future"))
        assertFalse(pack.application.matches("com.example", "0.1316.0"))
    }

    @Test
    fun resolvesConditionalAndConstantReplacements() {
        val pack = PatchPackParser.parse(validPack().toString())
        assertEquals(2, pack.resolve(emptyMap()).patches.size)
        val resolved = pack.resolve(mapOf("token" to "secret-value"))
        assertEquals(3, resolved.patches.size)
        val replacements = resolved.patches.associate { it.signature.name to it.replacement }
        assertEquals(MethodReplacement.ReturnBoolean(true), replacements["isEnabled"])
        assertEquals(MethodReplacement.ReturnString("patched"), replacements["getLabel"])
        assertEquals(MethodReplacement.ReturnString("secret-value"), replacements["getToken"])
    }

    @Test
    fun rejectsUnknownFieldsAndOperations() {
        val unknownField = validPack().put("executeCode", "not-supported")
        assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(unknownField.toString())
        }

        val unknownOperation = validPack()
        unknownOperation.getJSONArray("patches").getJSONObject(0)
            .getJSONObject("replacement").put("type", "runCode")
        val error = assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(unknownOperation.toString())
        }
        assertEquals("Unsupported replacement type.", error.message)
    }

    @Test
    fun rejectsInvalidDescriptorsAndReturnTypeMismatches() {
        val invalidDescriptor = validPack()
        invalidDescriptor.getJSONArray("patches").getJSONObject(0)
            .getJSONArray("parameters").put("java.lang.String")
        assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(invalidDescriptor.toString())
        }

        val wrongReturn = validPack()
        wrongReturn.getJSONArray("patches").getJSONObject(0).put("returnType", "I")
        assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(wrongReturn.toString())
        }
    }

    @Test
    fun rejectsDuplicateSignaturesAndUndeclaredInputs() {
        val duplicate = validPack()
        duplicate.getJSONArray("patches").put(duplicate.getJSONArray("patches").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(duplicate.toString())
        }

        val unknownInput = validPack()
        unknownInput.getJSONArray("patches").getJSONObject(2)
            .getJSONObject("replacement").put("inputId", "missing")
        assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(unknownInput.toString())
        }
    }

    @Test
    fun validatesRequiredAndUnknownResolvedInputs() {
        val required = validPack()
        required.getJSONArray("inputs").getJSONObject(0).put("required", true)
        val pack = PatchPackParser.parse(required.toString())
        assertThrows(IllegalArgumentException::class.java) { pack.resolve(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) {
            pack.resolve(mapOf("token" to "value", "unknown" to "value"))
        }
    }

    @Test
    fun acceptsVoidReturnsOnlyForVoidMethods() {
        val valid = validPack()
        val patch = valid.getJSONArray("patches").getJSONObject(0)
        patch.put("returnType", "V")
        patch.put("replacement", JSONObject().put("type", "returnVoid"))
        val resolved = PatchPackParser.parse(valid.toString()).resolve(emptyMap())
        assertEquals(MethodReplacement.ReturnVoid, resolved.patches.first().replacement)

        patch.put("returnType", "Z")
        assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(valid.toString())
        }
    }

    @Test
    fun rejectsUnsupportedFormatVersion() {
        val pack = validPack().put("formatVersion", 2)
        val error = assertThrows(IllegalArgumentException::class.java) {
            PatchPackParser.parse(pack.toString())
        }
        assertEquals("Only patch-pack format 1 is supported.", error.message)
    }

    private fun validPack() = JSONObject(
        """
        {
          "formatVersion": 1,
          "id": "example-v1",
          "name": "Example patches",
          "application": {
            "packageName": "com.example.app",
            "displayName": "Example",
            "versionName": "1.0"
          },
          "inputs": [
            {"id": "token", "type": "secretString", "required": false}
          ],
          "patches": [
            {
              "dexClass": "Lcom/example/Feature;",
              "method": "isEnabled",
              "parameters": [],
              "returnType": "Z",
              "replacement": {"type": "returnBoolean", "value": true}
            },
            {
              "dexClass": "Lcom/example/Feature;",
              "method": "getLabel",
              "parameters": [],
              "returnType": "Ljava/lang/String;",
              "replacement": {"type": "returnString", "value": "patched"}
            },
            {
              "dexClass": "Lcom/example/Session;",
              "method": "getToken",
              "parameters": [],
              "returnType": "Ljava/lang/String;",
              "requiresInputs": ["token"],
              "replacement": {"type": "returnInput", "inputId": "token"}
            }
          ]
        }
        """.trimIndent(),
    )
}
