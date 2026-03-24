package com.baozi.laninjector.injection

import android.util.Log
import java.io.ByteArrayOutputStream

class ManifestPatcher {

    companion object {
        private const val TAG = "LanInjector"

        // Binary XML chunk types
        private const val CHUNK_STRING_POOL = 0x001C0001
        private const val CHUNK_RESOURCE_IDS = 0x00080180
        private const val CHUNK_START_NAMESPACE = 0x00100100
        private const val CHUNK_END_NAMESPACE = 0x00100101
        private const val CHUNK_START_TAG = 0x00100102
        private const val CHUNK_END_TAG = 0x00100103

        // Resource IDs for android: attributes
        private const val RES_ANDROID_NAME = 0x01010003
        private const val RES_ANDROID_EXPORTED = 0x01010010
        private const val RES_ANDROID_AUTHORITIES = 0x01010018

        // Typed value types
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_BOOLEAN = 0x12

        private const val PROVIDER_CLASS = "com.baozi.laninjector.payload.PayloadProvider"
        private const val PERMISSION_NAME = "android.permission.SYSTEM_ALERT_WINDOW"
    }

    /**
     * Patch the binary AndroidManifest.xml to add a <provider> element
     * inside <application>.
     */
    fun patchManifest(manifestBytes: ByteArray, packageName: String): ByteArray {
        return try {
            val result = addProviderToManifest(manifestBytes, packageName)
            Log.d(TAG, "ManifestPatcher: patched ${manifestBytes.size} -> ${result.size} bytes")
            result
        } catch (e: Exception) {
            Log.e(TAG, "ManifestPatcher: failed to patch", e)
            throw RuntimeException("Failed to patch manifest: $e")
        }
    }

    private fun addProviderToManifest(data: ByteArray, packageName: String): ByteArray {
        val authorities = "$packageName.laninjector_init"

        // ===== Phase 1: Parse existing structures =====
        val fileHeader = parseFileHeader(data)
        val stringPool = parseStringPool(data, 8)
        val resIdTable = parseResourceIdTable(data, 8 + stringPool.chunkSize)
        val xmlStart = 8 + stringPool.chunkSize + resIdTable.chunkSize

        // ===== Phase 2: Find/add needed strings =====

        // Build string -> index map from existing pool
        val stringMap = mutableMapOf<String, Int>()
        for (i in stringPool.strings.indices) {
            stringMap[stringPool.strings[i]] = i
        }

        // Strings we need - find existing or add new
        val androidNsIdx = stringMap["http://schemas.android.com/apk/res/android"]
            ?: throw IllegalStateException("Android namespace not found in string pool")

        // Attribute names - must be in resource ID mapped range
        val nameIdx = findOrAddAttrString(stringPool, resIdTable, stringMap, "name", RES_ANDROID_NAME)
        val exportedIdx = findOrAddAttrString(stringPool, resIdTable, stringMap, "exported", RES_ANDROID_EXPORTED)
        val authoritiesIdx = findOrAddAttrString(stringPool, resIdTable, stringMap, "authorities", RES_ANDROID_AUTHORITIES)

        // Tag name and value strings - appended at end (no resource ID needed)
        val providerTagIdx = findOrAddString(stringPool, stringMap, "provider")
        val classNameIdx = findOrAddString(stringPool, stringMap, PROVIDER_CLASS)
        val authValueIdx = findOrAddString(stringPool, stringMap, authorities)

        // Permission-related strings
        val hasPermission = stringMap.containsKey(PERMISSION_NAME)
        val usesPermissionTagIdx = findOrAddString(stringPool, stringMap, "uses-permission")
        val permissionValueIdx = findOrAddString(stringPool, stringMap, PERMISSION_NAME)

        // Build old->new index mapping for any inserted strings
        val indexMapping = buildIndexMapping(stringPool)

        // Apply mapping to get final indices (new strings already at correct position)
        fun mapIdx(idx: Int): Int = if (idx < indexMapping.size) indexMapping[idx] else idx

        val finalAndroidNsIdx = mapIdx(androidNsIdx)
        val finalNameIdx = mapIdx(nameIdx)
        val finalExportedIdx = mapIdx(exportedIdx)
        val finalAuthoritiesIdx = mapIdx(authoritiesIdx)
        val finalProviderTagIdx = mapIdx(providerTagIdx)
        val finalClassNameIdx = mapIdx(classNameIdx)
        val finalAuthValueIdx = mapIdx(authValueIdx)
        val finalUsesPermTagIdx = mapIdx(usesPermissionTagIdx)
        val finalPermValueIdx = mapIdx(permissionValueIdx)

        // ===== Phase 3: Rebuild binary =====
        val output = ByteArrayOutputStream()

        // File header (placeholder)
        writeIntLE(output, fileHeader.magic)
        writeIntLE(output, 0) // placeholder for file size

        // Serialize new string pool
        val newStringPoolBytes = serializeStringPool(stringPool)
        output.write(newStringPoolBytes)

        // Serialize new resource ID table
        val newResIdBytes = serializeResourceIdTable(resIdTable)
        output.write(newResIdBytes)

        // ===== Phase 4: Copy XML chunks with index adjustment and element insertion =====
        var pos = xmlStart
        var applicationInserted = false
        var permissionInserted = hasPermission // skip if already exists

        while (pos < data.size) {
            val chunkType = readIntLE(data, pos)
            val chunkSize = readIntLE(data, pos + 4)
            if (chunkSize <= 0) break

            // Copy chunk with adjusted string indices
            val adjustedChunk = adjustChunkStringIndices(data, pos, chunkSize, chunkType, indexMapping)
            output.write(adjustedChunk)

            // After <manifest> START_TAG, insert <uses-permission> element
            if (!permissionInserted && chunkType == CHUNK_START_TAG) {
                val tagNameIdx = readIntLE(data, pos + 20)
                val originalTagName = stringPool.originalStrings.getOrNull(tagNameIdx)
                if (originalTagName == "manifest") {
                    permissionInserted = true
                    Log.d(TAG, "ManifestPatcher: inserting <uses-permission> after <manifest>")
                    writeUsesPermissionElement(output,
                        finalUsesPermTagIdx, finalAndroidNsIdx,
                        finalNameIdx, finalPermValueIdx)
                }
            }

            // After <application> START_TAG, insert <provider> element
            if (!applicationInserted && chunkType == CHUNK_START_TAG) {
                val tagNameIdx = readIntLE(data, pos + 20) // name index in original
                val originalTagName = stringPool.originalStrings.getOrNull(tagNameIdx)
                if (originalTagName == "application") {
                    applicationInserted = true
                    Log.d(TAG, "ManifestPatcher: inserting <provider> after <application>")

                    // Insert provider START_TAG
                    writeProviderStartTag(output,
                        finalProviderTagIdx, finalAndroidNsIdx,
                        finalNameIdx, finalClassNameIdx,
                        finalExportedIdx,
                        finalAuthoritiesIdx, finalAuthValueIdx)

                    // Insert provider END_TAG
                    writeProviderEndTag(output, finalProviderTagIdx)
                }
            }

            pos += chunkSize
        }

        val result = output.toByteArray()

        // Fix file size
        writeIntLE(result, 4, result.size)

        return result
    }

    // ===== String pool structures =====

    private data class StringPoolInfo(
        val chunkSize: Int,
        val strings: MutableList<String>,
        val originalStrings: List<String>,  // before modifications
        val isUtf8: Boolean,
        val styleCount: Int,
        val styleData: ByteArray,
        val insertionPoints: MutableList<Int> = mutableListOf(), // indices where strings were inserted
        val rawData: ByteArray
    )

    private data class ResourceIdTableInfo(
        val chunkSize: Int,
        val ids: MutableList<Int>
    )

    private data class FileHeader(val magic: Int, val fileSize: Int)

    private fun parseFileHeader(data: ByteArray): FileHeader {
        return FileHeader(readIntLE(data, 0), readIntLE(data, 4))
    }

    private fun parseStringPool(data: ByteArray, offset: Int): StringPoolInfo {
        var pos = offset
        val chunkType = readIntLE(data, pos); pos += 4
        val chunkSize = readIntLE(data, pos); pos += 4
        val stringCount = readIntLE(data, pos); pos += 4
        val styleCount = readIntLE(data, pos); pos += 4
        val flags = readIntLE(data, pos); pos += 4
        val stringsOffset = readIntLE(data, pos); pos += 4
        val stylesOffset = readIntLE(data, pos); pos += 4

        val isUtf8 = (flags and (1 shl 8)) != 0

        // Read string offsets
        val stringOffsets = IntArray(stringCount) { readIntLE(data, pos + it * 4) }
        pos += stringCount * 4

        // Skip style offsets
        pos += styleCount * 4

        // Parse strings
        val stringDataStart = offset + stringsOffset
        val strings = mutableListOf<String>()
        for (i in 0 until stringCount) {
            val strPos = stringDataStart + stringOffsets[i]
            strings.add(readPoolString(data, strPos, isUtf8))
        }

        // Extract style data
        val styleData = if (stylesOffset != 0) {
            val styleStart = offset + stylesOffset
            val styleEnd = offset + chunkSize
            data.copyOfRange(styleStart, styleEnd)
        } else {
            ByteArray(0)
        }

        return StringPoolInfo(
            chunkSize = chunkSize,
            strings = strings.toMutableList(),
            originalStrings = strings.toList(),
            isUtf8 = isUtf8,
            styleCount = styleCount,
            styleData = styleData,
            rawData = data.copyOfRange(offset, offset + chunkSize)
        )
    }

    private fun readPoolString(data: ByteArray, pos: Int, isUtf8: Boolean): String {
        return try {
            if (isUtf8) {
                var p = pos
                // char length
                var charLen = data[p].toInt() and 0xFF
                p++
                if (charLen and 0x80 != 0) {
                    charLen = ((charLen and 0x7F) shl 8) or (data[p].toInt() and 0xFF)
                    p++
                }
                // byte length
                var byteLen = data[p].toInt() and 0xFF
                p++
                if (byteLen and 0x80 != 0) {
                    byteLen = ((byteLen and 0x7F) shl 8) or (data[p].toInt() and 0xFF)
                    p++
                }
                String(data, p, byteLen, Charsets.UTF_8)
            } else {
                var p = pos
                var charLen = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                if (charLen and 0x8000 != 0) {
                    charLen = ((charLen and 0x7FFF) shl 16) or
                            ((data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8))
                    p += 2
                }
                val bytes = ByteArray(charLen * 2)
                System.arraycopy(data, p, bytes, 0, bytes.size)
                String(bytes, Charsets.UTF_16LE)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseResourceIdTable(data: ByteArray, offset: Int): ResourceIdTableInfo {
        val chunkType = readIntLE(data, offset)
        if (chunkType != CHUNK_RESOURCE_IDS) {
            // No resource ID table
            return ResourceIdTableInfo(0, mutableListOf())
        }
        val chunkSize = readIntLE(data, offset + 4)
        val count = (chunkSize - 8) / 4
        val ids = mutableListOf<Int>()
        for (i in 0 until count) {
            ids.add(readIntLE(data, offset + 8 + i * 4))
        }
        return ResourceIdTableInfo(chunkSize, ids)
    }

    // ===== Find/add strings =====

    /**
     * Find an attribute name string in the resource-ID-mapped region.
     * If found, return its index. If not, insert it at the end of the
     * resource-ID-mapped region and add the resource ID.
     */
    private fun findOrAddAttrString(
        pool: StringPoolInfo,
        resIdTable: ResourceIdTableInfo,
        stringMap: MutableMap<String, Int>,
        attrName: String,
        resourceId: Int
    ): Int {
        // Check if this resource ID already exists in the table
        for (i in resIdTable.ids.indices) {
            if (resIdTable.ids[i] == resourceId) {
                return i // Already mapped, return existing index
            }
        }

        // Check if the string exists but isn't in the resource ID table
        val existingIdx = stringMap[attrName]
        if (existingIdx != null && existingIdx < resIdTable.ids.size) {
            // String exists in the mapped range, check its resource ID
            return existingIdx
        }

        // Need to insert at the end of the resource-ID-mapped region
        val insertIdx = resIdTable.ids.size
        pool.strings.add(insertIdx, attrName)
        pool.insertionPoints.add(insertIdx)
        resIdTable.ids.add(resourceId)

        // Update string map for subsequent lookups
        // Shift all existing indices >= insertIdx
        val updatedMap = mutableMapOf<String, Int>()
        for ((k, v) in stringMap) {
            updatedMap[k] = if (v >= insertIdx) v + 1 else v
        }
        updatedMap[attrName] = insertIdx
        stringMap.clear()
        stringMap.putAll(updatedMap)

        Log.d(TAG, "ManifestPatcher: inserted attr '$attrName' at index $insertIdx, resId=0x${resourceId.toString(16)}")
        return insertIdx
    }

    /**
     * Find or append a non-attribute string at the end of the pool.
     */
    private fun findOrAddString(
        pool: StringPoolInfo,
        stringMap: MutableMap<String, Int>,
        str: String
    ): Int {
        stringMap[str]?.let { return it }

        val idx = pool.strings.size
        pool.strings.add(str)
        stringMap[str] = idx
        Log.d(TAG, "ManifestPatcher: appended string '$str' at index $idx")
        return idx
    }

    /**
     * Build mapping from original string index to new string index,
     * accounting for any inserted strings.
     */
    private fun buildIndexMapping(pool: StringPoolInfo): IntArray {
        val originalCount = pool.originalStrings.size
        val mapping = IntArray(pool.strings.size) { it } // identity by default

        // For each original string, calculate its new position
        // Insertion points shift later strings
        val sortedInsertions = pool.insertionPoints.sorted()

        // Build mapping for original indices
        val result = IntArray(pool.strings.size)
        for (i in result.indices) {
            result[i] = i
        }

        // The mapping needs to map OLD index to NEW index
        // insertionPoints tell us where new strings were inserted
        // Original string at old index i moves to i + (number of insertions <= i's new position)
        val oldToNew = IntArray(originalCount)
        for (oldIdx in 0 until originalCount) {
            var newIdx = oldIdx
            for (insertPoint in sortedInsertions) {
                if (insertPoint <= newIdx) {
                    newIdx++
                }
            }
            oldToNew[oldIdx] = newIdx
        }

        // Return a mapping array where index = old index, value = new index
        // For new strings (appended at end), their index IS the correct final index
        // We need a mapping that covers ALL final indices
        // The adjustChunkStringIndices will use this to map old->new
        return oldToNew
    }

    // ===== Serialization =====

    private fun serializeStringPool(pool: StringPoolInfo): ByteArray {
        val out = ByteArrayOutputStream()

        // Encode all strings
        val encodedStrings = pool.strings.map { str ->
            if (pool.isUtf8) encodeUtf8String(str) else encodeUtf16String(str)
        }

        // Calculate offsets
        val stringOffsets = IntArray(pool.strings.size)
        var offset = 0
        for (i in pool.strings.indices) {
            stringOffsets[i] = offset
            offset += encodedStrings[i].size
        }
        val totalStringDataSize = offset

        // Header: 28 bytes
        // String offsets: 4 * stringCount
        // Style offsets: 4 * styleCount
        // String data
        // Style data
        val headerSize = 28
        val offsetsSize = 4 * pool.strings.size + 4 * pool.styleCount
        val stringsOffset = headerSize + offsetsSize
        val stylesOffset = if (pool.styleData.isNotEmpty()) {
            stringsOffset + totalStringDataSize
        } else {
            0
        }
        val chunkDataSize = stringsOffset + totalStringDataSize + pool.styleData.size
        // Pad to 4-byte boundary
        val padding = (4 - (chunkDataSize % 4)) % 4
        val chunkSize = chunkDataSize + padding

        // Write header
        writeIntLE(out, CHUNK_STRING_POOL)
        writeIntLE(out, chunkSize)
        writeIntLE(out, pool.strings.size)
        writeIntLE(out, pool.styleCount)
        writeIntLE(out, if (pool.isUtf8) (1 shl 8) else 0)
        writeIntLE(out, stringsOffset)
        writeIntLE(out, stylesOffset)

        // Write string offsets
        for (off in stringOffsets) {
            writeIntLE(out, off)
        }

        // Write style offsets (from original data if any)
        // Style offsets need recalculating if present, but typically manifests don't have styles
        if (pool.styleCount > 0) {
            // Copy original style offsets (they're relative and don't change)
            val origStyleOffsetStart = 28 + pool.originalStrings.size * 4
            for (i in 0 until pool.styleCount) {
                writeIntLE(out, readIntLE(pool.rawData, origStyleOffsetStart + i * 4))
            }
        }

        // Write string data
        for (encoded in encodedStrings) {
            out.write(encoded)
        }

        // Write style data
        out.write(pool.styleData)

        // Pad to 4-byte boundary
        repeat(padding) { out.write(0) }

        return out.toByteArray()
    }

    private fun serializeResourceIdTable(table: ResourceIdTableInfo): ByteArray {
        if (table.ids.isEmpty()) return ByteArray(0)

        val out = ByteArrayOutputStream()
        val chunkSize = 8 + table.ids.size * 4
        writeIntLE(out, CHUNK_RESOURCE_IDS)
        writeIntLE(out, chunkSize)
        for (id in table.ids) {
            writeIntLE(out, id)
        }
        return out.toByteArray()
    }

    // ===== XML chunk adjustment =====

    private fun adjustChunkStringIndices(
        data: ByteArray, offset: Int, chunkSize: Int,
        chunkType: Int, mapping: IntArray
    ): ByteArray {
        val chunk = data.copyOfRange(offset, offset + chunkSize)

        fun mapIdx(idx: Int): Int {
            return if (idx >= 0 && idx < mapping.size) mapping[idx] else idx
        }

        when (chunkType) {
            CHUNK_START_NAMESPACE, CHUNK_END_NAMESPACE -> {
                // bytes 16-19: prefix, 20-23: uri
                writeIntLE(chunk, 16, mapIdx(readIntLE(chunk, 16)))
                writeIntLE(chunk, 20, mapIdx(readIntLE(chunk, 20)))
            }

            CHUNK_START_TAG -> {
                // bytes 16-19: namespace, 20-23: name
                val nsIdx = readIntLE(chunk, 16)
                if (nsIdx >= 0) writeIntLE(chunk, 16, mapIdx(nsIdx))
                writeIntLE(chunk, 20, mapIdx(readIntLE(chunk, 20)))

                // attributes start at offset 36
                val attrCount = readShortLE(chunk, 28)
                for (i in 0 until attrCount) {
                    val attrOffset = 36 + i * 20
                    // namespace (4), name (4), rawValue (4), typedValue(8)
                    val attrNs = readIntLE(chunk, attrOffset)
                    if (attrNs >= 0) writeIntLE(chunk, attrOffset, mapIdx(attrNs))
                    writeIntLE(chunk, attrOffset + 4, mapIdx(readIntLE(chunk, attrOffset + 4)))

                    val rawVal = readIntLE(chunk, attrOffset + 8)
                    if (rawVal >= 0) writeIntLE(chunk, attrOffset + 8, mapIdx(rawVal))

                    // typed value: if type is string (0x03), map the data field
                    val typedType = chunk[attrOffset + 15].toInt() and 0xFF
                    if (typedType == TYPE_STRING) {
                        val typedData = readIntLE(chunk, attrOffset + 16)
                        if (typedData >= 0) writeIntLE(chunk, attrOffset + 16, mapIdx(typedData))
                    }
                }
            }

            CHUNK_END_TAG -> {
                // bytes 16-19: namespace, 20-23: name
                val nsIdx = readIntLE(chunk, 16)
                if (nsIdx >= 0) writeIntLE(chunk, 16, mapIdx(nsIdx))
                writeIntLE(chunk, 20, mapIdx(readIntLE(chunk, 20)))
            }
        }

        return chunk
    }

    // ===== Provider element generation =====

    private fun writeProviderStartTag(
        out: ByteArrayOutputStream,
        providerTagIdx: Int, androidNsIdx: Int,
        nameAttrIdx: Int, classNameIdx: Int,
        exportedAttrIdx: Int,
        authoritiesAttrIdx: Int, authValueIdx: Int
    ) {
        // 3 attributes, sorted by resource ID:
        // android:name (0x01010003), android:exported (0x01010010), android:authorities (0x01010018)
        val attrCount = 3
        val chunkSize = 36 + attrCount * 20 // 36 = 16 header + 20 body before attrs

        writeIntLE(out, CHUNK_START_TAG)        // chunk type
        writeIntLE(out, chunkSize)               // chunk size
        writeIntLE(out, 1)                       // line number
        writeIntLE(out, -1)                      // comment = 0xFFFFFFFF

        writeIntLE(out, -1)                      // namespace URI (none on element)
        writeIntLE(out, providerTagIdx)           // name = "provider"
        writeShortLE(out, 0x0014)                // attributeStart
        writeShortLE(out, 0x0014)                // attributeSize
        writeShortLE(out, attrCount)             // attributeCount
        writeShortLE(out, 0)                     // idIndex
        writeShortLE(out, 0)                     // classIndex
        writeShortLE(out, 0)                     // styleIndex

        // Attr 1: android:name = PROVIDER_CLASS (TYPE_STRING)
        writeAttr(out, androidNsIdx, nameAttrIdx, classNameIdx, TYPE_STRING, classNameIdx)

        // Attr 2: android:exported = false (TYPE_INT_BOOLEAN)
        writeAttr(out, androidNsIdx, exportedAttrIdx, -1, TYPE_INT_BOOLEAN, 0)

        // Attr 3: android:authorities = authorities value (TYPE_STRING)
        writeAttr(out, androidNsIdx, authoritiesAttrIdx, authValueIdx, TYPE_STRING, authValueIdx)
    }

    private fun writeProviderEndTag(out: ByteArrayOutputStream, providerTagIdx: Int) {
        writeIntLE(out, CHUNK_END_TAG)  // chunk type
        writeIntLE(out, 24)              // chunk size
        writeIntLE(out, 1)               // line number
        writeIntLE(out, -1)              // comment

        writeIntLE(out, -1)              // namespace URI
        writeIntLE(out, providerTagIdx)   // name = "provider"
    }

    /**
     * Write <uses-permission android:name="..."/> as START_TAG + END_TAG.
     */
    private fun writeUsesPermissionElement(
        out: ByteArrayOutputStream,
        tagIdx: Int, androidNsIdx: Int,
        nameAttrIdx: Int, permValueIdx: Int
    ) {
        // START_TAG with 1 attribute: android:name = permission string
        val attrCount = 1
        val chunkSize = 36 + attrCount * 20

        writeIntLE(out, CHUNK_START_TAG)
        writeIntLE(out, chunkSize)
        writeIntLE(out, 1)                // line number
        writeIntLE(out, -1)               // comment

        writeIntLE(out, -1)               // namespace URI (none on element)
        writeIntLE(out, tagIdx)           // name = "uses-permission"
        writeShortLE(out, 0x0014)         // attributeStart
        writeShortLE(out, 0x0014)         // attributeSize
        writeShortLE(out, attrCount)      // attributeCount
        writeShortLE(out, 0)              // idIndex
        writeShortLE(out, 0)              // classIndex
        writeShortLE(out, 0)              // styleIndex

        // android:name = permission value (TYPE_STRING)
        writeAttr(out, androidNsIdx, nameAttrIdx, permValueIdx, TYPE_STRING, permValueIdx)

        // END_TAG
        writeIntLE(out, CHUNK_END_TAG)
        writeIntLE(out, 24)
        writeIntLE(out, 1)
        writeIntLE(out, -1)

        writeIntLE(out, -1)
        writeIntLE(out, tagIdx)           // name = "uses-permission"
    }

    private fun writeAttr(
        out: ByteArrayOutputStream,
        nsIdx: Int, nameIdx: Int, rawValueIdx: Int,
        typedType: Int, typedData: Int
    ) {
        writeIntLE(out, nsIdx)            // namespace
        writeIntLE(out, nameIdx)          // name
        writeIntLE(out, rawValueIdx)      // raw value (-1 for non-string)
        writeShortLE(out, 0x08)           // typed value size
        out.write(0)                       // typed value zero byte
        out.write(typedType)               // typed value type
        writeIntLE(out, typedData)         // typed value data
    }

    // ===== String encoding =====

    private fun encodeUtf8String(str: String): ByteArray {
        val utf8Bytes = str.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        // Char length
        if (str.length > 0x7F) {
            baos.write(((str.length shr 8) and 0x7F) or 0x80)
            baos.write(str.length and 0xFF)
        } else {
            baos.write(str.length and 0xFF)
        }
        // Byte length
        if (utf8Bytes.size > 0x7F) {
            baos.write(((utf8Bytes.size shr 8) and 0x7F) or 0x80)
            baos.write(utf8Bytes.size and 0xFF)
        } else {
            baos.write(utf8Bytes.size and 0xFF)
        }
        baos.write(utf8Bytes)
        baos.write(0) // null terminator
        return baos.toByteArray()
    }

    private fun encodeUtf16String(str: String): ByteArray {
        val baos = ByteArrayOutputStream()
        if (str.length > 0x7FFF) {
            val high = ((str.length shr 16) and 0x7FFF) or 0x8000
            baos.write(high and 0xFF)
            baos.write((high shr 8) and 0xFF)
            baos.write(str.length and 0xFF)
            baos.write((str.length shr 8) and 0xFF)
        } else {
            baos.write(str.length and 0xFF)
            baos.write((str.length shr 8) and 0xFF)
        }
        for (c in str) {
            baos.write(c.code and 0xFF)
            baos.write((c.code shr 8) and 0xFF)
        }
        baos.write(0)
        baos.write(0) // null terminator
        return baos.toByteArray()
    }

    // ===== Low-level I/O =====

    private fun readIntLE(data: ByteArray, pos: Int): Int {
        return (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(data: ByteArray, pos: Int): Int {
        return (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeIntLE(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
    }

    private fun writeIntLE(data: ByteArray, pos: Int, value: Int) {
        data[pos] = (value and 0xFF).toByte()
        data[pos + 1] = ((value shr 8) and 0xFF).toByte()
        data[pos + 2] = ((value shr 16) and 0xFF).toByte()
        data[pos + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
