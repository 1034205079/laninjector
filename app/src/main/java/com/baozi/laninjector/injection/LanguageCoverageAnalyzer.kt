package com.baozi.laninjector.injection

import com.baozi.laninjector.model.LanguageCoverage

/** Counts explicit string definitions; never asks Android for a fallback translation. */
class LanguageCoverageAnalyzer {
    private val names = mutableMapOf<Int, String>()
    private val strings = mutableSetOf<Int>()
    private val definitions = mutableMapOf<String, MutableSet<Int>>()
    private val references = mutableMapOf<Int, MutableSet<Int>>()
    private val locales = sortedSetOf<String>()

    data class Result(val locales: List<String>, val coverage: List<LanguageCoverage>, val basis: String)

    fun analyze(table: ByteArray, xmlReferences: Set<Int>, flutter: Boolean): Result {
        require(table.size >= 12 && u16(table, 0) == 2 && i32(table, 4) == table.size) { "Invalid resource table" }
        names.clear(); strings.clear(); definitions.clear(); references.clear(); locales.clear()
        chunks(table, 0, table.size) { start, type, header, size ->
            if (type == 2) chunks(table, start + header, start + size) { p, t, h, n ->
                if (t == 0x200) parsePackage(table, p, h, n)
            }
        }
        val reachable = xmlReferences.toMutableSet()
        val queue = java.util.ArrayDeque(xmlReferences)
        while (queue.isNotEmpty()) references[queue.removeFirst()]?.forEach { if (reachable.add(it)) queue.add(it) }
        fun appString(id: Int) = !isLibraryResource(names[id].orEmpty())
        var sample = reachable.filter { it in strings && appString(it) }.toSet()
        val fromXml = sample.size >= 10
        if (!fromXml) sample = strings.filter(::appString).toSet()
        val basis = if (fromXml) "布局/菜单引用的原生字符串（含样式引用）" else "原生字符串资源估算（未识别到足够页面引用）"
        val scores = locales.map { locale ->
            val own = definitions[locale].orEmpty()
            // A regional qualifier may inherit explicitly translated language-only entries.
            val language = locale.substringBefore('-')
            val inherited = if (locale != language) definitions[language].orEmpty() else emptySet()
            val translated = sample.count { it in own || it in inherited }
            val direct = sample.count { it in own }
            LanguageCoverage(locale, translated, sample.size,
                !flutter && direct > 0 && sample.size >= 10 && translated * 100L >= sample.size * 80L, direct)
        }
        return Result(locales.toList(), scores, if (flutter) "$basis；Flutter 正文未纳入，需手动确认" else basis)
    }

    private fun parsePackage(data: ByteArray, start: Int, header: Int, size: Int) {
        require(header >= 284)
        val pkg = i32(data, start + 8)
        require(pkg in 1..255)
        val typePool = pool(data, start + i32(data, start + 268))
        val keyPool = pool(data, start + i32(data, start + 276))
        val offset = if (header >= 288) i32(data, start + 284) else 0
        chunks(data, start + header, start + size) { p, type, h, n ->
            if (type != 0x201) return@chunks
            require(h >= 32)
            val rawType = u8(data, p + 8)
            val kind = typePool.getOrNull(rawType - 1) ?: return@chunks
            val typeId = rawType + offset
            require(typeId in 1..255)
            val flags = u8(data, p + 9)
            val count = i32(data, p + 12)
            val entryStart = i32(data, p + 16)
            require(count in 0..65536 && entryStart in h..n)
            val locale = locale(data, p + 20, h - 20)
            if (locale.isNotEmpty()) locales.add(locale)
            val width = if (flags and 2 != 0) 2 else 4
            require(count.toLong() * width <= entryStart - h)
            for (i in 0 until count) {
                val slot = p + h + i * width
                val index: Int
                val relative: Int
                if (flags and 1 != 0) {
                    index = u16(data, slot); relative = u16(data, slot + 2) * 4
                } else {
                    index = i
                    relative = if (flags and 2 != 0) {
                        val value = u16(data, slot); if (value == 0xffff) continue else value * 4
                    } else {
                        val value = i32(data, slot); if (value == -1) continue else value
                    }
                }
                val e = p + entryStart + relative
                require(relative >= 0 && e >= p && e.toLong() + 8 <= p.toLong() + n)
                val entryFlags = u16(data, e + 2)
                val compact = entryFlags and 8 != 0
                val key = if (compact) u16(data, e) else i32(data, e + 4)
                val id = (pkg shl 24) or (typeId shl 16) or index
                names[id] = keyPool.getOrNull(key) ?: ""
                val refs = references.getOrPut(id) { mutableSetOf() }
                var explicitString = false
                if (compact) {
                    val valueType = entryFlags ushr 8
                    if (valueType == 1 || valueType == 7) refs.add(i32(data, e + 4))
                    explicitString = valueType == 3
                } else {
                    val entrySize = u16(data, e)
                    require(entrySize >= 8 && e.toLong() + entrySize <= p.toLong() + n)
                    if (entryFlags and 1 != 0) {
                        require(entrySize >= 16)
                        val parent = i32(data, e + 8); if (parent != 0) refs.add(parent)
                        val maps = i32(data, e + 12)
                        require(maps >= 0 && maps.toLong() * 12 <= p.toLong() + n - e - entrySize)
                        for (j in 0 until maps) {
                            val v = e + entrySize + j * 12 + 4
                            val vt = u8(data, v + 3)
                            if (vt == 1 || vt == 7) refs.add(i32(data, v + 4))
                        }
                    } else {
                        val v = e + entrySize
                        require(v.toLong() + 8 <= p.toLong() + n)
                        val vt = u8(data, v + 3)
                        if (vt == 1 || vt == 7) refs.add(i32(data, v + 4))
                        explicitString = vt == 3
                    }
                }
                if (kind == "string") {
                    strings.add(id)
                    if (explicitString) definitions.getOrPut(locale) { mutableSetOf() }.add(id)
                }
            }
        }
    }

    companion object {
        private val libraryPrefixes = listOf("abc_", "mtrl_", "m3_", "material_", "androidx_", "design_",
            "preference_", "notification_", "status_bar_notification_", "common_google_", "exo_", "com_facebook_",
            "bigo_", "sdm_", "secmtp_", "mbridge_", "applovin_", "ia_", "pob_", "taurusx_", "vrvsdk_",
            "mediation_", "mobilefuse_", "zmaticoo_", "cloudx_", "pssdk_", "inmobi_", "applog_")
        fun isLibraryResource(name: String): Boolean = libraryPrefixes.any { name.startsWith(it) }

        /** All typed references, including styles and included layouts, from compiled XML. */
        fun xmlReferences(data: ByteArray): Set<Int> {
            val result = mutableSetOf<Int>()
            if (data.size < 8 || u16(data, 0) != 3) return result
            chunks(data, u16(data, 2), data.size) { p, type, h, n ->
                if (type == 0x102) {
                    require(h >= 16 && n >= 36)
                    val offset = u16(data, p + 24)
                    val stride = u16(data, p + 26)
                    val count = u16(data, p + 28)
                    require(stride >= 20 && offset >= 20 && 16L + offset + count.toLong() * stride <= n)
                    for (i in 0 until count) {
                        val a = p + 16 + offset + i * stride
                        val typeValue = u8(data, a + 15)
                        if (typeValue == 1 || typeValue == 7) result.add(i32(data, a + 16))
                    }
                }
            }
            return result
        }

        private fun chunks(data: ByteArray, start: Int, end: Int, block: (Int, Int, Int, Int) -> Unit) {
            require(start >= 0 && end <= data.size)
            var p = start
            while (p < end) {
                require(p.toLong() + 8 <= end)
                val header = u16(data, p + 2); val size = i32(data, p + 4)
                require(header >= 8 && size >= header && size.toLong() <= end - p)
                block(p, u16(data, p), header, size); p += size
            }
        }

        private fun pool(data: ByteArray, p: Int): List<String> {
            require(u16(data, p) == 1)
            val header = u16(data, p + 2); val size = i32(data, p + 4)
            val count = i32(data, p + 8); val utf8 = i32(data, p + 16) and 0x100 != 0
            val start = i32(data, p + 20)
            require(header >= 28 && count >= 0 && count.toLong() * 4 <= size - header && p.toLong() + size <= data.size)
            return List(count) { i ->
                var at = p + start + i32(data, p + header + i * 4)
                fun length(): Int {
                    val first = if (utf8) u8(data, at++) else u16(data, at).also { at += 2 }
                    val flag = if (utf8) 0x80 else 0x8000
                    return if (first and flag == 0) first else {
                        val second = if (utf8) u8(data, at++) else u16(data, at).also { at += 2 }
                        ((first and (flag - 1)) shl if (utf8) 8 else 16) or second
                    }
                }
                if (utf8) length()
                val len = length().toLong() * if (utf8) 1 else 2
                require(at >= p && len >= 0 && at.toLong() + len <= p.toLong() + size)
                String(data, at, len.toInt(), if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            }
        }

        private fun locale(data: ByteArray, p: Int, available: Int): String {
            val size = i32(data, p); require(size >= 12 && size <= available)
            fun unpack(at: Int, base: Char): String {
                val a = u8(data, at); val b = u8(data, at + 1)
                if (a == 0 && b == 0) return ""
                return if (a and 0x80 != 0) "${base + (b and 31)}${base + (((b and 224) shr 5) or ((a and 3) shl 3))}${base + ((a and 124) shr 2)}"
                else "${a.toChar()}${b.toChar()}"
            }
            val lang = when (val l = unpack(p + 8, 'a')) { "tl" -> "fil"; "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> l }
            if (lang.isEmpty()) return ""
            val region = unpack(p + 10, '0')
            val script = if (size >= 40) String(data, p + 36, 4, Charsets.US_ASCII).trimEnd('\u0000') else ""
            val variant = if (size >= 48) String(data, p + 40, 8, Charsets.US_ASCII).trimEnd('\u0000') else ""
            return if (script.isNotEmpty() || variant.isNotEmpty()) listOf(lang, script, region, variant).filter { it.isNotEmpty() }.joinToString("-")
            else if (region.isNotEmpty()) "$lang-r$region" else lang
        }
        private fun u8(d: ByteArray, p: Int) = d[p].toInt() and 255
        private fun u16(d: ByteArray, p: Int) = u8(d, p) or (u8(d, p + 1) shl 8)
        private fun i32(d: ByteArray, p: Int) = u16(d, p) or (u16(d, p + 2) shl 16)
    }
}
