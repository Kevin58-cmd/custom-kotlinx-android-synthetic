package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

object SyntheticViewGenerator : Disposable {

    private val LOG = Logger.getInstance(SyntheticViewGenerator::class.java)

    init {
        registerLayoutFileWatcher()
    }

    fun generateSingleLayout(projectPath: String, layoutFilePath: String) {
        val layoutFile = File(layoutFilePath)
        if (!layoutFile.exists()) return

        val projectRoot = File(projectPath)
        val moduleDir = generateSequence(layoutFile.parentFile) { it.parentFile }
            .firstOrNull { it.parentFile?.name == "src" && it.name == "main" }?.parentFile?.parentFile
            ?: return
        val layoutName = layoutFile.nameWithoutExtension

        LOG.info("moduleDir : ${moduleDir}")
        val namespace = extractNamespaceFromBuildGradle(moduleDir) ?: return
        LOG.info("namespace : ${namespace}")

//        val outputDir = moduleDir.toPath().resolve("kotlinx/android/synthetic/main/$layoutName")
        val outputDir = moduleDir.toPath()
            .resolve("build/generated/synthetic-view-gen/kotlinx/android/synthetic/main/$layoutName")
        val hashCacheDir = moduleDir.toPath().resolve("build/synthetic-view-cache")
        Files.createDirectories(outputDir)
        Files.createDirectories(hashCacheDir)


        val layoutHash = calculateFileHash(layoutFile)
        val hashFile = hashCacheDir.resolve("$layoutName.md5").toFile()

        if (hashFile.exists() && hashFile.readText() == layoutHash) {
            LOG.info("Skip unchanged layout: ${layoutFile.name}")
            return
        }

        val views = parseViews(layoutFile)
        val kotlinCode = generateKotlinExtension(layoutName, views, namespace)
        val outPath = outputDir.resolve("synthetic.kt")

        if (outPath.toFile().exists() && outPath.toFile().readText() == kotlinCode) {
            LOG.info("Synthetic code unchanged for: ${layoutFile.name}, skipping write.")
        } else {
            Files.writeString(
                outPath,
                kotlinCode,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            LOG.info("Generated synthetic view code: $outPath")
        }

        val viewCode = generateViewKotlinExtension(layoutName, views, namespace)
        val viewOutPath = outputDir.resolve("view.kt")

        if (viewOutPath.toFile().exists() && viewOutPath.toFile().readText() == viewCode) {
            LOG.info("Synthetic code unchanged for: ${layoutFile.name}.view.kt, skipping write.")
        } else {
            Files.writeString(
                viewOutPath,
                viewCode,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            LOG.info("Generated synthetic view code: $viewOutPath")
        }

        hashFile.writeText(layoutHash)
    }

    fun generate(projectPath: String) {
        LOG.info("SyntheticViewGenerator: start generating synthetic views for project: $projectPath")
        val projectRoot = File(projectPath)
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            LOG.warn("Project root path not found or not a directory: $projectPath")
            return
        }
        val modules = projectRoot.listFiles { file -> file.isDirectory } ?: return

        modules.forEach { moduleDir ->
            LOG.info("module name: ${moduleDir.name}")
//            if (moduleDir.name.contains("app") || moduleDir.name.contains("arch") ) {
            val layoutDir = File(moduleDir, "src/main/res/layout")
            LOG.info("layoutDir: ${layoutDir.absolutePath}")
            if (!layoutDir.exists() || !layoutDir.isDirectory) {
                LOG.info("Module ${moduleDir.name} has no layout directory, skip.")
                return@forEach
            }
            val layoutFiles =
                layoutDir.listFiles { file -> file.extension == "xml" } ?: return@forEach
            LOG.info("layoutFiles size : ${layoutFiles.size}")
            layoutFiles.forEach { layoutFile ->
                generateSingleLayout(projectPath, layoutFile.absolutePath)
            }
//            }
        }
    }

    private fun registerLayoutFileWatcher() {
        VirtualFileManager.getInstance().addAsyncFileListener({ events: List<VFileEvent> ->
            events.filter { it.path.contains("/res/layout") && it.file?.extension == "xml" }
                .forEach {
                    val layoutPath = it.file?.path ?: return@forEach
                    val projectPath = guessProjectPathFrom(layoutPath) ?: return@forEach
                    LOG.info("layoutFile change : ${layoutPath}")
                    generateSingleLayout(projectPath, layoutPath)
                }
            null
        }, SyntheticViewGenerator)
    }

    private fun guessProjectPathFrom(path: String): String? {
        val file = File(path)
        return generateSequence(file.parentFile) { it.parentFile }
            .firstOrNull {
                it.resolve("settings.gradle").exists() || it.resolve("settings.gradle.kts").exists()
            }
            ?.absolutePath
    }

    private fun parseViews(layoutFile: File): List<Pair<String, String>> {
        val views = mutableListOf<Pair<String, String>>()
        try {
            val dbFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = dbFactory.newDocumentBuilder()
            val doc = docBuilder.parse(layoutFile)
            doc.documentElement.normalize()

            val allElements = doc.getElementsByTagName("*")
            for (i in 0 until allElements.length) {
                val node = allElements.item(i)
                val idAttr = node.attributes?.getNamedItem("android:id")?.nodeValue ?: continue
                val id = idAttr.substringAfter('/')
                val rawTag = node.nodeName
                val viewType = resolveFullViewType(rawTag)
                if (node.nodeName !== "fragment")
                    views.add(viewType to id)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to parse layout file: ${layoutFile.absolutePath}, exception: $e")
        }
        return views
    }

    private fun resolveFullViewType(tagName: String): String {
        if("MZBannerView" in tagName) return "$tagName<*>"
        if ('.' in tagName) return tagName

        return when (tagName) {
            "WebView" -> "android.webkit.WebView"
            "View", "ViewGroup", "SurfaceView", "ViewStub", "TextureView" ->
                "android.view.$tagName"

            else -> "android.widget.$tagName"
        }
    }

    private fun generateViewKotlinExtension(
        layoutName: String,
        views: List<Pair<String, String>>,
        namespace: String
    ): String {
        val packageName = "kotlinx.android.synthetic.main.$layoutName.view"
        val rImport = "$namespace.R"

        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import android.view.View")
            appendLine("import $rImport")

            views.forEach { (viewType, idName) ->
                appendLine(
                    """
val View.$idName: $viewType
    get() {
        val key = R.id.$idName
        val cached = getTag(key)
        if (cached is $viewType) return cached
        val view = findViewById<$viewType>(key)
        setTag(key, view)
        return view
    }
                        """
                )
            }
        }
    }


    private fun generateKotlinExtension(
        layoutName: String,
        views: List<Pair<String, String>>,
        namespace: String
    ): String {
        val packageName = "kotlinx.android.synthetic.main.$layoutName"
        val rImport = "$namespace.R"
        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import android.app.Activity")
            appendLine("import android.view.View")
            appendLine("import androidx.fragment.app.Fragment")
            appendLine("import androidx.lifecycle.DefaultLifecycleObserver")
            appendLine("import androidx.lifecycle.LifecycleOwner")
            appendLine("import androidx.recyclerview.widget.RecyclerView")
            appendLine("import android.app.Dialog")
            appendLine("import android.widget.*")
            appendLine("import $rImport")
            appendLine("import java.util.WeakHashMap")
            appendLine()
            appendLine("// Generated by SyntheticViewGenerator for layout: $layoutName")
            appendLine()
            appendLine("private val _syntheticCache = WeakHashMap<Any, MutableMap<Int, View>>()")
            appendLine("@Suppress(\"UNCHECKED_CAST\")")
            appendLine("private fun <T : View> Any.cachedFindViewById(id: Int, finder: () -> T): T {")
            appendLine("    val cache = _syntheticCache.getOrPut(this) { mutableMapOf() }")
            appendLine("    return cache.getOrPut(id) { finder() } as T")
            appendLine("}")
            appendLine()
            appendLine("private val _syntheticRegistered = WeakHashMap<Any, Boolean>()")
            appendLine("private fun Fragment.registerSyntheticClearOnDestroyView() {")
            appendLine("    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {")
            appendLine("        override fun onDestroy(owner: LifecycleOwner) {")
            appendLine("            _syntheticCache.remove(this@registerSyntheticClearOnDestroyView)")
            appendLine("        }")
            appendLine("    })")
            appendLine("}")
            appendLine()
            appendLine("private fun Activity.registerSyntheticClearOnDestroy() {")
            appendLine("    if (this is LifecycleOwner) {")
            appendLine("        lifecycle.addObserver(object : DefaultLifecycleObserver {")
            appendLine("            override fun onDestroy(owner: LifecycleOwner) {")
            appendLine("                _syntheticCache.remove(this@registerSyntheticClearOnDestroy)")
            appendLine("            }")
            appendLine("        })")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("private val Fragment.syntheticCacheAutoRegister: Unit")
            appendLine("    get() {")
            appendLine("        if (_syntheticRegistered.put(this, true) == true) return Unit")
            appendLine("        registerSyntheticClearOnDestroyView()")
            appendLine("        return Unit")
            appendLine("    }")
            appendLine()
            appendLine("private val Activity.syntheticCacheAutoRegister: Unit")
            appendLine("    get() {")
            appendLine("        if (_syntheticRegistered.put(this, true) == true) return Unit")
            appendLine("        registerSyntheticClearOnDestroy()")
            appendLine("        return Unit")
            appendLine("    }")


            val receivers = listOf(
                "Activity" to "{ syntheticCacheAutoRegister; findViewById(RES_ID) }",
                "Fragment" to "{syntheticCacheAutoRegister; requireView().findViewById(RES_ID) }",
            )
            receivers.forEach { (receiver, callExpr) ->
                views.forEach { (viewType, idName) ->
                    appendLine(
                        "val $receiver.$idName: $viewType get() = cachedFindViewById(R.id.$idName, ${
                            callExpr.replace(
                                "RES_ID",
                                "R.id.$idName"
                            )
                        })"
                    )
                }
                appendLine()
            }

            views.forEach { (viewType, idName) ->
                appendLine(
                    """
val RecyclerView.ViewHolder.$idName: $viewType
    get() {
        val key = R.id.$idName
        val cached = itemView.getTag(key)
        if (cached is $viewType) return cached
        val found = itemView.findViewById<$viewType>(key)
        itemView.setTag(key, found)
        return found
    }
                """
                )
            }

            views.forEach { (viewType, idName) ->
                appendLine(
                    """
val Dialog.$idName: $viewType
    get() {
        val key = R.id.$idName
        val cached = window?.decorView?.getTag(key)
        if (cached is $viewType) return cached
        val view = findViewById<$viewType>(key)
        window?.decorView?.setTag(key, view)
        return view
    }
                """
                )
            }
        }
    }

    private fun extractNamespaceFromBuildGradle(moduleDir: File): String? {
        val gradleFile = File(moduleDir, "build.gradle")
        if (!gradleFile.exists()) return null
        return gradleFile.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("namespace") }
            ?.substringAfter("namespace")
            ?.removePrefix("=")
            ?.trim()
            ?.removeSurrounding("\"")
    }

    private fun calculateFileHash(file: File): String {
        val bytes = file.readBytes()
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(bytes)).toString(16).padStart(32, '0')
    }

    override fun dispose() {
        Disposer.dispose(this)
    }
}
