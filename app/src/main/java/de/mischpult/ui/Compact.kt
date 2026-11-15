package de.mischpult.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DialogShape = RoundedCornerShape(6.dp)
private val FieldShape = RoundedCornerShape(3.dp)

/**
 * A dialog as a console module: graphite plate with screws, engraved title,
 * pads for buttons (the confirm pad's LED lit). Same slots as Material's AlertDialog.
 */
@Composable
fun DjDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier
                .padding(18.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .shadow(16.dp, DialogShape)
                .panel()
                .drawWithContent {
                    drawContent()
                    // Four screws hold the module in the console
                    val r = 3.5.dp.toPx()
                    val inset = 7.dp.toPx() + r
                    for (p in listOf(Offset(inset, inset), Offset(size.width - inset, inset),
                        Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset))) {
                        drawCircle(Color(0xFF2E3134), r, p)
                        drawLine(Color.Black.copy(alpha = 0.6f), p - Offset(r * 0.6f, 0f), p + Offset(r * 0.6f, 0f), 1.dp.toPx())
                    }
                }
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides DjColors.text) {
                title?.let {
                    Box(Modifier.padding(bottom = 8.dp)) {
                        ProvideTextStyle(TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)) { it() }
                    }
                    // Engraved divider under the module name
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.6f)))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
                    Spacer(Modifier.height(10.dp))
                }
                text?.let {
                    Box(Modifier.weight(1f, fill = false)) {
                        ProvideTextStyle(TextStyle(fontSize = 13.sp, lineHeight = 18.sp)) { it() }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                ProvideDefaultPad { confirmButton() }
            }
        }
    }
}

/** Single-line text field, 40 dp high instead of Material's 56, with an optional small label above. */
@Composable
fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Input and placeholder share one style (the theme's font, one line height),
    // so the cursor sits exactly where the placeholder's first letter starts
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 14.sp, lineHeight = 18.sp, color = DjColors.text))
    Column(modifier) {
        label?.let { Text(it, fontSize = 11.sp, color = DjColors.textDim, modifier = Modifier.padding(bottom = 2.dp)) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(DjColors.deckA),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        // Border outside the display window, so its top shadow can't cover the focus line
                        .border(1.dp, if (focused) DjColors.deckA.copy(alpha = 0.7f) else Color.Transparent, FieldShape)
                        .lcd()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.let {
                        Icon(it, null, tint = DjColors.textDim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = style, color = DjColors.textDim, maxLines = 1)
                        }
                        inner()
                    }
                }
            },
        )
    }
}
