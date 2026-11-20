package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UiFont(val id: String, val name: String)

object UiFonts {
    const val MAX_BYTES = 10 * 1024 * 1024
    val builtIn = emptyList<String>()
    private val customId = Regex("custom-[a-f0-9-]{36}")

    fun isCustom(id: String): Boolean = customId.matches(id)
}

class UiFontStore private constructor(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val mutableFonts = MutableStateFlow(readIndex())
    val fonts: StateFlow<List<UiFont>> = mutableFonts.asStateFlow()

    fun cachedTypeface(id: String): Typeface? = typefaces[id]

    fun typeface(id: String): Typeface? =
        cachedTypeface(id) ?: if (UiFonts.isCustom(id)) load(id) else null

    fun fontFile(id: String): File? = if (UiFonts.isCustom(id)) file(id).takeIf { it.isFile } else null

    @Synchronized
    fun import(fileName: String, bytes: ByteArray): UiFont {
        require(isSupportedFontFile(fileName, bytes))
        val current = mutableFonts.value
        current.firstOrNull { file(it.id).takeIf(File::isFile)?.readBytes()?.contentEquals(bytes) == true }
            ?.let { return it }
        val name = fileName.substringBeforeLast('.').trim().take(80).ifBlank { fileName }
        val font = UiFont("custom-${UUID.randomUUID()}", name)
        write(file(font.id), bytes)
        try {
            requireNotNull(load(font.id))
            writeIndex(current + font)
        } catch (error: Exception) {
            typefaces.remove(font.id)
            file(font.id).delete()
            throw error
        }
        mutableFonts.value = current + font
        return font
    }

    @Synchronized
    fun remove(id: String) {
        val remaining = mutableFonts.value.filterNot { it.id == id }
        writeIndex(remaining)
        typefaces.remove(id)
        file(id).delete()
        mutableFonts.value = remaining
    }

    private fun load(id: String): Typeface? = file(id).takeIf { it.isFile }
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?.also { typefaces[id] = it }

    private fun readIndex(): List<UiFont> = runCatching {
        json.decodeFromString<List<UiFont>>(indexFile().readText())
            .filter { UiFonts.isCustom(it.id) && file(it.id).isFile }
    }.getOrDefault(emptyList())

    private fun writeIndex(fonts: List<UiFont>) {
        write(indexFile(), json.encodeToString(fonts).encodeToByteArray())
    }

    private fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }

    private fun indexFile() = File(dir, "fonts.json")

    private fun file(id: String): File {
        require(UiFonts.isCustom(id))
        return File(dir, id)
    }

    companion object {
        @Volatile
        private var instance: UiFontStore? = null

        fun get(context: Context): UiFontStore = instance ?: synchronized(this) {
            instance ?: UiFontStore(File(context.applicationContext.filesDir, "ui-fonts"))
                .also { instance = it }
        }
    }
}
