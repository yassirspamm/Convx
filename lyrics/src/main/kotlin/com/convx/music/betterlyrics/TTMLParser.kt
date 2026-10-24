package com.convx.music.betterlyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

object TTMLParser {
    private val WHITESPACE_REGEX = Regex("\\s")
    
    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord>,
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList()
    )
    
    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double
    )
    
    private data class SpanInfo(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean
    )

    private fun Node.hasTrailingWhitespace(): Boolean {
        val sibling = nextSibling ?: return false
        return sibling.nodeType == Node.TEXT_NODE &&
            WHITESPACE_REGEX.containsMatchIn(sibling.textContent.orEmpty())
    }
    
    // Lookup by local name, handling namespace prefixes.
    private fun Element.getAttributeByLocalName(localName: String): String {
        val nsValue = getAttributeNS("http://www.w3.org/ns/ttml#metadata", localName)
        if (nsValue.isNotEmpty()) return nsValue
        
        val prefixedValue = getAttribute("ttm:$localName")
        if (prefixedValue.isNotEmpty()) return prefixedValue

        val attrs = attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val attrName = attr.nodeName ?: continue
            if (attrName == localName || attrName.endsWith(":$localName")) {
                return attr.nodeValue ?: ""
            }
        }
        return ""
    }
    
    fun parseTTML(ttml: String): List<ParsedLine> {
        val lines = mutableListOf<ParsedLine>()
        
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // Harden against XXE: remote TTML must not resolve external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ttml.byteInputStream())
            
            val pElements = doc.getElementsByTagName("p")
            
            for (i in 0 until pElements.length) {
                val pElement = pElements.item(i) as? Element ?: continue
                
                val begin = pElement.getAttribute("begin")
                if (begin.isNullOrEmpty()) continue
                
                val startTime = parseTime(begin)
                val spanInfos = mutableListOf<SpanInfo>()
                val backgroundLines = mutableListOf<ParsedLine>()
                
                val agent = pElement.getAttributeByLocalName("agent").ifEmpty { null }
                
                // Parse child nodes to preserve whitespace between spans
                val childNodes = pElement.childNodes
                for (j in 0 until childNodes.length) {
                    val node = childNodes.item(j)

                    if (node.nodeType != Node.ELEMENT_NODE) continue
                    val span = node as? Element ?: continue
                    if (span.tagName.lowercase() != "span") continue

                    // TTML uses roles to mark background, translated, and romanized spans.
                    val role = span.getAttributeByLocalName("role")
                    if (role == "x-bg") {
                        val bgLine = parseBackgroundSpan(span, startTime)
                        if (bgLine != null) backgroundLines.add(bgLine)
                        continue
                    }
                    if (role == "x-translation" || role == "x-roman") continue

                    parseWordSpan(span, node)?.let(spanInfos::add)
                }
                
                // Merge consecutive spans without whitespace between them into single words
                val words = mergeSpansIntoWords(spanInfos)
                val lineText = words.joinToString(" ") { it.text }
                
                // If no spans found, use text content directly (excluding background text)
                val finalText = if (lineText.isEmpty()) {
                    getDirectTextContent(pElement).trim()
                } else {
                    lineText
                }
                
                if (finalText.isNotEmpty()) {
                    lines.add(
                        ParsedLine(
                            text = finalText,
                            startTime = startTime,
                            words = words,
                            agent = agent,
                            isBackground = false,
                            backgroundLines = backgroundLines
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        
        return lines
    }
    
    private fun parseBackgroundSpan(span: Element, parentStartTime: Double): ParsedLine? {
        val bgBegin = span.getAttribute("begin")
        val bgStartTime = if (bgBegin.isNotEmpty()) parseTime(bgBegin) else parentStartTime
        
        val spanInfos = mutableListOf<SpanInfo>()
        val childNodes = span.childNodes
        
        for (j in 0 until childNodes.length) {
            val node = childNodes.item(j)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val innerSpan = node as? Element ?: continue
            if (innerSpan.tagName.lowercase() != "span") continue

            val role = innerSpan.getAttributeByLocalName("role")
            if (role == "x-translation" || role == "x-roman") continue

            parseWordSpan(innerSpan, node)?.let(spanInfos::add)
        }
        
        val words = mergeSpansIntoWords(spanInfos)
        val lineText = words.joinToString(" ") { it.text }
        
        val finalText = if (lineText.isEmpty()) {
            getDirectTextContent(span).trim()
        } else {
            lineText
        }
        
        return if (finalText.isNotEmpty()) {
            ParsedLine(
                text = finalText,
                startTime = bgStartTime,
                words = words,
                agent = null,
                isBackground = true,
                backgroundLines = emptyList()
            )
        } else null
    }

    private fun parseWordSpan(span: Element, sourceNode: Node): SpanInfo? {
        val begin = span.getAttribute("begin")
        val end = span.getAttribute("end")
        val text = span.textContent?.trim().orEmpty()
        if (text.isEmpty() || begin.isEmpty() || end.isEmpty()) return null

        return SpanInfo(
            text = text,
            startTime = parseTime(begin),
            endTime = parseTime(end),
            hasTrailingSpace = sourceNode.hasTrailingWhitespace(),
        )
    }
    
    private fun getDirectTextContent(element: Element): String {
        val sb = StringBuilder()
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            when (node.nodeType) {
                Node.TEXT_NODE -> sb.append(node.textContent)
                Node.ELEMENT_NODE -> {
                    val child = node as? Element ?: continue
                    val role = child.getAttributeByLocalName("role")
                    val isLyricSpan = child.tagName.lowercase() == "span"
                    val isExcludedRole = role == "x-bg" || role == "x-translation" || role == "x-roman"

                    if (isLyricSpan && !isExcludedRole) {
                        sb.append(child.textContent ?: "")
                    }
                }
            }
        }
        return sb.toString()
    }
    
    private fun mergeSpansIntoWords(spanInfos: List<SpanInfo>): List<ParsedWord> {
        if (spanInfos.isEmpty()) return emptyList()
        
        val words = mutableListOf<ParsedWord>()
        var currentText = StringBuilder(spanInfos.first().text)
        var currentStartTime = spanInfos.first().startTime
        var currentEndTime = spanInfos.first().endTime

        for (index in 1 until spanInfos.size) {
            val previousSpan = spanInfos[index - 1]
            val span = spanInfos[index]

            if (previousSpan.hasTrailingSpace) {
                words.add(
                    ParsedWord(
                        text = currentText.toString().trim(),
                        startTime = currentStartTime,
                        endTime = currentEndTime
                    )
                )
                currentText = StringBuilder(span.text)
                currentStartTime = span.startTime
            } else {
                // Adjacent spans without whitespace can be syllables of one word.
                currentText.append(span.text)
            }
            currentEndTime = span.endTime
        }

        words.add(
            ParsedWord(
                text = currentText.toString().trim(),
                startTime = currentStartTime,
                endTime = currentEndTime
            )
        )
        
        return words
    }
    
    fun toLRC(lines: List<ParsedLine>): String {
        return buildString {
            lines.forEach { line ->
                val agentPrefix = if (!line.agent.isNullOrEmpty()) "{agent:${line.agent}}" else ""

                appendLine("${formatLrcTime(line.startTime)}$agentPrefix${line.text}")

                if (line.words.isNotEmpty()) {
                    val wordsData = line.words.joinToString("|") { word ->
                        "${word.text}:${word.startTime}:${word.endTime}"
                    }
                    appendLine("<$wordsData>")
                }
                
                // Add background vocals as separate lines
                line.backgroundLines.forEach { bgLine ->
                    appendLine("${formatLrcTime(bgLine.startTime)}{bg}${bgLine.text}")

                    if (bgLine.words.isNotEmpty()) {
                        val bgWordsData = bgLine.words.joinToString("|") { word ->
                            "${word.text}:${word.startTime}:${word.endTime}"
                        }
                        appendLine("<$bgWordsData>")
                    }
                }
            }
        }
    }

    private fun formatLrcTime(timeSeconds: Double): String {
        val timeMs = (timeSeconds * 1000).toLong()
        val minutes = timeMs / 60000
        val seconds = (timeMs % 60000) / 1000
        val centiseconds = (timeMs % 1000) / 10
        return String.format("[%02d:%02d.%02d]", minutes, seconds, centiseconds)
    }
    
    private fun parseTime(timeStr: String): Double {
        if (!timeStr.contains(":")) return timeStr.toDoubleOrNull() ?: 0.0

        val parts = timeStr.split(":")
        return when (parts.size) {
            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: return 0.0
                val seconds = parts[1].toDoubleOrNull() ?: return 0.0
                minutes * 60 + seconds
            }
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: return 0.0
                val minutes = parts[1].toDoubleOrNull() ?: return 0.0
                val seconds = parts[2].toDoubleOrNull() ?: return 0.0
                hours * 3600 + minutes * 60 + seconds
            }
            else -> timeStr.toDoubleOrNull() ?: 0.0
        }
    }
}
