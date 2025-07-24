package com.taskfree.app.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

// recognise   https://x, http://x,  www.x, email@domain.x
private val linkRegex =
    "(https?://\\S+)|(www\\.\\S+)|([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})".toRegex(RegexOption.IGNORE_CASE)

fun linkified(raw: String, linkStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (m in linkRegex.findAll(raw)) {
            append(raw.substring(last, m.range.first))
            last = m.range.last + 1
            val shown = m.value
            val link = when {
                shown.startsWith("www.", true) -> "https://$shown"
                shown.contains("@") -> "mailto:$shown"
                else -> shown
            }
            withLink(LinkAnnotation.Url(link, TextLinkStyles(linkStyle))) {
                append(shown)
            }
        }
        append(raw.substring(last))
    }

// Extension function for AnnotatedString
fun linkified(annotated: AnnotatedString, linkStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        val text = annotated.text
        var currentPos = 0

        for (m in linkRegex.findAll(text)) {
            // Append text before the URL
            val beforeUrl = text.substring(currentPos, m.range.first)
            val beforeStart = length
            append(beforeUrl)

            // Copy existing annotations that overlap with the text before URL
            annotated.spanStyles.forEach { spanStyle ->
                val overlapStart = maxOf(spanStyle.start, currentPos)
                val overlapEnd = minOf(spanStyle.end, m.range.first)
                if (overlapStart < overlapEnd) {
                    val newStart = beforeStart + (overlapStart - currentPos)
                    val newEnd = beforeStart + (overlapEnd - currentPos)
                    addStyle(spanStyle.item, newStart, newEnd)
                }
            }

            annotated.paragraphStyles.forEach { paragraphStyle ->
                val overlapStart = maxOf(paragraphStyle.start, currentPos)
                val overlapEnd = minOf(paragraphStyle.end, m.range.first)
                if (overlapStart < overlapEnd) {
                    val newStart = beforeStart + (overlapStart - currentPos)
                    val newEnd = beforeStart + (overlapEnd - currentPos)
                    addStyle(paragraphStyle.item, newStart, newEnd)
                }
            }

            currentPos = m.range.last + 1
            val shown = m.value
            val link = when {
                shown.startsWith("www.", true) -> "https://$shown"
                shown.contains("@") -> "mailto:$shown"
                else -> shown
            }

            // Add the link
            withLink(LinkAnnotation.Url(link, TextLinkStyles(linkStyle))) {
                append(shown)
            }
        }

        // Append remaining text
        val remainingText = text.substring(currentPos)
        val remainingStart = length
        append(remainingText)

        // Copy existing annotations for remaining text
        annotated.spanStyles.forEach { spanStyle ->
            val overlapStart = maxOf(spanStyle.start, currentPos)
            val overlapEnd = minOf(spanStyle.end, text.length)
            if (overlapStart < overlapEnd) {
                val newStart = remainingStart + (overlapStart - currentPos)
                val newEnd = remainingStart + (overlapEnd - currentPos)
                addStyle(spanStyle.item, newStart, newEnd)
            }
        }

        annotated.paragraphStyles.forEach { paragraphStyle ->
            val overlapStart = maxOf(paragraphStyle.start, currentPos)
            val overlapEnd = minOf(paragraphStyle.end, text.length)
            if (overlapStart < overlapEnd) {
                val newStart = remainingStart + (overlapStart - currentPos)
                val newEnd = remainingStart + (overlapEnd - currentPos)
                addStyle(paragraphStyle.item, newStart, newEnd)
            }
        }
    }

@Composable
fun AutoLinkedText(
    raw: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
    linkStyle: SpanStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
) {
    val text = remember(raw) { linkified(raw, linkStyle) }
    Text(text, style = style, color = color)
}

// New overload for AnnotatedString
@Composable
fun AutoLinkedText(
    annotated: AnnotatedString,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
    linkStyle: SpanStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
) {
    val text = remember(annotated, linkStyle) { linkified(annotated, linkStyle) }
    Text(text, style = style, color = color)
}