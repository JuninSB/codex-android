package local.codex.mobile.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Shapes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object Ink {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF212121)
    val Control = Color(0xFF303030)
    val Elevated = Color(0xFF383838)
    val Divider = Color(0xFF424242)
    val Contour = Color(0xFF494949)
    val Text = Color(0xFFFAFAFA)
    val Muted = Color(0xFFAFAFAF)
    val Accent = Color(0xFF345FE6)
    val Link = Color(0xFFB6C8FF)
}
private val palette = darkColorScheme(
    primary = Ink.Accent, onPrimary = Color.White, primaryContainer = Color(0xFF232E48),
    onPrimaryContainer = Ink.Text, background = Ink.Background, onBackground = Ink.Text,
    surface = Ink.Surface, onSurface = Ink.Text, onSurfaceVariant = Ink.Muted,
    surfaceContainer = Ink.Surface, surfaceContainerHigh = Ink.Elevated, surfaceVariant = Ink.Elevated,
    outline = Ink.Contour, outlineVariant = Ink.Divider
)
private val body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 25.sp)
internal val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
private val typography = Typography(
    bodyLarge = body,
    bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 21.sp),
    titleMedium = body.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = body.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = body.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
)
@Composable internal fun MobileTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = palette, typography = typography,
    shapes = Shapes(extraSmall = RoundedCornerShape(14.dp), small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)), content = content
)

@Composable internal fun Symbol(
    @DrawableRes id: Int,
    description: String?,
    modifier: Modifier = Modifier,
    color: Color = Ink.Text
) = Icon(painterResource(id), description, modifier.size(24.dp), tint = color)

@Composable internal fun ControlButton(
    @DrawableRes id: Int,
    description: String,
    onClick: () -> Unit
) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        border = BorderStroke(1.dp, Ink.Contour),
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Ink.Control)
    ) { Symbol(id, description) }
}
