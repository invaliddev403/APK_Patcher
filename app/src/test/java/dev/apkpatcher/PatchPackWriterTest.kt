package dev.apkpatcher

import org.junit.Assert.assertEquals
import org.junit.Test

class PatchPackWriterTest {
    @Test
    fun everyOperationRoundTripsThroughJson() {
        val patches = listOf(
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "boolean", returnType = "Z"),
                ReplacementSpec.ReturnBoolean(false),
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "string", returnType = "Ljava/lang/String;"),
                ReplacementSpec.ReturnString("value"),
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "input", returnType = "Ljava/lang/String;"),
                ReplacementSpec.ReturnInput("token"),
                setOf("token"),
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "void", returnType = "V"),
                ReplacementSpec.ReturnVoid,
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "null", returnType = "Ljava/lang/Object;"),
                ReplacementSpec.ReturnNull,
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "unit", returnType = "Ljava/lang/Object;"),
                ReplacementSpec.ReturnStaticField("Lkotlin/Unit;", "a", "Lkotlin/Unit;"),
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "onCreate", listOf("Landroid/os/Bundle;"), "V"),
                ReplacementSpec.DismissActivity,
            ),
            MethodPatchSpec(
                MethodSignature("Lexample/A;", "emit", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;"),
                ReplacementSpec.LaunchActivityFromField(
                    "Lexample/Target;",
                    "activity",
                    "Lexample/A;",
                ),
            ),
        )
        val pack = PatchPack(
            1,
            "round-trip",
            "Round trip",
            PatchTarget("com.example.app", "Example", "1.0"),
            listOf(PatchInput("token", PatchInputType.SECRET_STRING, false)),
            patches,
        )

        assertEquals(pack, PatchPackParser.parse(PatchPackWriter.write(pack)))
    }
}
