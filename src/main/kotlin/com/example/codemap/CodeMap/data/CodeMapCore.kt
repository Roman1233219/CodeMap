package com.example.codemap.CodeMap.data

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

data class RawFile(
    val fileName: String,
    val filePath: String,
    val fileType: String,
    val classes: List<RawClass>,
    val externalReferences: List<String>,
    val isVirtual: Boolean = false
)

data class RawClass(val name: String, val methods: List<String>)

data class Connection(val from: String, val to: String, val type: String, var weight: Int = 0)

data class MapData(val blocks: Map<String, List<MapNode>>, val connections: List<Connection>)

data class MapNode(val name: String, val path: String, val internalStructure: RawFile)

class CodeMapCore(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dbPath = "${project.basePath}/.idea/codemap_db.json"

    fun refreshDatabase(onFinished: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val resultFiles = mutableListOf<RawFile>()
            ApplicationManager.getApplication().runReadAction {
                val baseDir = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
                if (baseDir != null) deepScan(baseDir, PsiManager.getInstance(project), resultFiles)
            }
            File(dbPath).writeText(gson.toJson(resultFiles))
            ApplicationManager.getApplication().invokeLater { onFinished() }
        }
    }

    private fun deepScan(vFile: VirtualFile, psiManager: PsiManager, result: MutableList<RawFile>) {
        if (vFile.isDirectory) {
            vFile.children.forEach { deepScan(it, psiManager, result) }
            return
        }
        val ext = vFile.extension?.lowercase() ?: ""
        if (ext !in listOf("kt", "java", "xml")) return

        ApplicationManager.getApplication().runReadAction {
            val psiFile = psiManager.findFile(vFile) ?: return@runReadAction
            val classes = mutableListOf<RawClass>()
            val refs = mutableSetOf<String>()

            // Собираем классы
            PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java).forEach { 
                classes.add(RawClass(it.name ?: "Unknown", it.methods.map { m -> m.name }))
            }

            // Ищем любые упоминания системных классов и внешних связей
            val text = psiFile.text
            if (text.contains("SharedPreferences") || text.contains("getSharedPreferences")) refs.add("SharedPreferences")
            if (text.contains("Room") || text.contains("Dao")) refs.add("RoomDatabase")
            if (text.contains("Retrofit") || text.contains("Http")) refs.add("NetworkAPI")

            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiReference) {
                        val res = element.resolve()
                        if (res is PsiClass) res.name?.let { refs.add(it) }
                    }
                    super.visitElement(element)
                }
            })
            result.add(RawFile(vFile.name, vFile.path, ext.uppercase(), classes, refs.toList()))
        }
    }

    fun buildMapOnTheFly(): MapData {
        val dbFile = File(dbPath)
        if (!dbFile.exists()) return MapData(emptyMap(), emptyList())
        val rawFiles: List<RawFile> = gson.fromJson(dbFile.readText(), object : TypeToken<List<RawFile>>() {}.type)
        
        val blocks = mutableMapOf<String, MutableList<MapNode>>()
        val itemToCat = mutableMapOf<String, String>()

        // 1. Реальные файлы
        for (f in rawFiles) {
            val cat = when {
                f.fileType == "XML" || f.fileName.lowercase().contains("activity") || f.fileName.lowercase().contains("fragment") -> "Экраны"
                else -> determineCategoryByName(f.fileName + f.classes.joinToString { it.name })
            }
            blocks.getOrPut(cat) { mutableListOf() }.add(MapNode(f.fileName, f.filePath, f))
            itemToCat[f.fileName] = cat
            f.classes.forEach { itemToCat[it.name] = cat }
        }

        // 2. Виртуальные системные файлы
        rawFiles.flatMap { it.externalReferences }.distinct().forEach { ref ->
            if (itemToCat[ref] == null) {
                val cat = determineCategoryByName(ref)
                if (cat != "Прочее") {
                    itemToCat[ref] = cat
                    val vFile = RawFile(ref, "virtual", "SYS", emptyList(), emptyList(), true)
                    blocks.getOrPut(cat) { mutableListOf() }.add(MapNode("[System] $ref", "virtual", vFile))
                }
            }
        }

        // 3. Связи
        val conns = mutableMapOf<String, Connection>()
        for (f in rawFiles) {
            val from = itemToCat[f.fileName] ?: continue
            f.externalReferences.forEach { ref ->
                val to = itemToCat[ref] ?: return@forEach
                if (from != to) {
                    val key = "${from}_${to}"
                    val type = if (from == "Экраны") "EVENT" else "ACTION"
                    conns.getOrPut(key) { Connection(from, to, type) }.weight++
                }
            }
        }
        return MapData(blocks, conns.values.toList())
    }

    private fun determineCategoryByName(name: String): String {
        val low = name.lowercase()
        return when {
            low.contains("activity") || low.contains("fragment") || low.contains("view") || low.contains("screen") || low.contains("layout") || low.contains("adapter") || low.contains("viewmodel") -> "Экраны"
            low.contains("manager") || low.contains("service") || low.contains("usecase") || low.contains("controller") -> "Логика"
            low.contains("repository") || low.contains("database") || low.contains("dao") || low.contains("prefs") || low.contains("sharedpreferences") || low.contains("entity") || low.contains("model") -> "Данные"
            low.contains("api") || low.contains("network") || low.contains("retrofit") || low.contains("http") || low.contains("client") -> "Сеть"
            low.contains("utils") || low.contains("helper") || low.contains("base") || low.contains("ext") -> "Утилиты"
            else -> "Прочее"
        }
    }

    fun openDbFile() {
        val f = File(dbPath)
        if (f.exists()) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            vf?.let { ApplicationManager.getApplication().invokeLater { FileEditorManager.getInstance(project).openFile(it, true) } }
        }
    }

    fun openProjectFile(p: String) {
        if (p == "virtual") return
        val f = File(p)
        if (f.exists()) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            vf?.let { ApplicationManager.getApplication().invokeLater { FileEditorManager.getInstance(project).openFile(it, true) } }
        }
    }
}
