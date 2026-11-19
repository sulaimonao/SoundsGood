package com.example.sync

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class SyncFileStorage(private val rootDir: File) {

    constructor(context: Context) : this(context.filesDir)

    val audioDir: File = File(rootDir, "synced_audio")
    val stagingDir: File = File(rootDir, "synced_audio_staging")

    fun ensureDirectories() {
        for (directory in listOf(audioDir, stagingDir)) {
            if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
                throw SyncStorageException("Could not prepare app-owned sync storage.")
            }
        }
    }

    /**
     * Returns an immutable, hash-qualified app-owned filename inside [audioDir].
     * Inputs must already be validated ([SyncManifestValidator.SHA256_REGEX] and allowlisted ext).
     */
    fun targetFileFor(sha256: String, extension: String): File {
        require(SyncManifestValidator.SHA256_REGEX.matches(sha256)) { "Invalid sha256 digest" }
        require(extension in SyncManifestValidator.ALLOWED_EXTENSION_MIME_PAIRS) { "Invalid extension" }
        return File(audioDir, "$sha256.$extension")
    }

    fun newStagingPartFile(): File {
        ensureDirectories()
        return File(stagingDir, "${UUID.randomUUID()}.part")
    }

    fun cleanIncompleteStagingFiles() {
        if (!stagingDir.exists() || !stagingDir.isDirectory) return
        stagingDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    fun deletePartFileQuietly(partFile: File) {
        if (partFile.parentFile == stagingDir && partFile.name.endsWith(".part")) {
            partFile.delete()
        }
    }

    fun verifyExistingFile(file: File, expectedSizeBytes: Long, expectedSha256: String): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.length() != expectedSizeBytes) return false
        val actualHash = computeFileSha256Hex(file) ?: return false
        return constantTimeHexEquals(actualHash, expectedSha256)
    }

    fun computeFileSha256Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            FileInputStream(file).use { fis ->
                while (true) {
                    val read = fis.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            PairingUriParser.bytesToLowerHex(digest.digest())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Atomically promotes a verified [partFile] to [finalFile]. If [finalFile] already exists
     * with the matching hash and size, the duplicate staging file is simply cleaned up.
     */
    fun publishVerifiedPart(partFile: File, finalFile: File, expectedSize: Long, expectedSha256: String): Boolean {
        ensureDirectories()
        if (verifyExistingFile(finalFile, expectedSize, expectedSha256)) {
            deletePartFileQuietly(partFile)
            return true
        }
        if (!verifyExistingFile(partFile, expectedSize, expectedSha256)) {
            deletePartFileQuietly(partFile)
            return false
        }
        // A rename replaces an invalid same-hash target atomically. Never unlink
        // a published file before its replacement is ready.
        try {
            FileOutputStream(partFile, true).use { it.fd.sync() }
        } catch (error: Exception) {
            throw SyncStorageException("Could not finish storing the verified file.", error)
        }
        if (partFile.renameTo(finalFile)) {
            return true
        }
        // Fallback atomic copy into sibling temp within audioDir, then rename
        val siblingTemp = File(audioDir, ".tmp_${UUID.randomUUID()}")
        return try {
            partFile.copyTo(siblingTemp, overwrite = true)
            FileOutputStream(siblingTemp, true).use { it.fd.sync() }
            if (!verifyExistingFile(siblingTemp, expectedSize, expectedSha256)) {
                siblingTemp.delete()
                return false
            }
            deletePartFileQuietly(partFile)
            if (siblingTemp.renameTo(finalFile)) {
                true
            } else {
                siblingTemp.delete()
                false
            }
        } catch (_: Exception) {
            siblingTemp.delete()
            deletePartFileQuietly(partFile)
            false
        }
    }

    /** Last terminal report only; fixed codes/opaque IDs, never pairing secrets or paths. */
    fun writeLastReport(report: SyncClientReport): Boolean {
        val atomic = AtomicFile(File(rootDir, "sync_last_report.json"))
        var output: FileOutputStream? = null
        return try {
            val bytes = SyncClientReports.encodeLocal(report).toByteArray(Charsets.UTF_8)
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
            true
        } catch (_: Exception) {
            output?.let { runCatching { atomic.failWrite(it) } }
            false
        }
    }

    fun readLastReport(): SyncClientReport? {
        val file = File(rootDir, "sync_last_report.json")
        if (!file.exists() || file.length() > SyncClientReports.MAX_LOCAL_BYTES) return null
        return try {
            AtomicFile(file).openRead().use { input ->
                // Never allocate based on an unchecked or concurrently grown file.
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() > SyncClientReports.MAX_LOCAL_BYTES - count) return null
                    bytes.write(buffer, 0, count)
                }
                SyncClientReports.decodeLocal(bytes.toString(Charsets.UTF_8.name()))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun availableStorageBytes(): Long {
        ensureDirectories()
        return try {
            StatFs(rootDir.absolutePath).availableBytes
        } catch (_: Exception) {
            rootDir.usableSpace
        }
    }

    private fun constantTimeHexEquals(a: String, b: String): Boolean {
        val ba = a.encodeToByteArray()
        val bb = b.encodeToByteArray()
        return MessageDigest.isEqual(ba, bb)
    }
}
