package io.horizontalsystems.zanokit.sample.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.horizontalsystems.zanokit.ZANO_ASSET_ID
import io.horizontalsystems.zanokit.sample.MainViewModel
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(vm: MainViewModel) {
    val assets by vm.assetsFlow.collectAsState()
    val sendResult by vm.sendResult.collectAsState()
    val sendError by vm.sendError.collectAsState()

    var toAddress by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var selectedAssetId by remember { mutableStateOf(ZANO_ASSET_ID) }
    var assetDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Send", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = toAddress,
            onValueChange = { toAddress = it },
            label = { Text("Recipient address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Asset selector
        if (assets.size > 1) {
            val selectedTicker = assets.firstOrNull { it.assetId == selectedAssetId }?.ticker ?: "ZANO"
            ExposedDropdownMenuBox(
                expanded = assetDropdownExpanded,
                onExpandedChange = { assetDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedTicker,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Asset") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(assetDropdownExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = assetDropdownExpanded,
                    onDismissRequest = { assetDropdownExpanded = false },
                ) {
                    assets.forEach { asset ->
                        DropdownMenuItem(
                            text = { Text(asset.ticker) },
                            onClick = {
                                selectedAssetId = asset.assetId
                                assetDropdownExpanded = false
                            },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (atomic units)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            label = { Text("Memo (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = {
                vm.clearSendState()
                vm.send(toAddress, amount, memo)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = toAddress.isNotBlank() && amount.isNotBlank(),
        ) {
            Text("Send")
        }

        sendResult?.let { txHash ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Sent!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("tx: $txHash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        sendError?.let { error ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
