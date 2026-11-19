package com.vipervision.editor

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Done
import com.t8rin.imagetoolbox.core.resources.icons.EmojiSticky
import com.t8rin.imagetoolbox.core.resources.icons.GridOn
import com.t8rin.imagetoolbox.core.resources.icons.ImageSticky
import com.t8rin.imagetoolbox.core.resources.icons.Layers
import com.t8rin.imagetoolbox.core.resources.icons.Redo
import com.t8rin.imagetoolbox.core.resources.icons.StarSticky
import com.t8rin.imagetoolbox.core.resources.icons.TextSticky
import com.t8rin.imagetoolbox.core.resources.icons.Tune
import com.t8rin.imagetoolbox.core.resources.icons.Undo
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.ViperMarkupCanvas
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.ViperMarkupSheets
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.ViperMarkupUiState
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.screenLogic.MarkupLayersComponent

/**
 * One use of Markup layers: Image Toolbox's layered editor ([component]) for text, images,
 * emoji and shapes that stay movable, resizable and rotatable until applied.
 */
class MarkupSession internal constructor(
    val component: MarkupLayersComponent,
    private val lifecycle: LifecycleRegistry
) {
    val ui = ViperMarkupUiState()

    fun close() = lifecycle.destroy()
}

@Composable
internal fun MarkupScreen(
    session: MarkupSession,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val component = session.component
    val ui = session.ui
    BackHandler(onBack = onCancel)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
            Text("Text & layers", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = component::undo, enabled = component.canUndo) {
                Icon(Icons.Rounded.Undo, "Undo", tint = if (component.canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = component::redo, enabled = component.canRedo) {
                Icon(Icons.Rounded.Redo, "Redo", tint = if (component.canRedo) Color.White else Color.Gray)
            }
            IconButton(onClick = onApply) { Icon(Icons.Rounded.Done, "Apply", tint = LocalAccent.current) }
        }

        ViperMarkupCanvas(
            component = component,
            uiState = ui,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Text(
            if (component.layers.any { it.state.isActive }) {
                "Drag, pinch and twist the selected layer · double-tap it to edit"
            } else "Add text, pictures, emoji or shapes · tap one to select it",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp)
        )
        // Snap: a wide, short on/off pill above the bubbles.
        val accent = LocalAccent.current
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp)
                .width(150.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (ui.snappingEnabled) accent else BubbleGrey)
                .clickable { ui.snappingEnabled = !ui.snappingEnabled },
            contentAlignment = Alignment.Center
        ) {
            Text("Snap", color = if (ui.snappingEnabled) accent.contentOn() else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            item { MarkupBubble("Text", Icons.Outlined.TextSticky) { ui.showTextDialog = true } }
            item { MarkupBubble("Picture", Icons.Outlined.ImageSticky) { ui.pickImage() } }
            item { MarkupBubble("Emoji", Icons.Outlined.EmojiSticky) { ui.showEmojiPicker = true } }
            item { MarkupBubble("Shape", Icons.Outlined.StarSticky) { ui.showShapePicker = true } }
            item {
                MarkupBubble("Layers", Icons.Rounded.Layers, selected = ui.showLayersMenu) {
                    if (component.layers.isNotEmpty()) ui.showLayersMenu = !ui.showLayersMenu
                }
            }
            item {
                MarkupBubble("Edit layer", Icons.Rounded.Tune) {
                    if (component.layers.any { it.state.isActive }) ui.showLayerOptions()
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(6.dp))
    }

    ViperMarkupSheets(component, ui)
}

@Composable
private fun MarkupBubble(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (selected) LocalAccent.current else BubbleGrey)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (selected) LocalAccent.current.contentOn() else Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}
