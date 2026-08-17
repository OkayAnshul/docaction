package com.okayanshul.docaction.document.spreadsheet

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * A focused streaming XLSX reader.
 *
 * Apache POI was rejected (ADR-009): it assumes a StAX implementation Android doesn't
 * provide, carries a large dex footprint, and `XSSFWorkbook` loads whole workbooks into
 * memory. This reads what a schedule needs — values, positions, merges — and nothing else.
 * No formula evaluation, no charts, no writing.
 *
 * SAX rather than `XmlPullParser`: SAX ships on both the JVM and Android, which keeps this
 * module pure Kotlin and unit-testable against real workbooks with no emulator. (ADR-009
 * originally named XmlPullParser; SAX is strictly better for that reason.)
 *
 * Spreadsheets are the most hostile format the app accepts — ZIP plus XML is two attack
 * surfaces — so every limit below exists because its absence is exploitable.
 */
class XlsxReader(
    private val maxUncompressedBytes: Long = 200L * 1024 * 1024,
    private val maxCompressionRatio: Int = 100,
    private val maxEntries: Int = 10_000,
    private val maxCellsPerSheet: Int = 1_000_000,
) {

    fun read(file: File): Workbook {
        if (!file.exists() || file.length() == 0L) throw XlsxException(XlsxFailure.Empty)

        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw XlsxException(XlsxFailure.Corrupt, "not a readable zip")
        }

        return zip.use { archive ->
            guardArchive(archive)

            val entries = archive.entries().asSequence().map { it.name }.toList()
            if (entries.none { it == "xl/workbook.xml" }) throw XlsxException(XlsxFailure.NotAWorkbook)

            val sharedStrings = archive.entry("xl/sharedStrings.xml")
                ?.let { archive.getInputStream(it).use(::readSharedStrings) }
                ?: emptyList()

            val relationships = archive.entry("xl/_rels/workbook.xml.rels")
                ?.let { archive.getInputStream(it).use(::readRelationships) }
                ?: emptyMap()

            val declared = archive.entry("xl/workbook.xml")!!
                .let { archive.getInputStream(it).use(::readSheetIndex) }

            val sheets = declared.mapNotNull { sheet ->
                val target = relationships[sheet.relationshipId] ?: return@mapNotNull null
                val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
                val entry = archive.entry(path) ?: return@mapNotNull null
                val cells = archive.getInputStream(entry).use { readSheet(it, sharedStrings) }
                SheetGrid.of(sheet.name, sheet.hidden, cells)
            }

            if (sheets.isEmpty()) throw XlsxException(XlsxFailure.Corrupt, "no readable sheets")
            Workbook(sheets)
        }
    }

    // ---- safety ----

    private fun guardArchive(archive: ZipFile) {
        var entries = 0
        var uncompressed = 0L

        archive.entries().asSequence().forEach { entry ->
            if (++entries > maxEntries) throw XlsxException(XlsxFailure.Hostile, "too many entries")
            if (!isSafeName(entry.name)) throw XlsxException(XlsxFailure.Hostile, "unsafe entry name")

            val size = entry.size
            val compressed = entry.compressedSize
            if (size >= 0) {
                uncompressed += size
                if (uncompressed > maxUncompressedBytes) {
                    throw XlsxException(XlsxFailure.Hostile, "uncompressed size limit")
                }
                if (compressed > 0 && size / compressed > maxCompressionRatio) {
                    throw XlsxException(XlsxFailure.Hostile, "compression ratio limit")
                }
            }
        }
    }

    /**
     * Entry names are never used to build a filesystem path anywhere in this codebase, but
     * a name that tries to escape is a signal worth refusing outright.
     */
    internal fun isSafeName(name: String): Boolean =
        !name.startsWith("/") && !name.contains("..") && !name.contains('\\') && !name.contains(':')

    private fun ZipFile.entry(name: String): ZipEntry? = getEntry(name)

    // ---- parts ----

    private data class DeclaredSheet(val name: String, val relationshipId: String, val hidden: Boolean)

    private fun readSheetIndex(input: InputStream): List<DeclaredSheet> {
        val sheets = mutableListOf<DeclaredSheet>()
        parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                if (qName.substringAfter(':') != "sheet") return
                val id = attrs.getValue("r:id") ?: attrs.getValue("id") ?: return
                sheets += DeclaredSheet(
                    name = attrs.getValue("name").orEmpty(),
                    relationshipId = id,
                    // Hidden sheets are read but flagged — never silently included, never
                    // silently dropped. The user decides.
                    hidden = attrs.getValue("state").equals("hidden", ignoreCase = true),
                )
            }
        })
        return sheets
    }

    private fun readRelationships(input: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                if (qName.substringAfter(':') != "Relationship") return
                val id = attrs.getValue("Id") ?: return
                val target = attrs.getValue("Target") ?: return
                map[id] = target
            }
        })
        return map
    }

    private fun readSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        parse(input, object : DefaultHandler() {
            private val current = StringBuilder()
            private var inItem = false
            private var inText = false

            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                when (qName.substringAfter(':')) {
                    "si" -> { inItem = true; current.setLength(0) }
                    "t" -> inText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inItem && inText) current.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "t" -> inText = false
                    "si" -> { strings += current.toString(); inItem = false }
                }
            }
        })
        return strings
    }

    private fun readSheet(input: InputStream, sharedStrings: List<String>): List<SheetCell> {
        val cells = mutableListOf<SheetCell>()

        parse(input, object : DefaultHandler() {
            private var reference: String? = null
            private var type: String? = null
            private val value = StringBuilder()
            private var inValue = false
            private var inInlineText = false

            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                when (qName.substringAfter(':')) {
                    "c" -> {
                        reference = attrs.getValue("r")
                        type = attrs.getValue("t")
                        value.setLength(0)
                    }
                    "v" -> inValue = true
                    "t" -> inInlineText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue || inInlineText) value.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val ref = reference ?: return
                        val raw = value.toString()
                        if (raw.isNotEmpty()) {
                            val text = when (type) {
                                "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                                "b" -> if (raw == "1") "TRUE" else "FALSE"
                                else -> raw
                            }
                            if (text.isNotBlank()) {
                                parseCellRef(ref)?.let { (row, column) ->
                                    if (cells.size >= maxCellsPerSheet) {
                                        throw XlsxException(XlsxFailure.TooLarge, "cell limit")
                                    }
                                    cells += SheetCell(row, column, text)
                                }
                            }
                        }
                        reference = null
                        type = null
                    }
                }
            }
        })

        return cells
    }

    private fun parse(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            // Billion laughs and external entity fetching, closed off explicitly rather
            // than relied upon to be off by default.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        try {
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (e: XlsxException) {
            throw e
        } catch (e: Exception) {
            throw XlsxException(XlsxFailure.Corrupt, "malformed part")
        }
    }
}
