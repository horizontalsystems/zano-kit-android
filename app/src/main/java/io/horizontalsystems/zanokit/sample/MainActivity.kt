package io.horizontalsystems.zanokit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.horizontalsystems.zanokit.sample.ui.BalanceScreen
import io.horizontalsystems.zanokit.sample.ui.SendScreen
import io.horizontalsystems.zanokit.sample.ui.TransactionsScreen
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        enableEdgeToEdge()
        setContent { ZanoApp(viewModel) }
    }
}

private sealed class Screen(val route: String, val label: String) {
    object Balance : Screen("balance", "Balance")
    object Transactions : Screen("transactions", "Transactions")
    object Send : Screen("send", "Send")
}

@Composable
fun ZanoApp(vm: MainViewModel) {
    val navController = rememberNavController()
    val screens = listOf(Screen.Balance, Screen.Transactions, Screen.Send)
    val icons = listOf(
        Icons.Default.Home,
        Icons.Default.Info,
        Icons.Default.Share,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                screens.forEachIndexed { i, screen ->
                    NavigationBarItem(
                        icon = { Icon(icons[i], contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = current?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Screen.Balance.route,
            modifier = Modifier.padding(inner),
        ) {
            composable(Screen.Balance.route) { BalanceScreen(vm) }
            composable(Screen.Transactions.route) { TransactionsScreen(vm) }
            composable(Screen.Send.route) { SendScreen(vm) }
        }
    }
}
