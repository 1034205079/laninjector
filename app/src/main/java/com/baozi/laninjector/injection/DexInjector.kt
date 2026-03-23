package com.baozi.laninjector.injection

import android.content.Context
import android.util.Log
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

class DexInjector(private val context: Context) {

    companion object {
        private const val TAG = "LanInjector"
    }

    data class DexInjectionResult(
        val modifiedDexIndex: Int,
        val modifiedDexBytes: ByteArray,
        val payloadDexBytes: ByteArray
    )

    fun inject(
        apkFile: java.io.File,
        launcherActivity: String,
        locales: List<String>,
        dexCount: Int
    ): DexInjectionResult {
        val smaliClassName = "L${launcherActivity.replace('.', '/')};"
        Log.d(TAG, "DexInjector: looking for $smaliClassName")

        val zipFile = java.util.zip.ZipFile(apkFile)
        var targetDexName: String? = null
        var targetDexBytes: ByteArray? = null

        for (entry in zipFile.entries()) {
            if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue
            val bytes = zipFile.getInputStream(entry).readBytes()
            val dexFile = DexBackedDexFile(Opcodes.forApi(24), bytes)
            for (classDef in dexFile.classes) {
                if (classDef.type == smaliClassName) {
                    targetDexName = entry.name
                    targetDexBytes = bytes
                    break
                }
            }
            if (targetDexName != null) break
        }
        zipFile.close()

        requireNotNull(targetDexName) { "Launcher activity $launcherActivity not found in any DEX file" }
        requireNotNull(targetDexBytes)
        Log.d(TAG, "DexInjector: found launcher in $targetDexName")

        val modifiedDex = modifyDex(targetDexBytes, smaliClassName)

        val dexIndex = if (targetDexName == "classes.dex") 0
        else targetDexName.removePrefix("classes").removeSuffix(".dex").toInt() - 1

        val payloadDex = context.assets.open("payload.dex").readBytes()

        return DexInjectionResult(dexIndex, modifiedDex, payloadDex)
    }

    private fun modifyDex(dexBytes: ByteArray, targetClass: String): ByteArray {
        val dexFile = DexBackedDexFile(Opcodes.forApi(24), dexBytes)
        val opcodes = Opcodes.forApi(24)

        val modifiedClasses = mutableListOf<ClassDef>()
        for (classDef in dexFile.classes) {
            if (classDef.type == targetClass) {
                modifiedClasses.add(modifyClass(classDef))
            } else {
                modifiedClasses.add(classDef)
            }
        }

        val dexPool = DexPool(opcodes)
        for (classDef in modifiedClasses) {
            dexPool.internClass(classDef)
        }
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        return dataStore.data
    }

    private fun modifyClass(classDef: ClassDef): ClassDef {
        val modifiedMethods = mutableListOf<Method>()

        for (method in classDef.methods) {
            if (method.name == "onCreate" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == "Landroid/os/Bundle;"
            ) {
                modifiedMethods.add(modifyOnCreate(method))
            } else {
                modifiedMethods.add(method)
            }
        }

        return ImmutableClassDef(
            classDef.type, classDef.accessFlags, classDef.superclass,
            classDef.interfaces, classDef.sourceFile, classDef.annotations,
            classDef.fields, modifiedMethods
        )
    }

    /**
     * Insert a single invoke-static {p0} call at the beginning of onCreate.
     * No extra registers needed — p0 (this) is always available.
     * PayloadInit.init(Activity) reads locales from assets at runtime.
     */
    private fun modifyOnCreate(method: Method): Method {
        val impl = method.implementation
            ?: throw IllegalStateException("onCreate has no implementation")

        val mutableImpl = MutableMethodImplementation(impl)
        val regCount = impl.registerCount
        val paramCount = method.parameterTypes.size + 1 // +1 for 'this'
        val p0 = regCount - paramCount

        Log.d(TAG, "DexInjector: onCreate regCount=$regCount, paramCount=$paramCount, p0=v$p0")

        // Build single instruction: invoke-static {p0}, PayloadInit.init(Activity)V
        val initCall = BuilderInstruction35c(
            com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC,
            1, p0, 0, 0, 0, 0,
            ImmutableMethodReference(
                "Lcom/baozi/laninjector/payload/PayloadInit;",
                "init",
                listOf("Landroid/app/Activity;"),
                "V"
            )
        )

        // Insert at position 0 (before everything, including super.onCreate)
        // This is safe because PayloadInit.init() checks `initialized` flag
        mutableImpl.addInstruction(0, initCall)

        val newImpl = ImmutableMethodImplementation(
            regCount, // NO change to register count!
            mutableImpl.instructions,
            mutableImpl.tryBlocks,
            mutableImpl.debugItems
        )

        return ImmutableMethod(
            method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl
        )
    }
}
