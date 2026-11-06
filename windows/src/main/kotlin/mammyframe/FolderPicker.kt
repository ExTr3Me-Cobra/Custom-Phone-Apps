package mammyframe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Ink3 = Color(0xFF4A3A3F)

/** A place to start from: a name and a folder. */
private data class Place(val label: String, val dir: File)

/** Common places on a Windows PC, then every drive. Only ones that exist are shown. */
private fun places(): List<Place> {
    val home = File(System.getProperty("user.home"))
    val oneDrive = System.getenv("OneDrive")?.let(::File)
    val list = listOfNotNull(
        Place("Pictures", File(home, "Pictures")),
        oneDrive?.let { Place("OneDrive Pictures", File(it, "Pictures")) },
        Place("Desktop", File(home, "Desktop")),
        oneDrive?.let { Place("OneDrive Desktop", File(it, "Desktop")) },
        Place("Downloads", File(home, "Downloads")),
        Place("Documents", File(home, "Documents")),
        oneDrive?.let { Place("OneDrive", it) },
    ).filter { it.dir.isDirectory }
    val drives = File.listRoots().orEmpty().map { Place("Drive ${it.path.trimEnd('\\', '/')}", it) }
    return list + drives
}

/** What's in a folder: its subfolders (not hidden or system ones), and how many photos are right in it. */
private suspend fun look(dir: File): Pair<List<File>, Int> = withContext(Dispatchers.IO) {
    val all = runCatching { dir.listFiles() }.getOrNull().orEmpty()
    val folders = all.filter {
        it.isDirectory && !it.isHidden && !it.name.startsWith(".") && !it.name.startsWith("$") &&
            it.name != "System Volume Information"
    }.sortedBy { it.name.lowercase() }
    val photos = all.count { it.isFile && it.extension.lowercase() in Photos.extensions }
    folders to photos
}

/**
 * Choosing the photos folder, inside Mammy Frame (big rows, works by touch or mouse). Starts in
 * the folder chosen before, if any; [onPick] gets the folder, [onCancel] closes without changing.
 */
@Composable
fun FolderPicker(start: String?, onPick: (String) -> Unit, onCancel: () -> Unit) {
    var dir by remember { mutableStateOf(start?.let(::File)?.takeIf { it.isDirectory }) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var photosHere by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    val startPlaces = remember { places() }

    LaunchedEffect(dir) {
        val d = dir ?: return@LaunchedEffect
        loading = true
        val (f, p) = look(d)
        folders = f
        photosHere = p
        loading = false
        Log.i("Folder picker: looking in $d (${f.size} folders, $p photos)")
    }

    // Dim the screen behind; clicking outside the box does nothing (so nothing is chosen by accident).
    Box(
        Modifier.fillMaxSize().background(Color(0x88000000)).pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = Color(0xFFFFFBF7), shadowElevation = 8.dp,
            modifier = Modifier.padding(24.dp).widthIn(max = 760.dp).fillMaxWidth().heightIn(max = 640.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Photos folder", fontFamily = Script, fontSize = 40.sp, color = Rose)
                Text(
                    dir?.path ?: "Choose where the photos are",
                    fontSize = 15.sp, color = Ink3.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))

                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    val d = dir
                    if (d == null) {
                        items(startPlaces, key = { it.dir.path + it.label }) { p ->
                            Row2(p.label, bold = true) { dir = p.dir }
                        }
                    } else {
                        item {
                            Row2(if (d.parentFile != null) "⬑  Up to ${d.parentFile.name.ifEmpty { d.parentFile.path }}" else "⬑  Back to places") {
                                dir = d.parentFile
                            }
                        }
                        if (!loading && folders.isEmpty()) item {
                            Text("No folders inside this one.", fontSize = 15.sp, color = Ink3.copy(alpha = 0.6f),
                                modifier = Modifier.padding(14.dp))
                        }
                        items(folders, key = { it.path }) { f -> Row2("📁  ${f.name}") { dir = f } }
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (dir != null) {
                    Text(
                        if (loading) "Looking…"
                        else "%,d photo%s right in this folder (photos in the folders inside it count too)."
                            .format(photosHere, if (photosHere == 1) "" else "s"),
                        fontSize = 14.sp, color = Ink3.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { dir?.let { onPick(it.absolutePath) } }, enabled = dir != null) {
                        Text("Use this folder", fontSize = 17.sp)
                    }
                    OutlinedButton(onClick = onCancel) { Text("Cancel", fontSize = 17.sp) }
                    Spacer(Modifier.weight(1f))
                    if (dir != null) TextButton(onClick = { dir = null }) { Text("Places", fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun Row2(text: String, bold: Boolean = false, onClick: () -> Unit) {
    Column {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Text(text, fontSize = 18.sp, color = Ink3, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = Ink3.copy(alpha = 0.08f))
    }
}

