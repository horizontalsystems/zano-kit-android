package io.horizontalsystems.zanokit.sample.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.horizontalsystems.zanokit.BalanceInfo
import io.horizontalsystems.zanokit.SyncState
import io.horizontalsystems.zanokit.sample.MainViewModel

@Composable
fun BalanceScreen(vm: MainViewModel) {
    val syncState by vm.syncStateFlow.collectAsState()
    val balances by vm.balancesFlow.collectAsState()
    val assets by vm.assetsFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Balances", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        SyncStateCard(syncState)
        Spacer(Modifier.height(12.dp))

        if (balances.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No balances yet", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(balances) { balance ->
                    val asset = assets.firstOrNull { it.assetId == balance.assetId }
                    BalanceCard(balance, asset?.ticker ?: "???", asset?.decimalPoint ?: 12)
                }
            }
        }
    }
}

@Composable
fun SyncStateCard(state: SyncState) {
    val (label, color) = when (state) {
        is SyncState.Synced -> "Synced" to MaterialTheme.colorScheme.primary
        is SyncState.Connecting -> "Connecting${if (state.waiting) " (waiting)" else ""}…" to MaterialTheme.colorScheme.secondary
        is SyncState.Syncing -> "Syncing ${state.progress}% (${state.remainingBlocks} blocks left)" to MaterialTheme.colorScheme.tertiary
        is SyncState.NotSynced.NotStarted -> "Not started" to MaterialTheme.colorScheme.outline
        is SyncState.NotSynced.NoNetwork -> "No network" to MaterialTheme.colorScheme.error
        is SyncState.NotSynced.StartError -> "Error: ${state.message}" to MaterialTheme.colorScheme.error
        is SyncState.NotSynced.StatusError -> "Error: ${state.message}" to MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = color,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun BalanceCard(balance: BalanceInfo, ticker: String, decimals: Int) {
    val divisor = Math.pow(10.0, decimals.toDouble())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(ticker, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text("%.${decimals}f".format(balance.total / divisor))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Unlocked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text("%.${decimals}f".format(balance.unlocked / divisor))
            }
            if (balance.awaitingIn > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Incoming", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text("+%.${decimals}f".format(balance.awaitingIn / divisor))
                }
            }
            if (balance.awaitingOut > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Outgoing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text("-%.${decimals}f".format(balance.awaitingOut / divisor))
                }
            }
        }
    }
}
