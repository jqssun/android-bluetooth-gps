package io.github.jqssun.btgps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun Field(
    @StringRes label: Int,
    value: String,
    @StringRes error: Int? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    @StringRes hint: Int? = null,
    @StringRes placeholder: Int? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        trailingIcon = trailing,
        supportingText = (error ?: hint)?.let { { Text(stringResource(it)) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = onDone?.let { { it() } }),
        singleLine = true,
    )
}
