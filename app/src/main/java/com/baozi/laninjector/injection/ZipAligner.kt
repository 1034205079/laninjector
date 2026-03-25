package com.baozi.laninjector.injection

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry

/**
 * Aligns ZIP entries for Android APK compatibility.
 * - STORED .so files: 4096-byte (page) alignment
 * - Other STORED files: 4-byte alignment
 * - DEFLATED files: no alignment needed
 */
class ZipAligner {

    companion object {
        private const val TAG = "LanInjector"
        private const val PAGE_ALIGNMENT = 4096
        private const val DEFAULT_ALIGNMENT = 4
    }

    fun align(inputFile: File, outputFile: File) {
        val input = RandomAccessFile(inputFile, "r")
        val output = RandomAccessFile(outputFile, "rw")
        output.setLength(0)

        // Find EOCD
        val eocdOffset = findEOCD(input)
        input.seek(eocdOffset + 10)
        val totalEntries = readShortLE(input)
        val cdSize = readIntLE(input)
        val cdOffset = readIntLE(input).toLong() and 0xFFFFFFFFL

        // Parse central directory to get accurate entry metadata
        data class EntryInfo(
            val name: String,
            val method: Int,
            val crc: Long,
            val compressedSize: Long,
            val uncompressedSize: Long,
            val localOffset: Long,
            val flags: Int,
            val time: Int,
            val date: Int,
            val externalAttrs: Int,
            val nameBytes: ByteArray
        )

        val entries = mutableListOf<EntryInfo>()
        input.seek(cdOffset)

        for (i in 0 until totalEntries) {
            val sig = readIntLE(input)
            if (sig != 0x02014b50) break

            input.skipBytes(2) // version made by
            input.skipBytes(2) // version needed
            val flags = readShortLE(input)
            val method = readShortLE(input)
            val time = readShortLE(input)
            val date = readShortLE(input)
            val crc = readIntLE(input).toLong() and 0xFFFFFFFFL
            val compSize = readIntLE(input).toLong() and 0xFFFFFFFFL
            val uncompSize = readIntLE(input).toLong() and 0xFFFFFFFFL
            val nameLen = readShortLE(input)
            val extraLen = readShortLE(input)
            val commentLen = readShortLE(input)
            input.skipBytes(2) // disk number
            input.skipBytes(2) // internal attrs
            val externalAttrs = readIntLE(input)
            val localOffset = readIntLE(input).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            input.readFully(nameBytes)
            input.skipBytes(extraLen + commentLen)

            entries.add(EntryInfo(
                name = String(nameBytes, Charsets.UTF_8),
                method = method,
                crc = crc,
                compressedSize = compSize,
                uncompressedSize = uncompSize,
                localOffset = localOffset,
                flags = flags,
                time = time,
                date = date,
                externalAttrs = externalAttrs,
                nameBytes = nameBytes
            ))
        }

        Log.d(TAG, "ZipAligner: ${entries.size} entries to align")

        data class NewEntry(val info: EntryInfo, val newLocalOffset: Long)
        val newEntries = mutableListOf<NewEntry>()

        for (info in entries) {
            val newLocalOffset = output.filePointer

            // Read original local header to get local extra field size
            input.seek(info.localOffset + 26) // skip to name length field
            val localNameLen = readShortLE(input)
            val localExtraLen = readShortLE(input)

            // Calculate alignment
            val alignment = if (info.method == ZipEntry.STORED) {
                if (info.name.endsWith(".so")) PAGE_ALIGNMENT else DEFAULT_ALIGNMENT
            } else {
                1 // no alignment for DEFLATED
            }

            val dataOffsetBase = newLocalOffset + 30 + info.nameBytes.size
            val newExtraLen = if (alignment > 1) {
                ((alignment - ((dataOffsetBase % alignment).toInt())) % alignment)
            } else {
                0
            }

            // Write new local header with correct sizes from central directory
            // Clear flag bit 3 (data descriptor) since we put sizes in the header
            val newFlags = info.flags and 0xFFF7.toInt() // clear bit 3

            writeIntLE(output, 0x04034b50)            // signature
            writeShortLE(output, 20)                    // version needed
            writeShortLE(output, newFlags)               // flags (no data descriptor)
            writeShortLE(output, info.method)            // method
            writeShortLE(output, info.time)              // time在
            writeShortLE(output, info.date)              // date
            writeIntLE(output, info.crc.toInt())         // crc32
            writeIntLE(output, info.compressedSize.toInt()) // compressed size
            writeIntLE(output, info.uncompressedSize.toInt()) // uncompressed size
            writeShortLE(output, info.nameBytes.size)    // name length
            writeShortLE(output, newExtraLen)             // extra length

            output.write(info.nameBytes)
            if (newExtraLen > 0) output.write(ByteArray(newExtraLen))

            // Skip to data in input: after local header + name + extra
            val dataStart = info.localOffset + 30 + localNameLen + localExtraLen
            input.seek(dataStart)

            // Copy compressed data (use size from central directory!)
            copyBytes(input, output, info.compressedSize)

            newEntries.add(NewEntry(info, newLocalOffset))
        }

        // Write central directory
        val newCdOffset = output.filePointer
        for (ne in newEntries) {
            val info = ne.info
            writeIntLE(output, 0x02014b50) // signature
            writeShortLE(output, 20)       // version made by
            writeShortLE(output, 20)       // version needed
            writeShortLE(output, info.flags and 0xFFF7.toInt()) // flags (clear bit 3)
            writeShortLE(output, info.method)
            writeShortLE(output, info.time)
            writeShortLE(output, info.date)
            writeIntLE(output, info.crc.toInt())
            writeIntLE(output, info.compressedSize.toInt())
            writeIntLE(output, info.uncompressedSize.toInt())
            writeShortLE(output, info.nameBytes.size)
            writeShortLE(output, 0) // extra len in CD
            writeShortLE(output, 0) // comment len
            writeShortLE(output, 0) // disk number
            writeShortLE(output, 0) // internal attrs
            writeIntLE(output, info.externalAttrs)
            writeIntLE(output, ne.newLocalOffset.toInt())
            output.write(info.nameBytes)
        }

        // Write EOCD
        val cdSize2 = output.filePointer - newCdOffset
        writeIntLE(output, 0x06054b50)
        writeShortLE(output, 0) // disk number
        writeShortLE(output, 0) // cd disk
        writeShortLE(output, newEntries.size)
        writeShortLE(output, newEntries.size)
        writeIntLE(output, cdSize2.toInt())
        writeIntLE(output, newCdOffset.toInt())
        writeShortLE(output, 0) // comment length

        Log.d(TAG, "ZipAligner: aligned ${output.filePointer} bytes, ${newEntries.size} entries")
        output.close()
        input.close()
    }

    private fun findEOCD(raf: RandomAccessFile): Long {
        val len = raf.length()
        val start = maxOf(0, len - 65557)
        val buf = ByteArray(minOf(len - start, 65557).toInt())
        raf.seek(start); raf.readFully(buf)
        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i+1] == 0x4B.toByte()
                && buf[i+2] == 0x05.toByte() && buf[i+3] == 0x06.toByte())
                return start + i
        }
        throw IllegalStateException("EOCD not found")
    }

    private fun copyBytes(input: RandomAccessFile, output: RandomAccessFile, count: Long) {
        val buf = ByteArray(32768); var rem = count
        while (rem > 0) {
            val n = minOf(rem, buf.size.toLong()).toInt()
            input.readFully(buf, 0, n); output.write(buf, 0, n); rem -= n
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
        raf.write((v shr 16) and 0xFF); raf.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
    }
}
