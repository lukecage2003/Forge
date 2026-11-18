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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Action Center", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (killed) {
            Text("Arrêt d'urgence actif : aucune action ne sera lancée.", color = ForgePalette.Error)
            OutlinedButton(onClick = { container.actions.releaseKillSwitch() }) { Text("Réactiver les actions") }
        } else {
            Button(onClick = { container.emergencyStop() }, colors = ButtonDefaults.buttonColors(containerColor = ForgePalette.Error)) {
                Text("ARRÊT D'URGENCE")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Plafond d'autonomie : ${container.gate.ceiling.label}", color = ForgePalette.TextMuted)
        Spacer(Modifier.height(12.dp))
        if (log.isEmpty()) Text("Aucune action pour l'instant.", color = ForgePalette.TextMuted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(log.reversed()) { e ->
                Card(colors = CardDefaults.cardColors(containerColor = ForgePalette.Surface)) {
                    Column(Modifier.padding(12.dp)) {
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
