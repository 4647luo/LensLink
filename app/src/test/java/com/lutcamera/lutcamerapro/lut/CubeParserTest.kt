package com.lutcamera.lutcamerapro.lut

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class CubeParserTest {
    private val rows = listOf("0 0 0", "1 0 0", "0 1 0", "1 1 0", "0 0 1", "1 0 1", "0 1 1", "1 1 1")

    private fun cube(header: String = "LUT_3D_SIZE 2", data: List<String> = rows): String =
        header + "\n" + data.joinToString("\n") + "\n"

    private fun parse(text: String) = CubeParser.parseOrThrow(text.byteInputStream())

    @Test fun acceptsStandardTitleCommentsBomAndDefaultDomain() {
        val text = cube("\uFEFF# camera LUT\nTITLE \"My # LUT\" # header comment\nDOMAIN_MIN 0 0 0\nLUT_3D_SIZE 2\nDOMAIN_MAX 1 1 1", rows.map { "$it # node" })
        val result = parse(text)
        assertEquals("My # LUT", result.title)
        assertEquals(2, result.size)
        assertEquals(24, result.data.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), result.data.take(6).toFloatArray(), 0f)
    }

    @Test fun supportsWhitespaceScientificNotationAndFiniteExtendedOutputRange() {
        val result = parse(cube("// comment\nTITLE Sample LUT\nLUT_3D_SIZE\t2", rows.toMutableList().apply { this[0] = "-1e-2\t1.2E0   +0.5 // finite values" }))
        assertEquals("Sample LUT", result.title)
        assertArrayEquals(floatArrayOf(-0.01f, 1.2f, 0.5f), result.data.take(3).toFloatArray(), 0f)
    }

    @Test fun rejectsDimensionsBeforeOverflowOrLargeAllocation() {
        listOf("-1", "0", "1", "66", "128", "100000", "2147483647", "99999999999999999", "2.5").forEach { size ->
            expectInvalid(cube("LUT_3D_SIZE $size"))
        }
    }

    @Test fun acceptsMaximumSupportedDimension() {
        val size = CubeParser.MAX_SIZE
        val text = buildString {
            append("LUT_3D_SIZE ").append(size).append('\n')
            repeat(size * size * size) { append("0 0 0\n") }
        }
        val result = parse(text)
        assertEquals(size * size * size * 3, result.data.size)
    }

    @Test fun rejectsMissingAndExtraNodesRatherThanTruncating() {
        expectInvalid(cube(data = rows.dropLast(1)))
        expectInvalid(cube(data = rows + "0 0 0"))
    }

    @Test fun rejectsShortOrLongRowsRatherThanRealigningComponents() {
        listOf("0 0", "0 0 0 0", "0", "0.1 broken 0.3").forEach { invalid ->
            expectInvalid(cube(data = rows.toMutableList().apply { this[1] = invalid }))
        }
    }

    @Test fun rejectsNonFiniteComponentsAndNumericOverflow() {
        listOf("NaN 0 0", "0 NaN 0", "0 0 NaN", "Infinity 0 0", "0 -Infinity 0", "0 0 1e999").forEach { invalid ->
            expectInvalid(cube(data = rows.toMutableList().apply { this[2] = invalid }))
        }
    }

    @Test fun explicitlyRejectsUnsupportedNonDefaultDomains() {
        val minimum = expectInvalid(cube("LUT_3D_SIZE 2\nDOMAIN_MIN -1 0 0"))
        val maximum = expectInvalid(cube("LUT_3D_SIZE 2\nDOMAIN_MAX 1 2 1"))
        assertTrue(minimum.message!!.contains("DOMAIN_MIN 0 0 0"))
        assertTrue(maximum.message!!.contains("DOMAIN_MAX 1 1 1"))
        expectInvalid(cube("LUT_3D_SIZE 2\nDOMAIN_MIN 0 NaN 0"))
        expectInvalid(cube("LUT_3D_SIZE 2\nDOMAIN_MAX 1 1"))
    }

    @Test fun rejectsDuplicateOrLateHeadersAndUnsupportedOneDimensionalTables() {
        expectInvalid(cube("LUT_3D_SIZE 2\nLUT_3D_SIZE 2"))
        expectInvalid(cube("LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MIN 0 0 0"))
        expectInvalid(cube("TITLE \"first\"\nTITLE \"second\"\nLUT_3D_SIZE 2"))
        expectInvalid(cube(data = rows + "DOMAIN_MAX 1 1 1"))
        expectInvalid(cube("LUT_1D_SIZE 2"))
        expectInvalid(cube("LUT_1D_SIZE 2\nLUT_3D_SIZE 2"))
        expectInvalid(rows.joinToString("\n"))
    }

    @Test fun rejectsMalformedTitlesAndExcessiveLineLength() {
        expectInvalid(cube("TITLE \"unclosed\nLUT_3D_SIZE 2"))
        expectInvalid(cube("TITLE \"quoted\" ignored\nLUT_3D_SIZE 2"))
        expectInvalid(cube("TITLE\nLUT_3D_SIZE 2"))
        expectInvalid("#" + "a".repeat(4096) + "\n" + cube())
    }

    @Test fun compatibilityParserReturnsNullForCorruptionAndIoFailure() {
        assertNull(CubeParser.parse(cube(data = rows.dropLast(1)).byteInputStream()))
        val brokenSource = object : InputStream() { override fun read(): Int = throw IOException("unreadable") }
        assertNull(CubeParser.parse(brokenSource))
    }

    private fun expectInvalid(text: String): CubeParser.ParseException {
        try {
            parse(text)
            fail("Invalid CUBE was accepted")
        } catch (expected: CubeParser.ParseException) {
            assertTrue(expected.message!!.isNotBlank())
            return expected
        }
        throw AssertionError("Expected ParseException")
    }
}
