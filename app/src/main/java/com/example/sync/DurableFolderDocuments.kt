package com.example.sync

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
internal const val MANAGED_MUSIC = "SoundsGood Library"
private const val MARKER = "SoundsGood-Library-v1.txt"
private val markerBytes = "SoundsGood public music library\nversion 1\n".toByteArray(Charsets.US_ASCII)

internal data class FolderDoc(val uri: Uri, val name: String, val isDirectory: Boolean, val size: Long?)
internal data class OwnedFolders(val managed: FolderDoc, val audio: FolderDoc, val records: FolderDoc)

internal suspend fun <T> folderIO(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: SyncException) { throw e }
    catch (e: Exception) {
        val full = generateSequence<Throwable>(e) { it.cause }.take(8).any {
            it.message?.contains("ENOSPC", true) == true || it.message?.contains("No space left", true) == true
        }
        throw SyncStorageException("The selected local folder could not be used. Reconnect it or choose it again.",
            e, if (full) "storage_full" else "local_storage_error")
    }
}

internal class DurableFolderDocuments(val resolver: ContentResolver, val tree: Uri) {
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    companion object {
        fun validateTree(tree: Uri) {
            require(tree.scheme == ContentResolver.SCHEME_CONTENT && tree.authority == LOCAL_STORAGE_AUTHORITY &&
                tree.query == null && tree.fragment == null && tree.pathSegments.size == 2 &&
                tree.pathSegments[0] == "tree") { "Choose a folder on this phone or a removable storage volume." }
            require(DurableFolderNames.allowedTreeId(DocumentsContract.getTreeDocumentId(tree)))
        }
    }

    fun acquireGrant(takeGrant: Boolean) {
        if (takeGrant) resolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            throw SyncStorageException("Choose the local music folder again and allow read and write access.")
        }
    }

    private fun id(doc: Uri): String = DocumentsContract.getDocumentId(doc)

    private fun shape(uri: Uri, expectedId: String) {
        require(uri.authority == LOCAL_STORAGE_AUTHORITY && uri.scheme == "content" &&
            DocumentsContract.getTreeDocumentId(uri) == rootId && id(uri) == expectedId)
    }

    private fun childId(parent: FolderDoc, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name &&
            name.none(Char::isISOControl))
        return id(parent.uri) + "/" + name
    }

    private fun readDoc(cursor: android.database.Cursor, uri: Uri): FolderDoc {
        val name = cursor.getString(1) ?: throw IOException("Missing document name")
        return FolderDoc(uri, name, cursor.getString(2) == Document.MIME_TYPE_DIR,
            if (cursor.isNull(3)) null else cursor.getLong(3))
    }

    private fun describe(uri: Uri): FolderDoc {
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Folder unavailable")
        return cursor.use {
            if (!it.moveToFirst()) throw FileNotFoundException("Document unavailable")
            shape(uri, it.getString(0) ?: throw IOException("Missing document ID"))
            readDoc(it, uri)
        }
    }

    fun checkReadable(folder: FolderDoc) {
        if (!describe(folder.uri).isDirectory) throw IOException("The storage folder is unavailable")
    }

    fun children(parent: FolderDoc, limit: Int, check: () -> Unit, visit: (FolderDoc) -> Unit) {
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id(parent.uri))
        val cursor = resolver.query(query, columns, null, null, null) ?: throw IOException("Folder unavailable")
        cursor.use {
            var count = 0
            while (it.moveToNext()) {
                check()
                if (++count > limit) throw SyncStorageException("This folder contains too many files.")
                val cid = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name ||
                    name.any(Char::isISOControl) || cid != childId(parent, name)) continue
                visit(readDoc(it, DocumentsContract.buildDocumentUriUsingTree(tree, cid)))
            }
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("Folder still loading")
        }
    }

    private fun knownDocument(parent: FolderDoc, name: String): FolderDoc? {
        val wanted = childId(parent, name)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, wanted)
        return try { describe(uri).also { require(it.name == name) } }
        catch (_: IOException) {
            // Some file providers hide scanner markers from query results but
            // still allow direct access to the exact, confined file.
            if (name != ".nomedia" && name != MARKER) return null
            try {
                resolver.openInputStream(uri)?.use { it.read() } ?: return null
                FolderDoc(uri, name, false, null)
            } catch (_: FileNotFoundException) { null }
        }
    }

    private fun named(parent: FolderDoc, name: String): FolderDoc? {
        knownDocument(parent, name)?.let { return it }
        // Absence must also be confirmed by a readable parent. An unavailable
        // volume can return a null direct query cursor.
        var found: FolderDoc? = null
        children(parent, 210_000, {}, { child ->
            if (child.name == name) {
                require(found == null)
                found = child
            }
        })
        return found
    }

    fun create(parent: FolderDoc, name: String, mime: String): FolderDoc {
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name)
            ?: throw IOException("Could not create document")
        shape(uri, childId(parent, name))
        return describe(uri).also { require(it.name == name) }
    }

    fun rename(parent: FolderDoc, document: FolderDoc, name: String): FolderDoc {
        val result = DocumentsContract.renameDocument(resolver, document.uri, name) ?: document.uri
        val actualName = id(result).substringAfterLast('/')
        shape(result, childId(parent, actualName))
        if (actualName != name) {
            // This is the provider-returned location of our own provisional
            // document. Leave any pre-existing file with the requested name alone.
            discard(FolderDoc(result, actualName, document.isDirectory, null))
            throw IOException("The selected folder already contains a conflicting name")
        }
        return knownDocument(parent, name) ?: throw IOException("Cannot verify the renamed document")
    }

    fun discard(doc: FolderDoc) {
        // Callers pass only a new, unique document created by this operation.
        runCatching { DocumentsContract.deleteDocument(resolver, doc.uri) }
    }

    fun provisional(parent: FolderDoc): FolderDoc =
        create(parent, "Pending-" + UUID.randomUUID().toString() + ".bin", "application/octet-stream")

    fun write(doc: FolderDoc, block: (java.io.OutputStream) -> Unit) {
        val output = resolver.openOutputStream(doc.uri, "w") ?: throw IOException("Cannot open new document")
        output.use { block(it); it.flush() }
    }

    fun read(doc: FolderDoc, limit: Int): ByteArray {
        if (doc.size != null && doc.size > limit) throw IOException("Document exceeds its limit")
        return resolver.openInputStream(doc.uri)?.use { TransferFolderBytes.readBounded(it, limit) }
            ?: throw IOException("Cannot read document")
    }

    fun publishBytes(parent: FolderDoc, name: String, bytes: ByteArray): FolderDoc {
        var owned = provisional(parent)
        try {
            write(owned) { it.write(bytes) }
            if (!read(owned, bytes.size).contentEquals(bytes)) throw IOException("Incomplete metadata write")
            owned = rename(parent, owned, name)
            if (!read(owned, bytes.size).contentEquals(bytes)) throw IOException("Published metadata changed")
            return owned
        } catch (error: Throwable) {
            discard(owned)
            throw error
        }
    }

    fun prepare(): OwnedFolders {
        val root = describe(rootUri)
        require(root.isDirectory)
        val existing = if (root.name == MANAGED_MUSIC) root else named(root, MANAGED_MUSIC)
        val managed = if (existing != null) {
            val marker = named(existing, MARKER)
            if (!existing.isDirectory || marker == null || marker.isDirectory ||
                !read(marker, markerBytes.size).contentEquals(markerBytes)) {
                throw SyncStorageException("Another folder named SoundsGood Library already exists here. Choose a different parent folder.")
            }
            existing
        } else {
            val temp = create(root, "SoundsGood-setup-" + UUID.randomUUID(), Document.MIME_TYPE_DIR)
            try {
                publishBytes(temp, MARKER, markerBytes)
                publishBytes(temp, ".nomedia", byteArrayOf())
                rename(root, temp, MANAGED_MUSIC)
            } catch (error: Throwable) {
                discard(temp)
                throw error
            }
        }
        val marker = named(managed, ".nomedia")
        if (marker == null) publishBytes(managed, ".nomedia", byteArrayOf())
        else require(!marker.isDirectory)
        fun dir(name: String): FolderDoc = (named(managed, name) ?: create(managed, name, Document.MIME_TYPE_DIR))
            .also { require(it.isDirectory) }
        return OwnedFolders(managed, dir("Audio"), dir("Records"))
    }

    fun capacity(): Long? {
        val columns = arrayOf(Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)
        return try {
            resolver.query(DocumentsContract.buildRootsUri(LOCAL_STORAGE_AUTHORITY), columns, null, null, null)?.use {
                while (it.moveToNext()) {
                    if (it.getString(0) == rootId.substringBefore(':') + ":" && !it.isNull(1)) {
                        return@use it.getLong(1).takeIf { bytes -> bytes >= 0L }
                    }
                }
                null
            }
        } catch (_: SecurityException) { null }
          catch (_: IllegalArgumentException) { null }
    }
}
