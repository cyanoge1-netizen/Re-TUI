package ohi.andre.consolelauncher.managers.dex

import android.content.Context
import dalvik.system.DexClassLoader
import ohi.andre.consolelauncher.dex.RetuiDexModule
import ohi.andre.consolelauncher.managers.modules.ModuleManager
import ohi.andre.consolelauncher.tuils.Tuils
import org.json.JSONObject
import java.io.File

/**
 * Discovers and loads dynamic Kotlin/DEX modules from Re-T-UI/dex-modules/<id>/.
 * Copies DEX payloads into read-only codeCacheDir to satisfy Android 14+ (API 34+) restrictions.
 */
object DexModuleManager {
    const val SOURCE_PREFIX: String = "dex:"
    private const val DIR_NAME = "dex-modules"

    data class DexManifest(
        val id: String,
        val title: String,
        val entryClass: String,
        val dexFile: File
    )

    private val loadedModules = HashMap<String, RetuiDexModule>()

    fun rootDir(): File? {
        val base = Tuils.getFolder() ?: return null
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun normalizeId(raw: String?): String =
        (raw ?: "").trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")

    fun readManifest(idRaw: String?): DexManifest? {
        val id = normalizeId(idRaw)
        if (id.isEmpty()) return null
        val dir = File(rootDir() ?: return null, id)
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        return try {
            val json = JSONObject(manifestFile.readText())
            val entryClass = json.optString("entryClass", "").trim()
            val dexName = json.optString("dexFile", "module.dex").trim().ifEmpty { "module.dex" }
            val title = json.optString("title", id.uppercase()).trim().ifEmpty { id.uppercase() }
            val dexFile = File(dir, dexName)
            if (entryClass.isEmpty() || !dexFile.isFile) return null
            DexManifest(id, title, entryClass, dexFile)
        } catch (e: Exception) {
            Tuils.log(e)
            null
        }
    }

    fun getTitle(idRaw: String?): String? = readManifest(idRaw)?.title

    fun scanAndRegister(context: Context): List<String> {
        val root = rootDir() ?: return emptyList()
        val registered = ArrayList<String>()
        val dirs = root.listFiles() ?: return emptyList()
        for (sub in dirs) {
            if (!sub.isDirectory) continue
            val manifest = readManifest(sub.name) ?: continue
            ModuleManager.setScriptModule(context, manifest.id, SOURCE_PREFIX + manifest.id)
            ModuleManager.addToDock(context, arrayListOf(manifest.id))
            registered.add(manifest.id)
        }
        return registered
    }

    fun load(context: Context, idRaw: String?, forceReload: Boolean = false): Result<RetuiDexModule> {
        val id = normalizeId(idRaw)
        if (!forceReload) {
            loadedModules[id]?.let { return Result.success(it) }
        } else {
            loadedModules.remove(id)?.onDestroy()
        }

        val manifest = readManifest(id)
            ?: return Result.failure(IllegalStateException("Missing manifest.json or DEX file for '$id'"))

        return try {
            val cacheDir = File(context.codeCacheDir, DIR_NAME).apply { mkdirs() }
            val cachedDex = File(cacheDir, "$id-${manifest.dexFile.lastModified()}.dex")
            if (!cachedDex.exists() || cachedDex.length() != manifest.dexFile.length()) {
                cacheDir.listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
                manifest.dexFile.copyTo(cachedDex, overwrite = true)
            }
            // Android 14+ (API 34+) requires dynamically loaded DEX files to be read-only.
            cachedDex.setReadOnly()

            val loader = DexClassLoader(
                cachedDex.absolutePath,
                null,
                null,
                context.classLoader
            )
            val clazz = loader.loadClass(manifest.entryClass)
            val instance = clazz.getDeclaredConstructor().newInstance() as? RetuiDexModule
                ?: return Result.failure(IllegalStateException("${manifest.entryClass} must implement RetuiDexModule"))
            loadedModules[id] = instance
            Result.success(instance)
        } catch (t: Throwable) {
            Tuils.log(t)
            Result.failure(t)
        }
    }

    fun disposeAll() {
        loadedModules.values.forEach {
            try { it.onDestroy() } catch (_: Throwable) {}
        }
        loadedModules.clear()
    }
}
