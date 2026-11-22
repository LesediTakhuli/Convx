package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.concert.ConcertAiSettings
import com.music.bitchord.playback.concert.ConcertQueuePreparer
import com.music.bitchord.playback.concert.ConcertSession
import com.music.bitchord.playback.concert.ConcertSetBuilder
import com.music.bitchord.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConcertModeScreen(
    controller: MediaController?,
    currentSong: Song?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var concept by remember(currentSong?.videoId) { mutableStateOf(currentSong?.artist?.takeIf { it.isNotBlank() }.orEmpty()) }
    var count by remember { mutableStateOf(12f) }
    var includeHost by remember { mutableStateOf(true) }
    var apiKey by remember { mutableStateOf(ConcertAiSettings.apiKey(context)) }
    var baseUrl by remember { mutableStateOf(ConcertAiSettings.baseUrl(context)) }
    var model by remember { mutableStateOf(ConcertAiSettings.model(context)) }
    var showAiSettings by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Build a setlist from an artist, mood or concert idea.") }
    var result by remember { mutableStateOf<ConcertSetBuilder.ResultSet?>(null) }

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
                    androidx.compose.material3.Icon(Icons.Rounded.MusicNote, contentDescription = null)
                    Text("Build your concert", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Create a live-style running order. BitChord can play selected song highlights and introduce each track with a spoken host.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = concept,
                    onValueChange = { concept = it; result = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Artist or concert theme") },
                    placeholder = { Text("e.g. Kendrick Lamar, 2000s R&B, summer festival") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${count.toInt()} songs", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("4–30", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
                Slider(value = count, onValueChange = { count = it }, valueRange = 4f..30f, steps = 25)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeHost, onCheckedChange = { includeHost = it })
                    Column(Modifier.weight(1f)) {
                        Text("Spoken concert host", style = MaterialTheme.typography.bodyMedium)
                        Text("Uses your phone's text-to-speech voice for intros and the finale", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    onClick = {
                        if (loading) return@Button
                        loading = true
                        result = null
                        status = "Searching for tracks…"
                        scope.launch {
                            val built = ConcertSetBuilder.build(context, concept, currentSong, count.toInt()) { message ->
                                scope.launch(Dispatchers.Main) { status = message }
                            }
                            result = built
                            status = if (built == null) "No concert set could be built. Try another artist or theme." else "${built.tracks.size} tracks arranged for your concert."
                            loading = false
                        }
                    },
                    enabled = !loading && concept.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) CircularProgressIndicator(strokeWidth = 2.dp) else androidx.compose.material3.Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (loading) "Building setlist…" else "Generate concert setlist")
                }
                TextButton(onClick = { showAiSettings = !showAiSettings }) {
                    Text(if (showAiSettings) "Hide AI settings" else "AI setlist settings · OpenRouter")
                }
                if (showAiSettings) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("OpenRouter API key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Chat completions URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TextButton(onClick = {
                        ConcertAiSettings.save(context, apiKey, baseUrl, model)
                        status = "AI settings saved. Your API key is stored in BitChord's encrypted credential store."
                        showAiSettings = false
                    }) { Text("Save AI settings") }
                    Text("An API key is optional. Without one, BitChord arranges its search results into a basic concert order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { set ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(set.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (set.aiDesigned) "AI-arranged running order" else "Search-result running order", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    set.tracks.forEachIndexed { index, track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(track.song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(track.song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(track.role.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (set.unmatched.isNotEmpty()) {
                        Text("Some AI suggestions were not found in the catalogue: ${set.unmatched.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            val activeController = controller
                            if (activeController == null) {
                                status = "BitChord's player is not connected yet."
                            } else {
                                loading = true
                                status = "Preparing concert clips and host announcements…"
                                scope.launch {
                                    val items = set.tracks.map { it.song.toMediaItem() }
                                    val prepared = ConcertQueuePreparer(context).prepare(
                                        title = set.title,
                                        sourceItems = items,
                                        includeHost = includeHost,
                                        sourceRoles = set.tracks.map { it.role },
                                    ) { progress ->
                                        scope.launch(Dispatchers.Main) {
                                            status = when (progress.stage) {
                                                ConcertQueuePreparer.Progress.Stage.PLANNING -> "Preparing song clips ${progress.done}/${progress.total}…"
                                                ConcertQueuePreparer.Progress.Stage.NARRATING -> "Recording host announcements ${progress.done}/${progress.total}…"
                                            }
                                        }
                                    }
                                    if (prepared == null) {
                                        status = "The concert could not be prepared."
                                    } else {
                                        activeController.setMediaItems(prepared.items, 0, 0L)
                                        activeController.prepare()
                                        ConcertSession.begin(prepared.show)
                                        activeController.play()
                                        status = if (prepared.narrated) "Concert started — your host is ready." else "Concert started. Your device did not provide a usable TTS voice, so it will play without spoken announcements."
                                    }
                                    loading = false
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start concert in BitChord") }
                }
            }
        }
        Text(
            "Concert playback uses short clips from each selected song. Playback continues through BitChord's normal player and queue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
