package dev.m4s4t0.microboy.ui.saves

import dev.m4s4t0.microboy.ui.theme.uiPill
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.m4s4t0.microboy.data.StateSlot
import dev.m4s4t0.microboy.ui.common.Format
import dev.m4s4t0.microboy.ui.common.StateThumbnail
import dev.m4s4t0.microboy.ui.common.slotName

/** Operations the host screen supports; null hides the action. */
class SlotActions(
    val onSave: ((Int) -> Unit)? = null,
    val onLoad: ((Int) -> Unit)? = null,
    val onDelete: ((Int) -> Unit)? = null,
    val onExport: ((Int) -> Unit)? = null,
    val onImport: ((Int) -> Unit)? = null,
)

enum class SlotMode { SAVE, LOAD, BROWSE }

/**
 * Grid of save-state slots with screenshots. Every destructive action
 * (overwrite, load over unsaved progress, delete) asks for confirmation.
 */
@Composable
fun SaveSlotGrid(
    slots: List<StateSlot>,
    mode: SlotMode,
    actions: SlotActions,
    modifier: Modifier = Modifier,
    defaultSlot: Int = 1,
) {
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val visible = if (mode == SlotMode.SAVE) slots.filter { !it.isAuto } else slots

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        items(visible, key = { it.slot }) { slot ->
            SlotCard(
                slot = slot,
                highlighted = slot.slot == defaultSlot,
                onClick = {
                    when (mode) {
                        SlotMode.SAVE -> actions.onSave?.let { save ->
                            if (slot.exists) confirm = "Overwrite ${slotName(slot.slot)}? The state saved ${Format.relative(slot.modified)} will be replaced." to { save(slot.slot) }
                            else save(slot.slot)
                        }
                        SlotMode.LOAD, SlotMode.BROWSE -> if (slot.exists) actions.onLoad?.let { load ->
                            confirm = "Load ${slotName(slot.slot)}? Progress since your last save will be lost." to { load(slot.slot) }
                        }
                    }
                },
                onDelete = actions.onDelete?.takeIf { slot.exists }?.let { del ->
                    { confirm = "Delete ${slotName(slot.slot)}? This can't be undone." to { del(slot.slot) } }
                },
                onExport = actions.onExport?.takeIf { slot.exists }?.let { { it(slot.slot) } },
                onImport = actions.onImport?.takeIf { !slot.isAuto }?.let { imp ->
                    {
                        if (slot.exists) confirm = "Importing will overwrite ${slotName(slot.slot)}. Continue?" to { imp(slot.slot) }
                        else imp(slot.slot)
                    }
                },
            )
        }
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Are you sure?") },
            text = { Text(msg) },
            confirmButton = { TextButton(shape = uiPill(), onClick = { confirm = null; action() }) { Text("Continue") } },
            dismissButton = { TextButton(shape = uiPill(), onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotCard(
    slot: StateSlot,
    highlighted: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onImport: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(
            if (highlighted) 2.dp else 1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f)) {
                if (slot.exists) {
                    StateThumbnail(slot.thumbnail, slot.modified, Modifier.fillMaxWidth().aspectRatio(1.5f))
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.5f), contentAlignment = Alignment.Center) {
                        Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(slotName(slot.slot), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (slot.exists) Format.relative(slot.modified) else "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onDelete != null || onExport != null || onImport != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Slot options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            onExport?.let { DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; it() }) }
                            onImport?.let { DropdownMenuItem(text = { Text("Import into this slot…") }, onClick = { menu = false; it() }) }
                            onDelete?.let { DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; it() }) }
                        }
                    }
                }
            }
        }
    }
}
