package com.baozi.laninjector.injection

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkRebuilder {

    companion object {
        private const val TAG = "LanInjector"
    }

    fun rebuild(
        originalApkFile: File,
        outputFile: File,
        modifiedManifest: ByteArray?,
        modifiedDexFiles: Map<String, ByteArray>,
        additionalDexFiles: Map<String, ByteArray>,
        additionalAssets: Map<String, ByteArray> = emptyMap()
    ) {
        outputFile.parentFile?.mkdirs()

        val zipFile = ZipFile(originalApkFile)
        val zipOut = ZipOutputStream(FileOutputStream(outputFile))

        var entryCount = 0

        for (entry in zipFile.entries()) {
            val name = entry.name

            // Skip signature files
            if (name.startsWith("META-INF/")) continue

            when {
                name == "AndroidManifest.xml" && modifiedManifest != null -> {
                    writeEntry(zipOut, name, modifiedManifest, stored = true)
                }

                name in modifiedDexFiles -> {
                    writeEntry(zipOut, name, modifiedDexFiles[name]!!, stored = true)
                }

                else -> {
                    // Stream copy — don't load entire entry into memory
                    val mustStore = entry.method == ZipEntry.STORED
                            || name.endsWith(".so")
                            || name.endsWith(".arsc")
                    streamCopyEntry(zipFile, entry, zipOut, mustStore)
                }
            }
            entryCount++
        }

        // Add new DEX files
        for ((name, data) in additionalDexFiles) {
            writeEntry(zipOut, name, data, stored = true)
            entryCount++
        }

        // Add additional asset files (e.g., locale list)
        for ((name, data) in additionalAssets) {
            writeEntry(zipOut, "assets/$name", data, stored = false)
            entryCount++
        }

        zipOut.close()
        zipFile.close()
        Log.d(TAG, "ApkRebuilder: wrote $entryCount entries to ${outputFile.name}")
    }

    /**
     * Stream copy an entry from source ZipFile to output ZipOutputStream.
     * Does not load the entire entry into memory.
     */
    private fun streamCopyEntry(
        zipFile: ZipFile,
        entry: ZipEntry,
        zipOut: ZipOutputStream,
        stored: Boolean
    ) {
        val newEntry = ZipEntry(entry.name)

        if (stored) {
            newEntry.method = ZipEntry.STORED
            // For STORED entries, we need size + CRC upfront
            // If original was STORED, reuse its metadata
            if (entry.method == ZipEntry.STORED && entry.size >= 0 && entry.crc >= 0) {
                newEntry.size = entry.size
                newEntry.compressedSize = entry.size
                newEntry.crc = entry.crc
                zipOut.putNextEntry(newEntry)
                zipFile.getInputStream(entry).use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                return
            }
            // Otherwise we must compute CRC by reading through the data
            // Use a temp file to avoid OOM for large entries
            val tempFile = File.createTempFile("entry_", ".tmp")
            try {
                var size = 0L
                val crc = CRC32()
                zipFile.getInputStream(entry).use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (input.read(buf).also { len = it } != -1) {
                            output.write(buf, 0, len)
                            crc.update(buf, 0, len)
                            size += len
                        }
                    }
                }
                newEntry.size = size
                newEntry.compressedSize = size
                newEntry.crc = crc.value
                zipOut.putNextEntry(newEntry)
                tempFile.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            } finally {
                tempFile.delete()
            }
        } else {
            newEntry.method = ZipEntry.DEFLATED
            zipOut.putNextEntry(newEntry)
            zipFile.getInputStream(entry).use { it.copyTo(zipOut) }
            zipOut.closeEntry()
        }
    }

    private fun writeEntry(zipOut: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = CRC32()
            crc.update(data)
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zipOut.putNextEntry(entry)
        zipOut.write(data)
        zipOut.closeEntry()
    }
}
