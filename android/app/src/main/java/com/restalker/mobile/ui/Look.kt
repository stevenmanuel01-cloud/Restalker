package com.restalker.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 1.4: the app's look (the design the owner was shown): a dark navy header, white cards on light grey, one teal accent, amber for
 *  "needs you", soft chips for the stock state. Every text color passes the 4.5:1 contrast rule on its background. */
object Look {
    val Ink = Color(0xFF0F172A)
    val Navy = Color(0xFF0E1B2C)
    val NavyCard = Color(0xFF16283F)
    val OnNavyDim = Color(0xFFA5B4C8)
    val Ground = Color(0xFFF3F4F6)
    val Line = Color(0xFFE2E8F0)
    val Muted = Color(0xFF475569)
    val Teal = Color(0xFF0F766E)
    val TealBright = Color(0xFF14B8A6)
    val TealSoft = Color(0xFFCCFBF1)
    val TealText = Color(0xFF115E59)
    val Mint = Color(0xFF5EEAD4)
    val AmberSoft = Color(0xFFFEF3C7)
    val AmberText = Color(0xFF78350F)
    val Amber = Color(0xFF92400E)
    val OrangeSoft = Color(0xFFFFEDD5)
    val OrangeText = Color(0xFF9A3412)
    val GreySoft = Color(0xFFE2E8F0)
    val GreyText = Color(0xFF334155)
    val Red = Color(0xFFB91C1C)
    val Green = Color(0xFF16A34A)          // 2.0: the price bar under the cap

    val scheme = lightColorScheme(
        primary = Teal, onPrimary = Color.White, primaryContainer = TealSoft, onPrimaryContainer = TealText,
        secondary = Navy, onSecondary = Color.White, secondaryContainer = TealSoft, onSecondaryContainer = TealText,
        background = Ground, onBackground = Ink, surface = Color.White, onSurface = Ink, surfaceVariant = Ground, onSurfaceVariant = Muted,
        error = Red, outline = Color(0xFFCBD5E1),
    )
    val card = RoundedCornerShape(16.dp)
}

@Composable
fun RkTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Look.scheme, content = content)

/** A small rounded label (the stock state of a product). */
@Composable
fun Chip(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
}

/** An amber strip: something needs you (sign in, notifications off, ...). */
@Composable
fun Banner(text: String, button: String?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).background(Look.AmberSoft, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Look.AmberText, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        if (button != null) Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Look.Amber),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), modifier = Modifier.padding(start = 8.dp).heightIn(min = 40.dp).testTag(tag)) {
            Text(button, fontSize = 13.sp, maxLines = 1) }
    }
}
