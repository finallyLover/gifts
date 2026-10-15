// ============================================================
// 应用导航骨架（手机端底部标签栏 + 手写导航栈）
//
// 使用 NavigationSuiteScaffold：手机上是底部标签栏，
// 平板 / 折叠屏展开时自动切换为侧边导航栏。
// ============================================================
package com.example.afigts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.vector.ImageVector

/** 页面定义 */
sealed interface Screen {
    data object ArchiveList : Screen
    data class ArchiveEdit(val archiveId: String?) : Screen
    data class Entry(val archiveId: String) : Screen
    data object PersonList : Screen
    data class PersonDetail(val personId: String) : Screen
    data object Search : Screen
    data object Reports : Screen
    data object Settings : Screen
}

private fun Screen.encode(): String = when (this) {
    Screen.ArchiveList -> "archives"
    is Screen.ArchiveEdit -> "edit|${archiveId ?: ""}"
    is Screen.Entry -> "entry|$archiveId"
    Screen.PersonList -> "persons"
    is Screen.PersonDetail -> "person|$personId"
    Screen.Search -> "search"
    Screen.Reports -> "reports"
    Screen.Settings -> "settings"
}

private fun decodeScreen(text: String): Screen {
    val parts = text.split('|')
    return when (parts[0]) {
        "archives" -> Screen.ArchiveList
        "edit" -> Screen.ArchiveEdit(parts.getOrNull(1)?.ifBlank { null })
        "entry" -> Screen.Entry(parts.getOrNull(1).orEmpty())
        "persons" -> Screen.PersonList
        "person" -> Screen.PersonDetail(parts.getOrNull(1).orEmpty())
        "search" -> Screen.Search
        "reports" -> Screen.Reports
        else -> Screen.Settings
    }
}

private data class Tab(val label: String, val icon: ImageVector, val root: Screen)

private val TABS = listOf(
    Tab("档案", Icons.Default.Folder, Screen.ArchiveList),
    Tab("人情", Icons.Default.Person, Screen.PersonList),
    Tab("检索", Icons.Default.Search, Screen.Search),
    Tab("报表", Icons.Default.Analytics, Screen.Reports),
    Tab("设置", Icons.Default.Settings, Screen.Settings)
)

@Composable
fun AFigtsApp() {
    val stack = rememberSaveable(
        saver = listSaver<MutableList<Screen>, String>(
            save = { list -> list.map { it.encode() } },
            restore = { saved -> saved.map { decodeScreen(it) }.toMutableStateList() }
        )
    ) { mutableStateListOf<Screen>(Screen.ArchiveList) }

    val current = stack.last()
    val selectedTab = when (current) {
        Screen.ArchiveList, is Screen.ArchiveEdit, is Screen.Entry -> 0
        Screen.PersonList, is Screen.PersonDetail -> 1
        Screen.Search -> 2
        Screen.Reports -> 3
        Screen.Settings -> 4
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TABS.forEachIndexed { index, tab ->
                item(
                    selected = selectedTab == index,
                    onClick = {
                        stack.clear()
                        stack.add(tab.root)
                    },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) }
                )
            }
        }
    ) {
        when (val s = current) {
            Screen.ArchiveList -> ArchiveListScreen(
                onOpenArchive = { stack.add(Screen.Entry(it)) },
                onNewArchive = { stack.add(Screen.ArchiveEdit(null)) },
                onEditArchive = { stack.add(Screen.ArchiveEdit(it)) }
            )

            is Screen.ArchiveEdit -> ArchiveEditScreen(s.archiveId) {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
            }

            is Screen.Entry -> EntryScreen(s.archiveId) {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
            }

            Screen.PersonList -> PersonListScreen { stack.add(Screen.PersonDetail(it)) }

            is Screen.PersonDetail -> PersonDetailScreen(s.personId) {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
            }

            Screen.Search -> SearchScreen()

            Screen.Reports -> ReportScreen()

            Screen.Settings -> SettingsScreen()
        }
    }
}
