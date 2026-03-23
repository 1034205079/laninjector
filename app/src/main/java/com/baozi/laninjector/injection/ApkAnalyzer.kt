package com.baozi.laninjector.injection

import android.content.Context
import android.util.Log
import com.baozi.laninjector.model.ApkInfo
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ApkAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "LanInjector"
    }

    fun analyze(apkInputStream: InputStream): ApkInfo {
        Log.d(TAG, "ApkAnalyzer: starting analysis")
        val zipStream = ZipInputStream(apkInputStream)
        var manifestBytes: ByteArray? = null
        var arscBytes: ByteArray? = null
        val entries = mutableListOf<String>()
        var dexCount = 0

        var entry = zipStream.nextEntry
        while (entry != null) {
            val name = entry.name
            entries.add(name)

            if (name == "AndroidManifest.xml") {
                val baos = java.io.ByteArrayOutputStream()
                zipStream.copyTo(baos)
                manifestBytes = baos.toByteArray()
            }

            if (name == "resources.arsc") {
                val baos = java.io.ByteArrayOutputStream()
                zipStream.copyTo(baos)
                arscBytes = baos.toByteArray()
            }

            if (name.matches(Regex("classes\\d*\\.dex"))) {
                dexCount++
            }

            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        zipStream.close()

        requireNotNull(manifestBytes) { "AndroidManifest.xml not found in APK" }
        Log.d(TAG, "ApkAnalyzer: manifest=${manifestBytes!!.size} bytes, arsc=${arscBytes?.size ?: 0} bytes, entries=${entries.size}, dexCount=$dexCount")

        // Parse binary manifest
        val manifestInfo = parseManifest(manifestBytes)

        // Detect locales: prefer resources.arsc, fallback to directory scanning
        val locales = if (arscBytes != null) {
            detectLocalesFromArsc(arscBytes)
        } else {
            detectLocalesFromEntries(entries)
        }
        Log.d(TAG, "ApkAnalyzer: pkg=${manifestInfo.first}, launcher=${manifestInfo.second}, locales=$locales")

        return ApkInfo(
            packageName = manifestInfo.first,
            launcherActivity = manifestInfo.second,
            locales = locales,
            dexCount = dexCount
        )
    }

    /**
     * Parse binary Android manifest to extract package name and launcher activity.
     * Uses Android's built-in binary XML parser via XmlResourceParser approach.
     */
    private fun parseManifest(manifestBytes: ByteArray): Pair<String, String> {
        // Use a simple binary XML parser
        val parser = BinaryXmlParser(manifestBytes)
        val result = parser.parse()

        val packageName = result.packageName
            ?: throw IllegalStateException("Could not find package name in manifest")
        val launcherActivity = result.launcherActivity
            ?: throw IllegalStateException("Could not find launcher activity in manifest")

        // Resolve relative activity names
        val fullActivityName = if (launcherActivity.startsWith(".")) {
            packageName + launcherActivity
        } else if (!launcherActivity.contains(".")) {
            "$packageName.$launcherActivity"
        } else {
            launcherActivity
        }

        return Pair(packageName, fullActivityName)
    }

    /**
     * Parse resources.arsc to extract locale configurations.
     * Scans for ResTable_type chunks (0x0201) and reads the locale from each config.
     */
    private fun detectLocalesFromArsc(arscBytes: ByteArray): List<String> {
        val locales = mutableSetOf<String>()
        var pos = 0

        // Scan through the entire arsc for ResTable_type chunks (0x0201)
        // These are nested inside Package chunks, so we do a linear scan
        while (pos + 8 < arscBytes.size) {
            val chunkType = readShortLE(arscBytes, pos)
            val chunkHeaderSize = readShortLE(arscBytes, pos + 2)
            val chunkSize = readIntLE(arscBytes, pos + 4)

            if (chunkSize < 8 || chunkSize > arscBytes.size - pos) {
                pos += 4
                continue
            }

            when (chunkType) {
                // RES_TABLE_TYPE = 0x0002 - top-level table, enter it
                0x0002 -> {
                    pos += 12 // skip header (type+headerSize+size+packageCount)
                    continue
                }
                // RES_TABLE_PACKAGE_TYPE = 0x0200 - package chunk, enter it
                0x0200 -> {
                    pos += chunkHeaderSize // skip package header, scan contents
                    continue
                }
                // RES_STRING_POOL_TYPE = 0x0001 - string pool, skip entirely
                0x0001 -> {
                    pos += chunkSize
                    continue
                }
                // RES_TABLE_TYPE_TYPE = 0x0201 - this has locale config!
                0x0201 -> {
                    // Header: type(2)+headerSize(2)+size(4) = 8
                    // Then: id(1)+res0(1)+res1(2)+entryCount(4)+entriesStart(4) = 12
                    // ResTable_config starts at offset 20 from chunk start
                    val configOffset = pos + 20
                    if (configOffset + 12 <= arscBytes.size) {
                        val configSize = readIntLE(arscBytes, configOffset)
                        if (configSize >= 12) {
                            val lang0 = arscBytes[configOffset + 8].toInt() and 0xFF
                            val lang1 = arscBytes[configOffset + 9].toInt() and 0xFF
                            val country0 = arscBytes[configOffset + 10].toInt() and 0xFF
                            val country1 = arscBytes[configOffset + 11].toInt() and 0xFF

                            if (lang0 in 0x61..0x7A && lang1 in 0x61..0x7A) { // lowercase a-z
                                val lang = "${lang0.toChar()}${lang1.toChar()}"
                                if (country0 in 0x41..0x5A && country1 in 0x41..0x5A) { // uppercase A-Z
                                    locales.add("$lang-r${country0.toChar()}${country1.toChar()}")
                                } else if (country0 == 0 && country1 == 0) {
                                    locales.add(lang)
                                }
                            }
                        }
                    }
                    pos += chunkSize
                    continue
                }
                // RES_TABLE_TYPE_SPEC_TYPE = 0x0202 - typespec, skip
                0x0202 -> {
                    pos += chunkSize
                    continue
                }
                else -> {
                    // Unknown chunk, try to skip by size if reasonable
                    if (chunkSize > 0) {
                        pos += chunkSize
                    } else {
                        pos += 4
                    }
                    continue
                }
            }
        }

        Log.d(TAG, "ApkAnalyzer: found ${locales.size} locales from resources.arsc: $locales")
        return locales.sorted()
    }

    /**
     * Fallback: detect locales from res/values-* directory entries.
     */
    private fun detectLocalesFromEntries(entries: List<String>): List<String> {
        val localePattern = Regex("res/values-([a-z]{2}(-r[A-Z]{2})?)/.*")
        val locales = mutableSetOf<String>()

        for (entry in entries) {
            val match = localePattern.matchEntire(entry)
            if (match != null) {
                locales.add(match.groupValues[1])
            }
        }

        return locales.sorted()
    }

    private fun readShortLE(data: ByteArray, pos: Int): Int {
        return (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
    }

    private fun readIntLE(data: ByteArray, pos: Int): Int {
        return (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Minimal binary Android XML (AXML) parser.
 * Parses just enough to extract package name and launcher activity.
 */
class BinaryXmlParser(private val data: ByteArray) {

    data class ManifestResult(
        var packageName: String? = null,
        var launcherActivity: String? = null
    )

    companion object {
        private const val TAG = "LanInjector"

        private const val CHUNK_STRING_TABLE = 0x001C0001
        private const val CHUNK_START_TAG = 0x00100102
        private const val CHUNK_END_TAG = 0x00100103
    }

    private var position = 0
    private var stringTable: Array<String> = emptyArray()

    // State for tracking launcher activity
    private var currentActivityName: String? = null
    private var inActivity = false
    private var inIntentFilter = false
    private var hasMainAction = false
    private var hasLauncherCategory = false

    fun parse(): ManifestResult {
        val result = ManifestResult()
        position = 0

        // File header: magic(4) + fileSize(4)
        val magic = readInt()
        val fileSize = readInt()
        Log.d(TAG, "BinaryXmlParser: magic=0x${magic.toString(16)}, fileSize=$fileSize, dataSize=${data.size}")

        while (position < data.size) {
            val chunkStart = position
            if (position + 8 > data.size) break

            val chunkType = readInt()
            val chunkSize = readInt()

            if (chunkSize < 8 || chunkStart + chunkSize > data.size + 8) {
                Log.w(TAG, "BinaryXmlParser: invalid chunkSize=$chunkSize at pos=$chunkStart, stopping")
                break
            }

            when (chunkType) {
                CHUNK_STRING_TABLE -> parseStringTable(chunkStart, chunkSize)
                CHUNK_START_TAG -> parseStartTag(chunkStart, chunkSize, result)
                CHUNK_END_TAG -> parseEndTag(chunkStart, chunkSize)
            }

            // Always advance to next chunk boundary
            position = chunkStart + chunkSize

            // Early exit if we found what we need
            if (result.packageName != null && result.launcherActivity != null) {
                Log.d(TAG, "BinaryXmlParser: found all needed info, stopping early")
                break
            }
        }

        Log.d(TAG, "BinaryXmlParser: result pkg=${result.packageName}, launcher=${result.launcherActivity}")
        return result
    }

    private fun parseStringTable(chunkStart: Int, chunkSize: Int) {
        val stringCount = readInt()
        val styleCount = readInt()
        val flags = readInt()
        val stringsOffset = readInt()
        val stylesOffset = readInt()

        val isUtf8 = (flags and (1 shl 8)) != 0
        Log.d(TAG, "BinaryXmlParser: stringTable count=$stringCount, utf8=$isUtf8")

        // Read string offsets
        val offsets = IntArray(stringCount) { readInt() }

        val stringsStart = chunkStart + stringsOffset

        stringTable = Array(stringCount) { i ->
            val stringOffset = stringsStart + offsets[i]
            if (stringOffset < 0 || stringOffset >= data.size) "" else readStringAt(stringOffset, isUtf8)
        }
    }

    private fun parseStartTag(chunkStart: Int, chunkSize: Int, result: ManifestResult) {
        // START_TAG chunk body (after type+size already read):
        // lineNumber(4), comment(4), nsUri(4), name(4),
        // attributeStart(2)+attributeSize(2), attributeCount(2)+idIndex(2), classIndex(2)+styleIndex(2)
        val lineNumber = readInt()
        val comment = readInt()
        val nsUri = readInt()
        val nameIdx = readInt()
        val attrStartAndSize = readInt()   // attributeStart(16) | attributeSize(16)
        val attrCountAndId = readInt()     // attributeCount(16) | idIndex(16)
        val classAndStyle = readInt()      // classIndex(16) | styleIndex(16)

        val attrCount = attrCountAndId and 0xFFFF
        val tagName = getStringFromTable(nameIdx)

        val attributes = mutableMapOf<String, String>()

        for (i in 0 until attrCount) {
            if (position + 20 > data.size) break
            val attrNsUri = readInt()
            val attrNameIdx = readInt()
            val attrRawValue = readInt()
            val attrTypedValue = readInt()
            val attrData = readInt()

            val attrName = getStringFromTable(attrNameIdx)
            val attrValue = if (attrRawValue >= 0 && attrRawValue < stringTable.size) {
                stringTable[attrRawValue]
            } else {
                attrData.toString()
            }

            attributes[attrName] = attrValue
        }

        when (tagName) {
            "manifest" -> {
                result.packageName = attributes["package"]
                Log.d(TAG, "BinaryXmlParser: <manifest> package=${result.packageName}")
            }
            "activity", "activity-alias" -> {
                inActivity = true
                hasMainAction = false
                hasLauncherCategory = false
                inIntentFilter = false
                currentActivityName = attributes["name"]
            }
            "intent-filter" -> {
                if (inActivity) inIntentFilter = true
            }
            "action" -> {
                if (inIntentFilter && attributes["name"] == "android.intent.action.MAIN") {
                    hasMainAction = true
                }
            }
            "category" -> {
                if (inIntentFilter && attributes["name"] == "android.intent.category.LAUNCHER") {
                    hasLauncherCategory = true
                }
                if (hasMainAction && hasLauncherCategory && currentActivityName != null) {
                    result.launcherActivity = currentActivityName
                    Log.d(TAG, "BinaryXmlParser: found launcher=${result.launcherActivity}")
                }
            }
        }
    }

    private fun parseEndTag(chunkStart: Int, chunkSize: Int) {
        // END_TAG: lineNumber(4), comment(4), nsUri(4), name(4)
        val lineNumber = readInt()
        val comment = readInt()
        val nsUri = readInt()
        val nameIdx = readInt()
        val tagName = getStringFromTable(nameIdx)

        when (tagName) {
            "activity", "activity-alias" -> {
                inActivity = false
                inIntentFilter = false
            }
            "intent-filter" -> {
                inIntentFilter = false
            }
        }
    }

    private fun readStringAt(offset: Int, isUtf8: Boolean): String {
        return try {
            if (isUtf8) readUtf8String(offset) else readUtf16String(offset)
        } catch (e: Exception) {
            ""
        }
    }

    private fun readUtf8String(offset: Int): String {
        var pos = offset
        // Char length (1 or 2 bytes)
        val b0 = data[pos].toInt() and 0xFF
        pos += if (b0 > 0x7F) 2 else 1
        // Byte length (1 or 2 bytes)
        val b1 = data[pos].toInt() and 0xFF
        val byteLen = if (b1 > 0x7F) {
            val b2 = data[pos + 1].toInt() and 0xFF
            pos += 2
            ((b1 and 0x7F) shl 8) or b2
        } else {
            pos += 1
            b1
        }
        if (pos + byteLen > data.size) return ""
        return String(data, pos, byteLen, Charsets.UTF_8)
    }

    private fun readUtf16String(offset: Int): String {
        var pos = offset
        var charLen = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        if (charLen > 0x7FFF) {
            charLen = ((charLen and 0x7FFF) shl 16) or
                    ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8))
            pos += 2
        }
        val byteCount = charLen * 2
        if (pos + byteCount > data.size) return ""
        val bytes = ByteArray(byteCount)
        System.arraycopy(data, pos, bytes, 0, byteCount)
        return String(bytes, Charsets.UTF_16LE)
    }

    private fun getStringFromTable(index: Int): String {
        return if (index >= 0 && index < stringTable.size) stringTable[index] else ""
    }

    private fun readInt(): Int {
        if (position + 4 > data.size) return 0
        val value = (data[position].toInt() and 0xFF) or
                ((data[position + 1].toInt() and 0xFF) shl 8) or
                ((data[position + 2].toInt() and 0xFF) shl 16) or
                ((data[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return value
    }
}
