package dev.drydock.prototype

import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File

/**
 * D7 边界桥 / D25 文件互通第一档：workspace（环境内 /root）以 DocumentsProvider
 * 挂给全安卓（文件选择器、发送附件、云盘等），免任何存储权限。
 * 只读为主；写入经 ParcelFileDescriptor 直通目录文件（同 uid，无越权面）。
 */
class WorkspaceProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "dev.drydock.documents"
        private const val ROOT_DOC_ID = "/"
        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }

    override fun onCreate(): Boolean = true

    private fun rootDir(): File? =
        context?.let { File(RootfsManager.rootfsDir(it), "root") }?.takeIf { it.isDirectory }

    private fun fileFor(docId: String): File {
        val root = rootDir() ?: throw java.io.FileNotFoundException("workspace 未部署")
        return File(root, docId.trimStart('/'))
    }

    private fun docIdFor(file: File): String {
        val root = rootDir() ?: return "/"
        return file.absolutePath.removePrefix(root.absolutePath).ifBlank { "/" }
    }

    private fun mimeFor(file: File): String =
        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun flagsFor(file: File): Int =
        if (file.isDirectory) {
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: ROOT_PROJECTION)
        val root = rootDir() ?: return result
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "workspace")
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY)
            add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.sym_def_app_icon)
            add(DocumentsContract.Root.COLUMN_TITLE, "Drydock workspace")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Linux 环境工作目录")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = fileFor(documentId)
        return MatrixCursor(projection ?: DOC_PROJECTION).apply { addDocRow(this, f) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val dir = fileFor(parentDocumentId)
        val result = MatrixCursor(projection ?: DOC_PROJECTION)
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { addDocRow(result, it) }
        return result
    }

    private fun addDocRow(cursor: MatrixCursor, f: File) {
        if (!f.exists()) return
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docIdFor(f))
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeFor(f))
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, f.name)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flagsFor(f))
            add(DocumentsContract.Document.COLUMN_SIZE, f.length())
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = true

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: android.os.CancellationSignal?,
    ): ParcelFileDescriptor {
        val f = fileFor(documentId)
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
}
