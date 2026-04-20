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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.horizontalsystems.zanokit.TransactionInfo
import io.horizontalsystems.zanokit.TransactionType
import io.horizontalsystems.zanokit.ZANO_ASSET_ID
import io.horizontalsystems.zanokit.sample.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TransactionsScreen(vm: MainViewModel) {
    val transactions by vm.transactionsFlow.collectAsState()
    val assets by vm.assetsFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Transactions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        if (transactions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No transactions yet", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transactions) { tx ->
                    val ticker = assets.firstOrNull { it.assetId == tx.assetId }?.ticker
                        ?: if (tx.assetId == ZANO_ASSET_ID) "ZANO" else tx.assetId.take(8) + "…"
                    val decimals = assets.firstOrNull { it.assetId == tx.assetId }?.decimalPoint ?: 12
                    TransactionCard(tx, ticker, decimals)
                }
            }
        }
    }
}

@Composable
fun TransactionCard(tx: TransactionInfo, ticker: String, decimals: Int) {
    val divisor = Math.pow(10.0, decimals.toDouble())
    val amountStr = "%.${decimals}f $ticker".format(tx.amount / divisor)
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(tx.timestamp * 1000L))

    val (typeLabel, typeColor) = when (tx.type) {
        TransactionType.incoming -> "IN" to Color(0xFF2E7D32)
        TransactionType.outgoing -> "OUT" to Color(0xFFC62828)
        TransactionType.sentToSelf -> "SELF" to Color(0xFF1565C0)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Type badge
            Surface(
                color = typeColor.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = typeLabel,
                    color = typeColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(amountStr, fontWeight = FontWeight.SemiBold)
                if (tx.isPending) {
                    Text("Pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                tx.recipientAddress?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                tx.memo?.let {
                    Text("\"$it\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Text(dateStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
