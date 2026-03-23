package com.baozi.laninjector.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtils {

    /**
     * Read a specific entry from a ZIP input stream.
     */
    fun readEntry(zipStream: ZipInputStream, entryName: String): ByteArray? {
        var entry = zipStream.nextEntry
        while (entry != null) {
            if (entry.name == entryName) {
                val baos = ByteArrayOutputStream()
                zipStream.copyTo(baos)
                zipStream.closeEntry()
                return baos.toByteArray()
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        return null
    }

    /**
     * List all entry names in a ZIP.
     */
    fun listEntries(zipStream: ZipInputStream): List<String> {
        val entries = mutableListOf<String>()
        var entry = zipStream.nextEntry
        while (entry != null) {
            entries.add(entry.name)
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        return entries
    }

    /**
     * Copy a ZIP entry from input to output.
     */
    fun copyEntry(input: InputStream, output: ZipOutputStream, entry: ZipEntry) {
        val newEntry = ZipEntry(entry.name)
        if (entry.method == ZipEntry.STORED) {
            newEntry.method = ZipEntry.STORED
            newEntry.size = entry.size
            newEntry.compressedSize = entry.compressedSize
            newEntry.crc = entry.crc
        } else {
            newEntry.method = ZipEntry.DEFLATED
        }
        output.putNextEntry(newEntry)
        input.copyTo(output)
        output.closeEntry()
    }

    /**
     * Write bytes as a new ZIP entry.
     */
    fun writeEntry(output: ZipOutputStream, name: String, data: ByteArray, compress: Boolean = true) {
        val entry = ZipEntry(name)
        if (!compress) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(data)
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        output.putNextEntry(entry)
        output.write(data)
        output.closeEntry()
    }
}
