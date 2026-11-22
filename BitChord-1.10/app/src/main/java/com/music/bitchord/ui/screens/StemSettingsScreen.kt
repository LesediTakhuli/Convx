package com.music.bitchord.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.smart.stems.DemucsInstall
import com.music.bitchord.playback.smart.stems.DemucsModel
import com.music.bitchord.playback.smart.stems.StemQuality
import com.music.bitchord.playback.smart.stems.StemSeparator
import com.music.bitchord.playback.smart.stems.StemStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun StemSettingsScreen(
    controller: MediaController?,
    currentSong: Song?,
    currentDurationMs: Long,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("bitchord_stem_settings", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(runCatching { StemQuality.valueOf(prefs.getString("quality", StemQuality.FAST.name) ?: StemQuality.FAST.name) }.getOrDefault(StemQuality.FAST)) }
    var modelUrl by remember { mutableStateOf(prefs.getString("demucs_url", "").orEmpty()) }
    var modelInstalled by remember { mutableStateOf(DemucsModel.isInstalled(context)) }
    var modelBytes by remember { mutableStateOf(DemucsModel.sizeBytes(context)) }
    var storageBytes by remember { mutableStateOf(StemStore.totalBytes(context)) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("Choose a separation mode, then split the song currently playing.") }
    var worker by remember { mutableStateOf<Job?>(null) }
    var cancelFlag by remember { mutableStateOf<AtomicBoolean?>(null) }
    var libraryTargets by remember { mutableStateOf<List<Song>>(emptyList()) }
    var showLibraryConfirm by remember { mutableStateOf(false) }
    var libraryProgress by remember { mutableStateOf("") }
    var batchFlag by remember { mutableStateOf<AtomicBoolean?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }

    val currentItem = controller?.currentMediaItem
    val currentUri = currentItem?.localConfiguration?.uri
    val songId = currentSong?.videoId ?: currentItem?.mediaId.orEmpty()
    val seconds = (currentDurationMs / 1_000L).takeIf { it > 0L }?.toInt()
        ?: currentSong?.durationText?.let(TrackMatcher::secondsOf)
        ?: 0

    if (showLibraryConfirm) {
        val durationSeconds = libraryTargets.sumOf { TrackMatcher.secondsOf(it.durationText)?.toLong() ?: 0L }
        val stemsCount = if (quality == StemQuality.DEMUCS && modelInstalled) 4 else 2
        val estimated = StemStore.estimatedBytes(durationSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), stemsCount)
        AlertDialog(
            onDismissRequest = { showLibraryConfirm = false },
            title = { Text("Split local music library?") },
            text = {
                val modelNote = if (quality == StemQuality.DEMUCS && !modelInstalled) {
                    " Demucs is not installed, so this batch will use Fast vocals + instrumental separation."
                } else ""
                Text("BitChord found ${libraryTargets.size} local tracks up to 12 minutes long. Estimated storage: ${formatBytes(estimated)}. This may take a long time and use substantial battery. Only files accessible on this device are included.$modelNote")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLibraryConfirm = false
                    working = true
                    val flag = AtomicBoolean(true)
                    batchFlag = flag
                    batchJob = scope.launch {
                        val thisJob = currentCoroutineContext()[Job]
                        var done = 0
                        var failed = 0
                        var failureMessage: String? = null
                        try {
                            for (track in libraryTargets) {
                                if (thisJob?.isActive != true || !flag.get()) break
                                val uriText = track.localUri ?: track.videoId.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                                val trackSeconds = TrackMatcher.secondsOf(track.durationText) ?: 0
                                if (uriText == null || trackSeconds !in 1..StemSeparator.MAX_SECONDS) {
                                    failed++
                                } else {
                                    val uri = Uri.parse(uriText)
                                    val ok = withContext(Dispatchers.IO) {
                                        StemSeparator.separate(context, track.videoId, uri, trackSeconds, quality) { flag.get() && thisJob?.isActive == true }
                                    }
                                    if (ok) done++ else failed++
                                }
                                libraryProgress = "Processed ${done + failed}/${libraryTargets.size} · $done ready · $failed skipped/failed"
                                storageBytes = StemStore.totalBytes(context)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            flag.set(false)
                            throw cancelled
                        } catch (error: Throwable) {
                            failed++
                            failureMessage = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                        } finally {
                            status = when {
                                failureMessage != null -> "Library split stopped after an error. $done tracks separated; $failed failed. $failureMessage"
                                flag.get() -> "Library split finished. $done tracks separated; $failed skipped or failed."
                                else -> "Library split cancelled. $done tracks separated; $failed skipped or failed."
                            }
                            batchFlag = null
                            batchJob = null
                            working = false
                        }
                    }
                }) { Text("Start splitting") }
            },
            dismissButton = { TextButton(onClick = { showLibraryConfirm = false }) { Text("Cancel") } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp)
            .padding(bottom = contentPadding.calculateBottomPadding() + 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                    Text("Vocal & stem separation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text("Separate the currently playing, locally available track into vocal and backing parts. The four-stem option uses an optional Demucs model.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Separation quality", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quality == StemQuality.FAST, onClick = { quality = StemQuality.FAST; prefs.edit().putString("quality", quality.name).apply() })
                    Column(Modifier.weight(1f)) {
                        Text("Fast · 2 stems", style = MaterialTheme.typography.bodyMedium)
                        Text("Vocals + instrumental", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quality == StemQuality.DEMUCS, onClick = { quality = StemQuality.DEMUCS; prefs.edit().putString("quality", quality.name).apply() })
                    Column(Modifier.weight(1f)) {
                        Text("Demucs · 4 stems", style = MaterialTheme.typography.bodyMedium)
                        Text("Drums + bass + other + vocals", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    onClick = {
                        val item = currentItem
                        val uri = currentUri
                        if (item == null || uri == null || songId.isBlank()) {
                            status = "Play a track that BitChord can access as a local file or a fully cached stream first."
                        } else if (seconds <= 0 || seconds > StemSeparator.MAX_SECONDS) {
                            status = "This track needs a known duration under 12 minutes for separation."
                        } else if (quality == StemQuality.DEMUCS && !modelInstalled) {
                            status = "Install the compatible Demucs model below, or choose Fast separation."
                        } else {
                            val flag = AtomicBoolean(true)
                            cancelFlag = flag
                            working = true
                            status = "Separating ${currentSong?.title ?: item.mediaMetadata.title ?: "track"}… This can take a while."
                            worker = scope.launch {
                                val job = kotlinx.coroutines.currentCoroutineContext()[Job]
                                val ok = withContext(Dispatchers.IO) {
                                    StemSeparator.separate(context, songId, uri, seconds, quality) { flag.get() && job?.isActive == true }
                                }
                                storageBytes = StemStore.totalBytes(context)
                                status = if (ok) "Separation complete. Choose a stem below to play it." else "Separation failed or was cancelled. For streaming audio, make sure the entire track is downloaded or cached."
                                working = false
                                cancelFlag = null
                            }
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (working) CircularProgressIndicator(strokeWidth = 2.dp) else androidx.compose.material3.Icon(Icons.Rounded.MusicNote, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (working) "Separating…" else "Split current song")
                }
                if (cancelFlag != null) OutlinedButton(onClick = {
                    cancelFlag?.set(false)
                    worker?.cancel()
                    working = false
                    status = "Cancelling separation…"
                }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (songId.isNotBlank()) {
                    val names = listOf("vocals", "instrumental", "drums", "bass", "other")
                    names.forEach { name ->
                        val file = StemStore.stem(context, songId, name)
                        if (file.exists()) {
                            OutlinedButton(
                                onClick = {
                                    val item = MediaItem.Builder()
                                        .setMediaId("stem:${songId}:$name")
                                        .setUri(Uri.fromFile(file))
                                        .setMediaMetadata(MediaMetadata.Builder().setTitle("${currentSong?.title.orEmpty()} · ${name.replaceFirstChar { it.uppercase() }}").build())
                                        .build()
                                    controller?.setMediaItem(item)
                                    controller?.prepare()
                                    controller?.play()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Play ${name.replaceFirstChar { it.uppercase() }}") }
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Split local library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Batch-separate audio files on this device. This does not attempt to process remote streaming tracks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (libraryProgress.isNotBlank()) Text(libraryProgress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (batchFlag != null) {
                    TextButton(onClick = {
                        batchFlag?.set(false)
                    }) { Text("Cancel library split") }
                }
                OutlinedButton(
                    onClick = {
                        working = true
                        status = "Scanning local music…"
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { LocalMediaRepository.getLocalMusic(context) }
                                .filter { track ->
                                    val uriText = track.localUri ?: track.videoId.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                                    val length = TrackMatcher.secondsOf(track.durationText) ?: 0
                                    uriText != null && length in 1..StemSeparator.MAX_SECONDS
                                }
                            libraryTargets = found
                            working = false
                            if (found.isEmpty()) {
                                status = "No supported local tracks found. Check BitChord's music-library permission and make sure local tracks have readable durations."
                            } else {
                                showLibraryConfirm = true
                                status = "Found ${found.size} local tracks ready for batch separation."
                            }
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan & split local library") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Icon(Icons.Rounded.Download, contentDescription = null)
                    Text("Demucs model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (modelInstalled) "Installed · ${formatBytes(modelBytes)}" else "Not installed. Fast separation works without this model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelUrl,
                    onValueChange = { modelUrl = it; prefs.edit().putString("demucs_url", it).apply() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Compatible htdemucs ONNX model URL") },
                    placeholder = { Text("https://…/htdemucs.onnx") },
                    singleLine = true,
                )
                Text("The model file is not included in the Convx archive. Use a direct download link to a compatible htdemucs ONNX export; BitChord checks the model before keeping it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (working && modelUrl.isNotBlank()) Text("Working… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Button(
                    onClick = {
                        if (modelUrl.isBlank()) {
                            status = "Enter a direct model URL first."
                        } else {
                            working = true
                            status = "Downloading and checking Demucs model…"
                            progress = 0f
                            scope.launch {
                                val result = DemucsModel.install(context, modelUrl) { value ->
                                    scope.launch(Dispatchers.Main) { progress = value }
                                }
                                modelInstalled = DemucsModel.isInstalled(context)
                                modelBytes = DemucsModel.sizeBytes(context)
                                status = when (result) {
                                    DemucsInstall.OK -> "Demucs model installed and passed its compatibility check."
                                    DemucsInstall.DOWNLOAD_FAILED -> "Model download failed. Check the URL and connection."
                                    DemucsInstall.INCOMPATIBLE -> "The downloaded ONNX model is not compatible with this Demucs implementation; it was removed."
                                }
                                working = false
                            }
                        }
                    },
                    enabled = !working && !modelInstalled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download & verify model") }
                if (modelInstalled) TextButton(onClick = {
                    DemucsModel.delete(context)
                    modelInstalled = false
                    modelBytes = 0L
                    status = "Demucs model removed. Fast two-stem separation remains available."
                }, enabled = !working) { Text("Delete Demucs model") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stem storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(formatBytes(storageBytes), style = MaterialTheme.typography.headlineSmall)
                Text("Saved separated audio is stored privately in BitChord's app storage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    StemStore.clear(context)
                    storageBytes = 0L
                    status = "All saved stems cleared."
                }, enabled = storageBytes > 0L && !working, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Clear stem storage")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
