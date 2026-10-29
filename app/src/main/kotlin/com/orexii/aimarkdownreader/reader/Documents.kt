package com.orexii.aimarkdownreader.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URLDecoder

/**
 * Reading documents: single files the user picked or shared, files inside a folder the user opened (so
 * images and links next to them work), and the bundled samples.
 */
object Documents {
    const val SAMPLES = "samples"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val MARKDOWN = Regex("""\.(md|markdown|mdown|mkd|mkdn|mdx|txt)$""", RegexOption.IGNORE_CASE)

    fun isMarkdown(name: String) = MARKDOWN.containsMatchIn(name)

    suspend fun read(context: Context, uri: Uri, folder: String? = null): OpenDocument = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited() } ?: error("The file could not be opened.")
        OpenDocument(uri.toString(), displayName(context, uri), decode(bytes), folder)
    }

    fun sample(context: Context, name: String): OpenDocument {
        val bytes = context.assets.open("$SAMPLES/$name").use { it.readLimited() }
        return OpenDocument("asset:///$SAMPLES/$name", name, decode(bytes), SAMPLES)
    }

    /** A document shared as text rather than as a file. */
    fun shared(text: String, title: String?): OpenDocument =
        OpenDocument("shared:${text.hashCode()}", title?.takeIf { it.isNotBlank() } ?: "Shared text", text, null)

    private fun InputStream.readLimited(): ByteArray {
        val bytes = readNBytesCompat(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "This file is too large to read (more than 8 MB)." }
        return bytes
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        return if (text.startsWith('\uFEFF')) text.substring(1) else text
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    /** Markdown files in a folder the user opened, with their path inside it (subfolders up to three deep). */
    suspend fun list(context: Context, tree: Uri): List<Pair<String, Uri>> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
        val found = mutableListOf<Pair<String, Uri>>()
        fun walk(folder: DocumentFile, prefix: String, depth: Int) {
            for (child in folder.listFiles().sortedBy { it.name?.lowercase() }) {
                val name = child.name ?: continue
                if (name.startsWith(".")) continue
                if (child.isDirectory && depth < 3) walk(child, "$prefix$name/", depth + 1)
                else if (child.isFile && isMarkdown(name)) found += "$prefix$name" to child.uri
            }
        }
        walk(root, "", 0)
        found
    }

    /** The folder value for a file opened from a tree: the tree and the file's directory inside it. */
    fun folderOf(tree: Uri, path: String): String = "tree:$tree|${path.substringBeforeLast('/', "")}"

    /**
     * Resolves a path relative to a document (an image or a link) to something that can be opened: an asset
     * path for samples, or a document inside the opened folder. Null when the document has no folder access.
     */
    fun resolve(context: Context, document: OpenDocument, relative: String): Resolved? {
        val folder = document.folder ?: return null
        val clean = URLDecoder.decode(relative.substringBefore('#').substringBefore('?'), "UTF-8").removePrefix("./")
        if (clean.isEmpty() || clean.contains("://")) return null
        if (folder == SAMPLES) {
            val path = normalize("", clean) ?: return null
            return Resolved.Asset("$SAMPLES/$path", path.substringAfterLast('/'))
        }
        if (!folder.startsWith("tree:")) return null
        val tree = Uri.parse(folder.removePrefix("tree:").substringBefore('|'))
        val directory = folder.substringAfter('|', "")
        val path = normalize(directory, clean) ?: return null
        var file = DocumentFile.fromTreeUri(context, tree) ?: return null
        for (part in path.split('/').filter { it.isNotEmpty() }) file = file.findFile(part) ?: return null
        return Resolved.Tree(tree, file.uri, path, file.name ?: path.substringAfterLast('/'))
    }

    private fun normalize(directory: String, relative: String): String? {
        val parts = ArrayDeque<String>()
        val start = if (relative.startsWith("/")) "" else directory
        for (part in (start.split('/') + relative.split('/')).filter { it.isNotEmpty() && it != "." }) {
            if (part == "..") parts.removeLastOrNull() ?: return null else parts.addLast(part)
        }
        return parts.joinToString("/")
    }

    sealed interface Resolved {
        val name: String
        data class Asset(val path: String, override val name: String) : Resolved
        data class Tree(val tree: Uri, val uri: Uri, val path: String, override val name: String) : Resolved
    }

    fun open(context: Context, resolved: Resolved): InputStream? = when (resolved) {
        is Resolved.Asset -> runCatching { context.assets.open(resolved.path) }.getOrNull()
        is Resolved.Tree -> runCatching { context.contentResolver.openInputStream(resolved.uri) }.getOrNull()
    }

    fun isTreeDocument(uri: Uri) = DocumentsContract.isTreeUri(uri)
}
