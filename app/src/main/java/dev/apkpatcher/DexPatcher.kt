package dev.apkpatcher

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipFile

data class DexPatchResult(
    val profileId: String,
    val replacementDexFiles: Map<String, File>,
    val patchedMethods: List<String>,
)

internal class DexPatcher(
    private val baseApk: File,
    private val workDirectory: File,
    private val profile: PatchProfile,
    private val cancellation: OperationCancellation? = null,
) {
    private fun replacementBody(
        replacement: MethodReplacement,
        signature: MethodSignature,
        superclass: String,
        receiverRegister: Int,
    ): List<org.jf.dexlib2.iface.instruction.Instruction> = when (replacement) {
        is MethodReplacement.ReturnBoolean -> listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, if (replacement.value) 1 else 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        is MethodReplacement.ReturnString -> listOf(
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                0,
                ImmutableStringReference(replacement.value),
            ),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        MethodReplacement.ReturnVoid -> listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        MethodReplacement.ReturnNull -> listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        is MethodReplacement.ReturnStaticField -> listOf(
            ImmutableInstruction21c(
                Opcode.SGET_OBJECT,
                0,
                ImmutableFieldReference(replacement.classType, replacement.name, replacement.type),
            ),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        MethodReplacement.DismissActivity -> listOf(
            ImmutableInstruction35c(
                Opcode.INVOKE_SUPER,
                2,
                receiverRegister,
                receiverRegister + 1,
                0,
                0,
                0,
                ImmutableMethodReference(
                    superclass,
                    "onCreate",
                    listOf("Landroid/os/Bundle;"),
                    "V",
                ),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                1,
                receiverRegister,
                0,
                0,
                0,
                0,
                ImmutableMethodReference("Landroid/app/Activity;", "finish", emptyList(), "V"),
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        is MethodReplacement.LaunchActivityFromField -> listOf(
            ImmutableInstruction22c(
                Opcode.IGET_OBJECT,
                0,
                receiverRegister,
                ImmutableFieldReference(signature.classType, replacement.fieldName, replacement.fieldType),
            ),
            ImmutableInstruction21c(
                Opcode.NEW_INSTANCE,
                1,
                ImmutableTypeReference("Landroid/content/Intent;"),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_CLASS,
                2,
                ImmutableTypeReference(replacement.targetClass),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_DIRECT,
                3,
                1,
                0,
                2,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/content/Intent;",
                    "<init>",
                    listOf("Landroid/content/Context;", "Ljava/lang/Class;"),
                    "V",
                ),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                2,
                0,
                1,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/content/Context;",
                    "startActivity",
                    listOf("Landroid/content/Intent;"),
                    "V",
                ),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                1,
                0,
                0,
                0,
                0,
                0,
                ImmutableMethodReference("Landroid/app/Activity;", "finish", emptyList(), "V"),
            ),
            ImmutableInstruction21c(
                Opcode.SGET_OBJECT,
                0,
                ImmutableFieldReference("Lkotlin/Unit;", "a", "Lkotlin/Unit;"),
            ),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
    }

    fun patch(): DexPatchResult {
        val required = profile.patches.associate { it.signature to it.replacement }
        val remaining = required.keys.toMutableSet()
        val patchedMethods = mutableListOf<String>()
        val replacements = mutableMapOf<String, File>()
        val dexDirectory = File(workDirectory, "dex").apply { mkdirs() }

        ZipFile(baseApk).use { zip ->
            val dexEntries = zip.entries().toList().filter {
                Regex("classes[0-9]*\\.dex").matches(it.name)
            }.sortedBy { it.name }
            if (dexEntries.isEmpty()) {
                throw IllegalArgumentException("The base APK contains no DEX bytecode.")
            }

            for (entry in dexEntries) {
                cancellation?.check()
                val inputDex = File(dexDirectory, "input-${entry.name}")
                zip.getInputStream(entry).use { input ->
                    inputDex.outputStream().use { output -> input.copyTo(output) }
                }
                val dexFile = DexFileFactory.loadDexFile(inputDex, null)
                var changed = false
                val rewrittenClasses = dexFile.classes.map { classDef ->
                    cancellation?.check()
                    var classChanged = false
                    val directMethods = classDef.directMethods.map { method ->
                        rewriteMethod(method, classDef.superclass ?: "Ljava/lang/Object;", required, remaining, patchedMethods)
                            ?.also { changed = true; classChanged = true } ?: method
                    }
                    val virtualMethods = classDef.virtualMethods.map { method ->
                        rewriteMethod(method, classDef.superclass ?: "Ljava/lang/Object;", required, remaining, patchedMethods)
                            ?.also { changed = true; classChanged = true } ?: method
                    }
                    if (!classChanged) {
                        return@map classDef
                    }
                    ImmutableClassDef(
                        classDef.type,
                        classDef.accessFlags,
                        classDef.superclass,
                        classDef.interfaces,
                        classDef.sourceFile,
                        classDef.annotations,
                        classDef.staticFields,
                        classDef.instanceFields,
                        directMethods,
                        virtualMethods,
                    )
                }

                if (changed) {
                    val output = File(dexDirectory, entry.name)
                    val pool = DexPool(dexFile.opcodes)
                    rewrittenClasses.forEach { pool.internClass(it) }
                    pool.writeTo(FileDataStore(output))
                    replacements[entry.name] = output
                }
                inputDex.delete()
            }
        }

        if (remaining.isNotEmpty()) {
            val missing = remaining.joinToString("\n") { "• ${it.displayName}" }
            throw IllegalArgumentException(
                "Profile ${profile.id} is incompatible with this application version. Missing methods:\n$missing",
            )
        }
        return DexPatchResult(profile.id, replacements, patchedMethods)
    }

    private fun rewriteMethod(
        method: Method,
        superclass: String,
        required: Map<MethodSignature, MethodReplacement>,
        remaining: MutableSet<MethodSignature>,
        patchedMethods: MutableList<String>,
    ): Method? {
        val signature = MethodSignature(
            method.definingClass,
            method.name,
            method.parameters.map { it.type },
            method.returnType,
        )
        if (signature !in required || signature !in remaining) {
            return null
        }
        remaining.remove(signature)
        patchedMethods.add(signature.displayName)
        val replacement = required.getValue(signature)
        val parameterRegisters = method.parameters.fold(0) { total, parameter ->
            total + if (parameter.type == "J" || parameter.type == "D") 2 else 1
        }
        val receiverRegisters = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        val localRegisters = when (replacement) {
            MethodReplacement.ReturnVoid -> 0
            MethodReplacement.DismissActivity -> 1
            is MethodReplacement.LaunchActivityFromField -> 3
            else -> 1
        }
        val instructions = replacementBody(replacement, signature, superclass, localRegisters)
        val implementation = ImmutableMethodImplementation(
            localRegisters + receiverRegisters + parameterRegisters,
            instructions,
            emptyList(),
            emptyList(),
        )
        return object : Method by method {
            override fun getImplementation(): MethodImplementation = implementation
        }
    }
}
