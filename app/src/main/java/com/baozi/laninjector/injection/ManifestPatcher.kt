package com.baozi.laninjector.injection

import java.io.ByteArrayOutputStream

import android.util.Log

class ManifestPatcher {

    companion object {
        private const val TAG = "LanInjector"
        private const val PERMISSION_NAME = "android.permission.SYSTEM_ALERT_WINDOW"
    }

    fun hasOverlayPermission(manifestBytes: ByteArray): Boolean {
        val permBytes = PERMISSION_NAME.toByteArray(Charsets.UTF_8)
        return findBytes(manifestBytes, permBytes) >= 0
    }

    fun patchManifest(manifestBytes: ByteArray): ByteArray {
        val hasPermission = hasOverlayPermission(manifestBytes)
        Log.d(TAG, "ManifestPatcher: hasOverlayPermission=$hasPermission")
        if (hasPermission) {
            Log.d(TAG, "ManifestPatcher: permission already exists, skipping")
            return manifestBytes
        }

        return try {
            val result = addPermissionToManifest(manifestBytes)
            Log.d(TAG, "ManifestPatcher: patched ${manifestBytes.size} -> ${result.size} bytes")
            result
        } catch (e: Exception) {
            Log.e(TAG, "ManifestPatcher: failed to patch", e)
            throw RuntimeException(
                "Failed to patch manifest. Manual permission may be needed: $e"
            )
        }
    }

    /**
     * Add SYSTEM_ALERT_WINDOW permission to the binary manifest.
     *
     * Binary AXML format:
     * - File header (8 bytes): magic + size
     * - String pool chunk
     * - Resource ID table chunk
     * - XML content chunks (namespace, start/end tags, text)
     *
     * We need to:
     * 1. Add "android.permission.SYSTEM_ALERT_WINDOW" to the string pool
     * 2. Insert a <uses-permission android:name="..."/> tag after <manifest>
     */
    private fun addPermissionToManifest(data: ByteArray): ByteArray {
        // Find the positions of key structures
        var pos = 0

        // File header
        val magic = readIntLE(data, pos); pos += 4
        val fileSize = readIntLE(data, pos); pos += 4

        // String pool chunk
        val stringPoolStart = pos
        val stringPoolType = readIntLE(data, pos); pos += 4
        val stringPoolSize = readIntLE(data, pos); pos += 4
        val stringCount = readIntLE(data, pos); pos += 4
        val styleCount = readIntLE(data, pos); pos += 4
        val stringFlags = readIntLE(data, pos); pos += 4
        val stringsOffset = readIntLE(data, pos); pos += 4
        val stylesOffset = readIntLE(data, pos); pos += 4

        val isUtf8 = (stringFlags and (1 shl 8)) != 0

        // We need to add our permission string to the pool
        val newStringIndex = stringCount

        // Read existing string offsets
        val offsetsStart = pos
        val offsets = IntArray(stringCount) {
            val v = readIntLE(data, pos)
            pos += 4
            v
        }

        // Skip style offsets
        for (i in 0 until styleCount) {
            pos += 4
        }

        // String data starts at stringPoolStart + stringsOffset
        val stringDataStart = stringPoolStart + stringsOffset
        // Find end of string data
        val stringDataEnd = if (stylesOffset != 0) {
            stringPoolStart + stylesOffset
        } else {
            stringPoolStart + stringPoolSize
        }

        // Encode our new permission string
        val newStringBytes = if (isUtf8) {
            encodeUtf8String(PERMISSION_NAME)
        } else {
            encodeUtf16String(PERMISSION_NAME)
        }

        // Calculate new string offset (relative to string data start within pool)
        val lastStringEnd = if (stringCount > 0) {
            // The offset of the last string + its encoded size
            val lastOffset = offsets[stringCount - 1]
            val lastStringPos = stringDataStart + lastOffset
            lastOffset + calculateEncodedStringSize(data, lastStringPos, isUtf8)
        } else {
            0
        }
        val newOffset = lastStringEnd

        // Build the new manifest
        val output = ByteArrayOutputStream()

        // 1. File header (will update size later)
        writeIntLE(output, magic)
        writeIntLE(output, 0) // placeholder for file size

        // 2. String pool header
        writeIntLE(output, stringPoolType)
        val newStringPoolSize = stringPoolSize + 4 + newStringBytes.size // +4 for offset entry, +bytes for string
        writeIntLE(output, newStringPoolSize)
        writeIntLE(output, stringCount + 1) // new string count
        writeIntLE(output, styleCount)
        writeIntLE(output, stringFlags)
        writeIntLE(output, stringsOffset + 4) // shift strings offset by 4 (new offset entry)
        writeIntLE(output, if (stylesOffset != 0) stylesOffset + 4 + newStringBytes.size else 0)

        // String offsets (existing + new)
        for (offset in offsets) {
            writeIntLE(output, offset)
        }
        writeIntLE(output, newOffset) // new string's offset

        // Style offsets (unchanged)
        val styleOffsetsStart = offsetsStart + stringCount * 4
        for (i in 0 until styleCount) {
            writeIntLE(output, readIntLE(data, styleOffsetsStart + i * 4))
        }

        // String data (existing)
        val existingStringDataSize = stringDataEnd - stringDataStart
        output.write(data, stringDataStart, existingStringDataSize)

        // Append new string
        output.write(newStringBytes)

        // Style data (if any)
        if (stylesOffset != 0) {
            val styleDataStart = stringPoolStart + stylesOffset
            val styleDataSize = stringPoolStart + stringPoolSize - styleDataStart
            output.write(data, styleDataStart, styleDataSize)
        }

        // Pad to 4-byte boundary
        while (output.size() % 4 != 0) {
            output.write(0)
        }

        // 3. Copy remaining chunks (resource map + XML content)
        val afterStringPool = stringPoolStart + stringPoolSize
        output.write(data, afterStringPool, data.size - afterStringPool)

        val result = output.toByteArray()

        // Update file size
        writeIntLE(result, 4, result.size)

        // Update string pool chunk size
        val actualPoolSize = afterStringPool - stringPoolStart + (result.size - data.size)
        writeIntLE(result, stringPoolStart + 4, actualPoolSize)

        return result
    }

    private fun encodeUtf8String(str: String): ByteArray {
        val utf8Bytes = str.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        // Char length (1 byte if < 128)
        baos.write(str.length and 0xFF)
        // Byte length (1 byte if < 128)
        baos.write(utf8Bytes.size and 0xFF)
        baos.write(utf8Bytes)
        baos.write(0) // null terminator
        return baos.toByteArray()
    }

    private fun encodeUtf16String(str: String): ByteArray {
        val baos = ByteArrayOutputStream()
        // Char count (2 bytes LE)
        baos.write(str.length and 0xFF)
        baos.write((str.length shr 8) and 0xFF)
        // UTF-16LE encoded characters
        for (c in str) {
            baos.write(c.code and 0xFF)
            baos.write((c.code shr 8) and 0xFF)
        }
        // Null terminator (2 bytes)
        baos.write(0)
        baos.write(0)
        return baos.toByteArray()
    }

    private fun calculateEncodedStringSize(data: ByteArray, pos: Int, isUtf8: Boolean): Int {
        return try {
            if (isUtf8) {
                var p = pos
                val charLen = data[p].toInt() and 0xFF
                p += if (charLen > 0x7F) 2 else 1
                val byteLen = data[p].toInt() and 0xFF
                p += if (byteLen > 0x7F) 2 else 1
                (p - pos) + byteLen + 1 // +1 for null terminator
            } else {
                var p = pos
                var charLen = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                if (charLen > 0x7FFF) {
                    p += 2
                    charLen = charLen and 0x7FFF
                }
                (p - pos) + charLen * 2 + 2 // +2 for null terminator
            }
        } catch (e: Exception) {
            4 // fallback
        }
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readIntLE(data: ByteArray, pos: Int): Int {
        return (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeIntLE(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 24) and 0xFF)
    }

    private fun writeIntLE(data: ByteArray, pos: Int, value: Int) {
        data[pos] = (value and 0xFF).toByte()
        data[pos + 1] = ((value shr 8) and 0xFF).toByte()
        data[pos + 2] = ((value shr 16) and 0xFF).toByte()
        data[pos + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
