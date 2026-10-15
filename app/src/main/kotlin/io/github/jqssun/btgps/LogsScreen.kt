package io.github.jqssun.btgps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LogsScreen(viewModel: SessionViewModel, modifier: Modifier = Modifier) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    SelectionContainer(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(logs) {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
fun LogsActions(viewModel: SessionViewModel) {
    IconButton(onClick = viewModel::exportLogs, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.share_24px), stringResource(R.string.btn_export))
    }
    IconButton(onClick = viewModel::clearLogs, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.delete_24px), stringResource(R.string.btn_clear))
    }
}
