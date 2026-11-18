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

/** 1.4: the app's look. 2.5 (owner, 2026-11-17: "A+ is a good mix"): the logo's colors only - navy (#1F3A5F) and amber (#F2B134) -
 *  on a slightly darker navy-tinted grey so the white boxes stand out; amber for every button, switch and the selected tab (navy text
 *  on amber); green tint = good to go (in stock under your cap), red tint = no good (over the cap). Every text color passes the 4.5:1
 *  contrast rule on its background; amber is never text on white (only a fill, or text on navy). */
object Look {
    val Ink = Color(0xFF0E1B2C)
    val Navy = Color(0xFF1F3A5F)             // the logo's navy: the header, links, the selected tab
    val NavyCard = Color(0xFF2B4B74)
    val OnNavyDim = Color(0xFFC9D5E3)
    val Ground = Color(0xFFE4E9F0)           // (navy-tinted grey: the white cards pop)
    val Line = Color(0xFFD8DFE8)
    val Muted = Color(0xFF4A5568)
    val Accent = Color(0xFFF2B134)           // the logo's amber: buttons, switches, the cap knob
    val AccentSoft = Color(0xFFFDF0D3)
    val OnNavyAccent = Color(0xFFF2B134)     // amber text on navy (7:1)
    val Good = Color(0xFF15803D)             // green text: good to go
    val GoodSoft = Color(0xFFDCF3E3)
    val GoodText = Color(0xFF14532D)
    val GoodBorder = Color(0xFF22A55B)
    val BadSoft = Color(0xFFFBE1E1)
    val BadText = Color(0xFF991B1B)
    // (older names, now in the A+ colors)
    val Teal = Navy
    val TealBright = Accent
    val TealSoft = GoodSoft
    val TealText = GoodText
    val Mint = OnNavyAccent
    val AmberSoft = Color(0xFFFEF3C7)        // "needs you" strips
    val AmberText = Color(0xFF78350F)
    val Amber = Color(0xFF92400E)
    val OrangeSoft = BadSoft
    val OrangeText = BadText
    val GreySoft = Color(0xFFEDF0F4)
    val GreyText = Color(0xFF334155)
    val Red = Color(0xFFB91C1C)
    val Green = Good
    val Link = Navy                          // a link to a web page (the TCG price, Best price)

    /** 2.5: the price bar - dark green (cheap) through a light middle to dark red (very expensive). */
    val BarGreen = Color(0xFF15803D)
    val BarGreenLight = Color(0xFF86D9A0)
    val BarMid = Color(0xFFF3F1E6)
    val BarWarm = Color(0xFFF6D3A8)
    val BarSold = Color(0xFFD3E2F2)          // 2.8: the items sold behind the TCG price line (light navy)          // 2.8: between the light middle and light red (a gradual turn)
    val BarRedLight = Color(0xFFF4A3A3)
    val BarRed = Color(0xFFB91C1C)

    val scheme = lightColorScheme(
        primary = Navy, onPrimary = Color.White, primaryContainer = AccentSoft, onPrimaryContainer = Ink,
        secondary = Navy, onSecondary = Color.White, secondaryContainer = AccentSoft, onSecondaryContainer = Ink,
        background = Ground, onBackground = Ink, surface = Color.White, onSurface = Ink, surfaceVariant = Ground, onSurfaceVariant = Muted,
        error = Red, outline = Color(0xFFB8C3D1),
    )
    /** 2.5: a filled button - amber with navy writing. */
    @Composable fun btn() = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink, disabledContainerColor = Line, disabledContentColor = Muted)
    @Composable fun sw() = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White, checkedBorderColor = Accent)
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
