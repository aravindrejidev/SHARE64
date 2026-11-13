package com.share64.app.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.share64.app.core.MdBlock
import com.share64.app.core.MdSpan

/**
 * Renders the blocks produced by [com.share64.app.core.MarkdownLite.parse] (release notes) with
 * Material 3 text styles. Only http(s) links are tappable.
 */
@Composable
fun MarkdownView(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (block in blocks) MarkdownBlock(block)
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = rememberStyledText(block.spans),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )

        is MdBlock.Paragraph -> Text(
            text = rememberStyledText(block.spans),
            style = MaterialTheme.typography.bodyMedium,
        )

        is MdBlock.Bullet -> MarkdownListItem(
            marker = "\u2022",
            markerWidth = 16.dp,
            spans = block.spans,
            depth = block.depth,
        )

        is MdBlock.Numbered -> MarkdownListItem(
            marker = "${block.number}.",
            markerWidth = 24.dp,
            spans = block.spans,
            depth = block.depth,
        )

        is MdBlock.Quote -> MarkdownQuote(block.spans)

        is MdBlock.Code -> MarkdownCode(block.text)

        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun MarkdownListItem(marker: String, markerWidth: Dp, spans: List<MdSpan>, depth: Int) {
    Row(modifier = Modifier.padding(start = (depth * 16).dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(markerWidth),
        )
        Text(
            text = rememberStyledText(spans),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MarkdownQuote(spans: List<MdSpan>) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Text(
            text = rememberStyledText(spans),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun MarkdownCode(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

/** Turns parsed inline spans into styled text: bold, italic, `code` and tappable links. */
@Composable
private fun rememberStyledText(spans: List<MdSpan>): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val uriHandler = LocalUriHandler.current

    return remember(spans, linkColor, codeBackground, uriHandler) {
        // A missing browser must never crash the app, so opening a link is wrapped.
        val onLinkClick = LinkInteractionListener { link ->
            if (link is LinkAnnotation.Url) runCatching { uriHandler.openUri(link.url) }
        }
        val linkStyles = TextLinkStyles(
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )

        buildAnnotatedString {
            for (span in spans) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                )
                val url = span.link?.takeIf {
                    it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
                }
                if (url != null) {
                    withLink(LinkAnnotation.Url(url, linkStyles, onLinkClick)) {
                        withStyle(style) { append(span.text) }
                    }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}
