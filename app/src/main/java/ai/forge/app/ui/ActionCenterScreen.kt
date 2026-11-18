package ai.forge.app.ui

import ai.forge.actions.ActionStatus
import ai.forge.app.AppContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Action Center (§11) : journal réel des actions et arrêt d'urgence. */
@Composable
fun ActionCenterScreen(container: AppContainer) {
    val log by container.actions.log.collectAsState()
    val killed by container.actions.killSwitchEngaged.collectAsState()
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Actions", "Plafond : ${container.gate.ceiling.label}")
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (killed) {
                Text("Arrêt d'urgence actif : aucune action ne sera lancée.", color = ForgePalette.Error)
                Spacer(Modifier.height(8.dp))
                GlassPill("Réactiver les actions") { container.actions.releaseKillSwitch() }
            } else {
                Button(onClick = { container.emergencyStop() }, Modifier.fillMaxWidth().height(52.dp), shape = androidx.compose.foundation.shape.CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ForgePalette.Error.copy(alpha = 0.85f))) { Text("Arrêt d'urgence", style = MaterialTheme.typography.labelLarge) }
            }
            if (log.isEmpty()) Text("Aucune action pour l'instant. Essayez : « Mets un réveil à 7 h ».", Modifier.padding(top = 16.dp), color = ForgePalette.TextMuted)
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = TabBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(log.reversed()) { e ->
                GlassSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), live = false) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${e.tool} — ${statusLabel(e.status)}", color = if (e.status == ActionStatus.SUCCEEDED) ForgePalette.Gold else ForgePalette.Text)
                        Text(e.outcome, style = MaterialTheme.typography.bodySmall)
                        Text("Motif : ${e.rationale} · ${DateFormat.getTimeInstance().format(Date(e.startedAtMs))}",
                            style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted)
                    }
                }
            }
        }
    }
}

private fun statusLabel(s: ActionStatus) = when (s) {
    ActionStatus.PROPOSED -> "proposée"
    ActionStatus.AWAITING_CONFIRMATION -> "en attente de votre confirmation"
    ActionStatus.RUNNING -> "en cours"
    ActionStatus.SUCCEEDED -> "transmise au système"
    ActionStatus.FAILED -> "échec"
    ActionStatus.DENIED -> "refusée (autorisation)"
    ActionStatus.CANCELLED -> "annulée"
    ActionStatus.BLOCKED_BY_KILL_SWITCH -> "bloquée par l'arrêt d'urgence"
}
