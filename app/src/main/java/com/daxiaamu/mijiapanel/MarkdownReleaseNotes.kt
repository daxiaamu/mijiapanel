package com.daxiaamu.mijiapanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun MarkdownReleaseNotes(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val lines = remember(markdown) {
        markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { originalLine ->
            val line = originalLine.trimEnd()
            val heading = HEADING.matchEntire(line)
            val unordered = UNORDERED_LIST.matchEntire(line)
            val ordered = ORDERED_LIST.matchEntire(line)
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                heading != null -> {
                    val level = heading.groupValues[1].length
                    Text(
                        text = inlineMarkdown(
                            heading.groupValues[2],
                            linkColor,
                            codeBackground,
                        ),
                        style = when (level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                unordered != null -> MarkdownListItem(
                    marker = "•",
                    content = unordered.groupValues[1],
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                )
                ordered != null -> MarkdownListItem(
                    marker = "${ordered.groupValues[1]}.",
                    content = ordered.groupValues[2],
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                )
                line.trimStart().startsWith("> ") -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text(
                            text = inlineMarkdown(
                                line.trimStart().removePrefix("> "),
                                linkColor,
                                codeBackground,
                            ),
                            modifier = Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
                else -> Text(
                    text = inlineMarkdown(line, linkColor, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MarkdownListItem(
    marker: String,
    content: String,
    linkColor: Color,
    codeBackground: Color,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = inlineMarkdown(content, linkColor, codeBackground),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun inlineMarkdown(
    text: String,
    linkColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE_TOKEN.findAll(text).forEach { match ->
        append(text, cursor, match.range.first)
        when {
            match.groupValues[1].isNotEmpty() -> withLink(
                LinkAnnotation.Url(
                    url = match.groupValues[2],
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                append(match.groupValues[1])
            }
            match.groupValues[3].isNotEmpty() -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
            ) {
                append(match.groupValues[3])
            }
            match.groupValues[4].isNotEmpty() -> withStyle(
                SpanStyle(fontStyle = FontStyle.Italic),
            ) {
                append(match.groupValues[4])
            }
            match.groupValues[5].isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                ),
            ) {
                append(match.groupValues[5])
            }
            match.groupValues[6].isNotEmpty() -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
            ) {
                append(match.groupValues[6])
            }
        }
        cursor = match.range.last + 1
    }
    append(text, cursor, text.length)
}

private val HEADING = Regex("""^\s*(#{1,6})\s+(.+)$""")
private val UNORDERED_LIST = Regex("""^\s*[-*+]\s+(.+)$""")
private val ORDERED_LIST = Regex("""^\s*(\d+)[.)]\s+(.+)$""")
private val INLINE_TOKEN = Regex(
    """\[([^\]\r\n]+)]\((https?://[^\s)]+)\)|\*\*([^*\r\n]+)\*\*|\*([^*\r\n]+)\*|`([^`\r\n]+)`|~~([^~\r\n]+)~~""",
    RegexOption.IGNORE_CASE,
)
