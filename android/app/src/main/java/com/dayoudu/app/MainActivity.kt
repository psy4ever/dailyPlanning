package com.dayoudu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dayoudu.app.data.NutritionDatabase
import com.dayoudu.app.data.NutritionRepository
import com.dayoudu.app.data.PlanningRepository
import com.dayoudu.app.data.SleepRepository
import com.dayoudu.app.data.BackupRepository
import com.dayoudu.app.data.LedgerRepository
import com.dayoudu.app.data.LocalUserRepository

internal val Pine = Color(0xFF145747)
internal val PineDeep = Color(0xFF0D4036)
internal val Ink = Color(0xFF172D2D)
internal val MutedInk = Color(0xFF718080)
internal val Cream = Color(0xFFFAF9F5)
internal val Mist = Color(0xFFE8F2E7)
internal val Sage = Color(0xFF95AA91)
internal val Gold = Color(0xFFE5AA4D)
internal val Peach = Color(0xFFFFF0E4)
internal val SoftLine = Color(0xFFE8EBE5)

private enum class MainTab(val title: String, val icon: Int) {
    TODAY("今天", R.drawable.tab_today), RECORD("记录", R.drawable.tab_record),
    MINE("我的", R.drawable.tab_mine)
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        val nutrition = NutritionRepository(NutritionDatabase.get(applicationContext))
        val planning = PlanningRepository(NutritionDatabase.get(applicationContext), applicationContext)
        val sleep = SleepRepository(NutritionDatabase.get(applicationContext))
        val backup = BackupRepository(applicationContext, NutritionDatabase.get(applicationContext))
        val ledger = LedgerRepository(NutritionDatabase.get(applicationContext))
        val localUser = LocalUserRepository(NutritionDatabase.get(applicationContext))
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Pine, onSurface = Ink, background = Cream,
                    surface = Color.White, secondary = Sage, tertiary = Gold, outline = SoftLine,
                    surfaceVariant = Mist, surfaceContainer = Color.White,
                    surfaceContainerLow = Color.White, surfaceContainerHigh = Color.White,
                    primaryContainer = Mist, onPrimaryContainer = PineDeep,
                    onSurfaceVariant = MutedInk, outlineVariant = SoftLine),
                typography = Typography(),
            ) {
                DailyRhythmApp(nutrition, planning, sleep, backup, ledger, localUser,
                    onRestored = { recreate() })
            }
        }
    }
}

@Composable
private fun DailyRhythmApp(nutrition: NutritionRepository, planning: PlanningRepository,
                           sleep: SleepRepository, backup: BackupRepository,
                           ledger: LedgerRepository, localUser: LocalUserRepository,
                           onRestored: () -> Unit) {
    var selectedTab by remember { mutableStateOf(MainTab.TODAY) }
    Scaffold(containerColor = Cream,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Pine, selectedTextColor = Pine,
                            indicatorColor = Mist, unselectedIconColor = MutedInk,
                            unselectedTextColor = MutedInk),
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            MainTab.TODAY -> TodayScreen(planning, padding)
            MainTab.RECORD -> RecordScreen(nutrition, sleep, ledger, padding)
            MainTab.MINE -> MineScreen(localUser, backup, padding, onRestored)
        }
    }
}
