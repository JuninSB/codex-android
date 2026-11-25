package local.codex.mobile.ui

import androidx.annotation.DrawableRes
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
    val Elevated = Color(0xFF303030)
    val Divider = Color(0xFF424242)
    val Text = Color(0xFFFAFAFA)
    val Muted = Color(0xFFA1A1AA)
    val Accent = Color(0xFF3563E9)
    val Link = Color(0xFFAFC4FF)
}
private val palette = darkColorScheme(
    primary = Ink.Accent, onPrimary = Color.White, primaryContainer = Color(0xFF172746),
    onPrimaryContainer = Ink.Text, background = Ink.Background, onBackground = Ink.Text,
    surface = Ink.Surface, onSurface = Ink.Text, onSurfaceVariant = Ink.Muted,
    surfaceContainer = Ink.Surface, surfaceContainerHigh = Ink.Elevated, surfaceVariant = Ink.Elevated,
    outline = Ink.Divider, outlineVariant = Ink.Divider
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
    colorScheme = palette, typography = typography, content = content
)

@Composable internal fun Symbol(
    @DrawableRes id: Int,
    description: String?,
    modifier: Modifier = Modifier,
    color: Color = Ink.Text
) = Icon(painterResource(id), description, modifier.size(24.dp), tint = color)
