package com.okayanshul.docaction.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Six type roles, and no more.
 *
 * If a seventh is needed, that is a signal the hierarchy is wrong rather than that the scale
 * is short. The platform font is used deliberately: it is excellent, free, and already
 * loaded, and a custom face would cost startup time for no legibility gain.
 */
@Immutable
data class DocActionType(
    val display: TextStyle,
    val title: TextStyle,
    val subject: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val label: TextStyle,
) {
    companion object {
        /**
         * Times and counts use **tabular figures**. A column of times that doesn't align is
         * the cheapest way to look unpolished, and this screen is nothing but columns of
         * times.
         */
        private const val TABULAR = "tnum"

        val Default = DocActionType(
            display = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = TABULAR,
            ),
            title = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            subject = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
            ),
            body = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal,
            ),
            meta = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
                fontFeatureSettings = TABULAR,
            ),
            label = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

val LocalType = staticCompositionLocalOf { DocActionType.Default }

/** Material's own scale, mapped onto ours so stock components stay consistent. */
internal fun materialTypography(type: DocActionType) = Typography(
    displaySmall = type.display,
    headlineMedium = type.title,
    titleMedium = type.subject,
    bodyLarge = type.body,
    bodyMedium = type.body,
    bodySmall = type.meta,
    labelLarge = type.label,
    labelMedium = type.label,
)
