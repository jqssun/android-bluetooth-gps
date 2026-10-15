package io.github.jqssun.btgps

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/* NavDisplay needs api 24 */
@Composable
fun <T : Any> PageStack(stack: SnapshotStateList<T>, content: @Composable (T) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        NavDisplay(backStack = stack, entryProvider = { key -> NavEntry(key) { content(it) } })
    } else {
        BackHandler(stack.size > 1) { stack.removeLastOrNull() }
        content(stack.last())
    }
}
