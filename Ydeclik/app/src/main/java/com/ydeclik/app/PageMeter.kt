package com.ydeclik.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Fenêtre des réglages du métronome pour la page affichée : métrique, accents, subdivisions, silence et ajout.
 * Toute modification est enregistrée pour cette page ([onChanged]).
 */
@Composable
fun PageMeterDialog(vm: MetronomeViewModel, onChanged: () -> Unit, onClose: () -> Unit) {
    // Les changements de BPM ne comptent pas : le BPM a déjà son propre enregistrement.
    val snapshot = vm.cfg.copy(bpm = 0)
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(snapshot) {
        if (first) first = false else onChanged()
    }
    val groups = vm.cfg.sig.groupings
    val groupingLabel = groups[vm.cfg.grouping.coerceIn(0, groups.size - 1)].joinToString("+")

    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = { Text("Réglages de cette page") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Métrique", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Pal.fg.copy(alpha = 0.6f))
                TimeSig.ALL.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { s -> Pill(s.label, on = vm.cfg.sig == s) { vm.setSig(s) } }
                    }
                }
                if (groups.size > 1) Pill("Groupement " + groupingLabel) { vm.cycleGrouping() }

                Text(
                    "Subdivisions et accents", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = Pal.fg.copy(alpha = 0.6f)
                )
                BeatsRow(vm)
                Text(
                    "Toucher la note : subdivision · > : accent · ‖ : silence",
                    fontSize = 11.sp, color = Pal.fg.copy(alpha = 0.5f)
                )

                Text("Silence / Ajout", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Pal.fg.copy(alpha = 0.6f))
                AutoPanel(vm)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer", color = Accent) } }
    )
}
