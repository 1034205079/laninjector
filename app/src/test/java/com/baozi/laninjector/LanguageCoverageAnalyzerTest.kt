package com.baozi.laninjector

import com.baozi.laninjector.injection.LanguageCoverageAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class LanguageCoverageAnalyzerTest {
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun pool(values: List<String>): ByteArray {
        val encoded = values.map { val b = it.toByteArray(); byteArrayOf(b.size.toByte(), b.size.toByte()) + b + byteArrayOf(0) }
        val start = 28 + values.size * 4
        val size = (start + encoded.sumOf { it.size } + 3) and -4
        val b = buffer(size).putShort(1).putShort(28).putInt(size).putInt(values.size).putInt(0).putInt(256).putInt(start).putInt(0)
        var offset = 0
        encoded.forEach { b.putInt(offset); offset += it.size }
        encoded.forEach { b.put(it) }
        return b.array()
    }

    private fun type(locale: String, indices: List<Int>, flags: Int = 0, aliases: Boolean = false): ByteArray {
        val count = if (flags == 1) indices.size else 12
        val width = if (flags == 2) 2 else 4
        val start = 84 + count * width
        val size = start + indices.size * 16
        val b = buffer(size).putShort(0x201).putShort(84).putInt(size).put(1).put(flags.toByte()).putShort(0).putInt(count).putInt(start)
        b.putInt(64)
        if (locale.isNotEmpty()) { b.put(28, locale[0].code.toByte()); b.put(29, locale[1].code.toByte()) }
        b.position(84)
        if (flags == 1) indices.forEachIndexed { n, id -> b.putShort(id.toShort()).putShort((n * 4).toShort()) }
        else for (id in 0 until count) {
            val n = indices.indexOf(id)
            if (flags == 2) b.putShort(if (n < 0) -1 else (n * 4).toShort()) else b.putInt(if (n < 0) -1 else n * 16)
        }
        indices.forEach { key ->
            b.putShort(8).putShort(0).putInt(key).putShort(8).put(0).put(if (aliases) 1 else 3).putInt(if (aliases) 0x7f010000 else 0)
        }
        return b.array()
    }

    private fun table(vararg chunks: ByteArray): ByteArray {
        val types = pool(listOf("string"))
        val keys = pool((0..9).map { "page_$it" } + listOf("abc_ok", "mtrl_cancel"))
        val packageSize = 288 + types.size + keys.size + chunks.sumOf { it.size }
        val p = buffer(packageSize).putShort(0x200).putShort(288).putInt(packageSize).putInt(127)
        p.putInt(268, 288); p.putInt(276, 288 + types.size); p.position(288)
        p.put(types).put(keys); chunks.forEach { p.put(it) }
        return buffer(12 + packageSize).putShort(2).putShort(12).putInt(12 + packageSize).putInt(1).put(p.array()).array()
    }

    private fun refs() = (0..11).map { 0x7f010000 + it }.toSet()

    @Test fun explicitCoverageFiltersLibraryAndDoesNotCountDefaultFallbackOrAliases() {
        val data = table(type("", (0..11).toList()), type("en", (0..11).toList()),
            type("ja", (0..7).toList(), 1), type("fr", listOf(10, 11), 2), type("ko", (0..9).toList(), aliases = true))
        val report = LanguageCoverageAnalyzer().analyze(data, refs(), false)
        val scores = report.coverage.associateBy { it.locale }
        assertEquals(10, scores.getValue("en").total)
        assertEquals(100, scores.getValue("en").percent)
        assertEquals(80, scores.getValue("ja").percent)
        assertTrue(scores.getValue("ja").recommended)
        assertEquals(0, scores.getValue("fr").translated)
        assertFalse(scores.getValue("fr").recommended)
        assertEquals(0, scores.getValue("ko").translated)
        assertFalse(LanguageCoverageAnalyzer().analyze(data, refs(), true).coverage.any { it.recommended })
    }

    @Test fun binaryXmlReadsTypedReferencesAndRejectsTruncation() {
        val b = buffer(64).putShort(3).putShort(8).putInt(64)
        b.putShort(0x102).putShort(16).putInt(56).putInt(0).putInt(-1)
        b.putInt(-1).putInt(0).putShort(20).putShort(20).putShort(1).putShort(0).putShort(0).putShort(0)
        b.putInt(-1).putInt(0).putInt(-1).putShort(8).put(0).put(1).putInt(0x7f010001)
        assertEquals(setOf(0x7f010001), LanguageCoverageAnalyzer.xmlReferences(b.array()))
        try { LanguageCoverageAnalyzer.xmlReferences(b.array().copyOf(55)); fail("Truncation must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun reportsOriginalApkWhenProvided() {
        val path = System.getenv("LANINJECTOR_TEST_APK") ?: return
        ZipFile(File(path)).use { zip ->
            val refs = mutableSetOf<Int>()
            zip.entries().asSequence().filter { (it.name.startsWith("res/layout") || it.name.startsWith("res/menu")) && it.name.endsWith(".xml") }
                .filter { entry -> !LanguageCoverageAnalyzer.isLibraryResource(entry.name.substringAfterLast('/')) }
                .forEach { refs.addAll(LanguageCoverageAnalyzer.xmlReferences(zip.getInputStream(it).use { input -> input.readBytes() })) }
            val data = zip.getInputStream(zip.getEntry("resources.arsc")).use { it.readBytes() }
            val result = LanguageCoverageAnalyzer().analyze(data, refs, false)
            assertTrue(result.coverage.isNotEmpty())
            println("Original APK coverage basis: ${result.basis}")
            result.coverage.forEach { println("${it.locale}: ${it.translated}/${it.total} (${it.percent}%) recommended=${it.recommended}") }
            assertTrue("Expected at least one target language with page translations", result.coverage.any { it.translated > 0 })
        }
    }
}
