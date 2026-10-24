package com.convx.music.betterlyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TTMLParserTest {
    @Test
    fun parsesWordsAndBackgroundSpansButSkipsTranslations() {
        val ttml = """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body><div><p begin="0">
                    <span begin="0" end="1">Hello</span>
                    <span ttm:role="x-translation" begin="0" end="1">Hola</span>
                    <span ttm:role="x-bg" begin="1" end="2">yeah</span>
                </p></div></body>
            </tt>
        """.trimIndent()

        val line = TTMLParser.parseTTML(ttml).single()

        assertEquals("Hello", line.text)
        assertEquals("yeah", line.backgroundLines.single().text)
    }

    @Test
    fun mergesTimedSpansIntoWordsAtWhitespaceBoundaries() {
        val ttml = """
            <tt><body><div><p begin="0"><span begin="0" end="0.5">Hel</span><span begin="0.5" end="1">lo</span> <span begin="1" end="2">world</span></p></div></body></tt>
        """.trimIndent()

        val line = TTMLParser.parseTTML(ttml).single()

        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hello", "world"), line.words.map { it.text })
    }

    @Test
    fun parsesSupportedTimeFormatsAndFallsBackToZeroForInvalidTimes() {
        val cases = mapOf(
            "12.5" to 12.5,
            "1:02.5" to 62.5,
            "1:02:03.5" to 3723.5,
            "invalid" to 0.0,
            "1:invalid" to 0.0,
            "1:2:3:4" to 0.0,
        )

        cases.forEach { (time, expectedSeconds) ->
            val ttml = "<tt><body><div><p begin=\"$time\">Lyrics</p></div></body></tt>"
            val line = TTMLParser.parseTTML(ttml).single()

            assertEquals(time, expectedSeconds, line.startTime, 0.001)
        }
    }

    @Test
    fun toLrcFormatsMainAndBackgroundLineTimestamps() {
        val output = TTMLParser.toLRC(
            listOf(
                TTMLParser.ParsedLine(
                    text = "Main line",
                    startTime = 61.239,
                    words = emptyList(),
                    agent = "v1",
                    backgroundLines = listOf(
                        TTMLParser.ParsedLine(
                            text = "Background line",
                            startTime = 62.5,
                            words = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "[01:01.23]{agent:v1}Main line",
                "[01:02.50]{bg}Background line",
            ),
            output.lines().filter(String::isNotEmpty),
        )
    }
}
