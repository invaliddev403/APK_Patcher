package dev.apkpatcher

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DexPatcherTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun currentProfilePatchesAllMethodsAcrossMultipleDexFiles() {
        val profile = citizenProfile()
        val apk = writeFixture(profile)
        val result = DexPatcher(apk, temporaryFolder.newFolder("work"), profile).patch()

        assertEquals(39, result.patchedMethods.size)
        assertEquals(setOf("classes.dex", "classes2.dex"), result.replacementDexFiles.keys)
        val methods = readMethods(result.replacementDexFiles)
        val parameterized = methods.getValue(profile.patches.first().signature)
        assertEquals(3, parameterized.implementation!!.registerCount)
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN), parameterized.implementation!!.instructions.map { it.opcode })

        val noParameters = methods.getValue(MethodSignature(
            "Lsp0n/citizen/domain/models/user/PrivateUser;",
            "isPaid",
            returnType = "Z",
        ))
        assertEquals(2, noParameters.implementation!!.registerCount)

        val nullReturn = methods.getValue(profile.patches.first {
            it.replacement is MethodReplacement.ReturnNull
        }.signature)
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), nullReturn.implementation!!.instructions.map { it.opcode })

        val staticReturn = methods.getValue(profile.patches.first {
            it.replacement is MethodReplacement.ReturnStaticField
        }.signature)
        assertEquals(listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT), staticReturn.implementation!!.instructions.map { it.opcode })

        val dismissed = methods.getValue(profile.patches.first {
            it.replacement is MethodReplacement.DismissActivity
        }.signature)
        assertEquals(3, dismissed.implementation!!.registerCount)
        assertEquals(
            listOf(Opcode.INVOKE_SUPER, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
            dismissed.implementation!!.instructions.map { it.opcode },
        )

        val redirect = methods.getValue(profile.patches.first {
            it.replacement is MethodReplacement.LaunchActivityFromField
        }.signature)
        assertEquals(6, redirect.implementation!!.registerCount)
        assertEquals(
            listOf(
                Opcode.IGET_OBJECT,
                Opcode.NEW_INSTANCE,
                Opcode.CONST_CLASS,
                Opcode.INVOKE_DIRECT,
                Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_VIRTUAL,
                Opcode.SGET_OBJECT,
                Opcode.RETURN_OBJECT,
            ),
            redirect.implementation!!.instructions.map { it.opcode },
        )
    }

    @Test
    fun credentialProfileWritesStringsWithoutPersistingThemInTheDefinition() {
        val profile = citizenProfile("test-user", "test-token")
        val result = DexPatcher(
            writeFixture(profile),
            temporaryFolder.newFolder("credentials-work"),
            profile,
        ).patch()
        val methods = readMethods(result.replacementDexFiles)

        assertEquals(42, result.patchedMethods.size)
        val tokenMethod = methods.getValue(MethodSignature(
            "Lsp0n/citizen/data/common/SessionManager;",
            "getUserToken",
            returnType = "Ljava/lang/String;",
        ))
        assertEquals(2, tokenMethod.implementation!!.registerCount)
        val instruction = tokenMethod.implementation!!.instructions.first() as ReferenceInstruction
        assertEquals("test-token", (instruction.reference as StringReference).string)
    }

    @Test
    fun incompatibleProfileReportsEveryMissingMethod() {
        val current = citizenProfile()
        val missing = MethodPatch(
            MethodSignature("Lexample/Missing;", "run", returnType = "Z"),
            MethodReplacement.ReturnBoolean(true),
        )
        val future = PatchProfile("future-profile", current.patches + missing)
        val error = assertThrows(IllegalArgumentException::class.java) {
            DexPatcher(writeFixture(current), temporaryFolder.newFolder("missing-work"), future).patch()
        }
        assertTrue(error.message!!.contains("future-profile"))
        assertTrue(error.message!!.contains("Lexample/Missing;->run"))
    }

    @Test
    fun voidReplacementNeedsNoLocalRegisters() {
        val patch = MethodPatch(
            MethodSignature("Lexample/Worker;", "run", returnType = "V"),
            MethodReplacement.ReturnVoid,
        )
        val profile = PatchProfile("void-profile", listOf(patch))
        val result = DexPatcher(
            writeFixture(profile),
            temporaryFolder.newFolder("void-work"),
            profile,
        ).patch()
        val method = readMethods(result.replacementDexFiles).getValue(patch.signature)
        assertEquals(1, method.implementation!!.registerCount)
        assertEquals(listOf(Opcode.RETURN_VOID), method.implementation!!.instructions.map { it.opcode })
    }

    @Test
    fun duplicateMethodSignaturesAreRejected() {
        val patch = citizenProfile().patches.first()
        assertThrows(IllegalArgumentException::class.java) {
            PatchProfile("invalid", listOf(patch, patch))
        }
    }

    private fun citizenProfile(userId: String = "", userToken: String = ""): PatchProfile =
        PatchPackParser.parse(File("src/main/res/raw/citizen_extended_v2.json").readText()).resolve(
            mapOf("userId" to userId, "userToken" to userToken),
        )

    private fun writeFixture(profile: PatchProfile): File {
        val classes = profile.patches.groupBy { it.signature.classType }.values.toList()
        val dexFiles = listOf(
            writeDex("classes.dex", classes.filterIndexed { index, _ -> index % 2 == 0 }),
            writeDex("classes2.dex", classes.filterIndexed { index, _ -> index % 2 == 1 }),
        ).filter { it.length() > 0 }
        val apk = temporaryFolder.newFile("fixture-${System.nanoTime()}.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            dexFiles.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return apk
    }

    private fun writeDex(name: String, classes: List<List<MethodPatch>>): File {
        val file = File(temporaryFolder.root, name)
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { patches ->
            val classType = patches.first().signature.classType
            val methods = patches.map { patch -> originalMethod(patch) }
            pool.internClass(ImmutableClassDef(
                classType,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                "Ljava/lang/Object;",
                emptyList(),
                null,
                emptySet(),
                emptyList(),
                emptyList(),
                emptyList(),
                methods,
            ))
        }
        pool.writeTo(FileDataStore(file))
        return file
    }

    private fun originalMethod(patch: MethodPatch): ImmutableMethod {
        val signature = patch.signature
        val implementation = when (signature.returnType) {
            "Z" -> ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ), emptyList(), emptyList())
            "V" -> ImmutableMethodImplementation(4, listOf(
                org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), emptyList(), emptyList())
            "Ljava/lang/String;" -> ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("original")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ), emptyList(), emptyList())
            else -> ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ), emptyList(), emptyList())
        }
        return ImmutableMethod(
            signature.classType,
            signature.name,
            signature.parameters.map { ImmutableMethodParameter(it, emptySet(), null) },
            signature.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            emptySet(),
            null,
            implementation,
        )
    }

    private fun readMethods(replacements: Map<String, File>): Map<MethodSignature, Method> {
        val result = mutableMapOf<MethodSignature, Method>()
        replacements.values.forEach { file ->
            DexFileFactory.loadDexFile(file, null).classes.forEach { classDef ->
                (classDef.directMethods + classDef.virtualMethods).forEach { method ->
                    result[MethodSignature(
                        method.definingClass,
                        method.name,
                        method.parameters.map { it.type },
                        method.returnType,
                    )] = method
                }
            }
        }
        return result
    }
}
