package com.ydeclik.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale

fun fmtDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "$h h ${m.toString().padStart(2, '0')} min"
        m > 0 -> "$m min"
        else -> "$sec s"
    }
}

@Composable
private fun MenuEntry(label: String, sub: String?, current: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, color = if (current) Orange else Color.Unspecified, fontWeight = FontWeight.SemiBold)
                if (sub != null) Text(sub, fontSize = 12.sp, color = Color.Gray)
            }
        },
        onClick = onClick
    )
}

/** Barre du haut de chaque écran : menu à trois lignes (en haut à gauche) et titre. Ajoutez d'autres entrées ici. */
@Composable
fun HeaderBar(ex: ExerciseViewModel, title: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable { open = true },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val sw = 2.5.dp.toPx()
                    for (i in 0..2) {
                        val y = size.height * (0.2f + 0.3f * i)
                        drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = sw, cap = StrokeCap.Round)
                    }
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MenuEntry("Métronome", null, ex.screen == Screen.METRONOME) { open = false; ex.tab(Screen.METRONOME) }
                MenuEntry("Exercices", null, ex.screen == Screen.EXERCISES) { open = false; ex.tab(Screen.EXERCISES) }
                MenuEntry("Routines", null, ex.screen == Screen.ROUTINES) { open = false; ex.tab(Screen.ROUTINES) }
                MenuEntry("Setlists", null, ex.screen == Screen.SETLISTS) { open = false; ex.tab(Screen.SETLISTS) }
                MenuEntry("Utilisation", "Aujourd'hui : ${fmtDuration(ex.todayAppSec)}", ex.screen == Screen.USAGE) {
                    open = false; ex.tab(Screen.USAGE)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("Auj. ${fmtDuration(ex.todayAppSec)}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
fun UsageScreen(ex: ExerciseViewModel) {
    val days = ex.usageDaysList()
    val maxSec = (days.maxOfOrNull { it.appSec } ?: 1L).coerceAtLeast(1L)
    val today = dayKey()
    val parse = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val show = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeaderBar(ex, "Utilisation")

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Tag("AUJOURD'HUI DANS L'APPLI")
                Text(fmtDuration(ex.todayAppSec), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Orange)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Tag("MÉTRONOME EN MARCHE")
                Text(fmtDuration(ex.todayPlaySec), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            "Le temps compte tant que l'application est à l'écran ou que le métronome joue.",
            fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(days, key = { it.day }) { d ->
                val label = runCatching { show.format(parse.parse(d.day)!!) }.getOrDefault(d.day)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (d.day == today) "$label · aujourd'hui" else label,
                            Modifier.weight(1f), fontSize = 14.sp,
                            fontWeight = if (d.day == today) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(fmtDuration(d.appSec), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth((d.appSec.toFloat() / maxSec).coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (d.day == today) Orange else Color.White.copy(alpha = 0.35f))
                    )
                    Text(
                        "Métronome en marche : ${fmtDuration(d.playSec)}",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/** Bouton de réglage du minuteur global (utilisé dans Exercices et Routines). */
@Composable
fun TimerPill(ex: ExerciseViewModel) {
    var show by remember { mutableStateOf(false) }
    Pill(if (ex.timerMin > 0) "⏱ Minuteur : ${ex.timerMin} min" else "⏱ Minuteur : aucun", on = ex.timerMin > 0) { show = true }
    if (show) {
        TextPrompt(
            "Minuteur global (minutes, 0 = aucun)", ex.timerMin.toString(), numeric = true,
            onDone = { ex.updateTimer(it.toIntOrNull() ?: 0); show = false },
            onDismiss = { show = false }
        )
    }
}
