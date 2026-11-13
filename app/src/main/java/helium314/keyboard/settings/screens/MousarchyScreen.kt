// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.Mousarchy
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mousarchy: whether its server is running (else what the last start said) and the field for the code that
 * `mousarchy emparelhar` shows on the PC.
 */
@Composable
fun MousarchyScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf(Mousarchy.status) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (!busy) running = withContext(Dispatchers.IO) { Mousarchy.running() }
    }
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.mousarchy), settings = emptyList()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    !Mousarchy.available(ctx) -> stringResource(R.string.mousarchy_missing)
                    busy || running == null -> "…"
                    running == true -> stringResource(R.string.mousarchy_running)
                    else -> stringResource(R.string.mousarchy_stopped, status)
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.mousarchy_pc_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Button(enabled = code.length == 6 && !busy && Mousarchy.available(ctx), onClick = {
                    busy = true
                    Mousarchy.start(ctx, code) { _, output ->
                        status = output
                        code = ""
                        busy = false
                    }
                }) { Text(stringResource(R.string.mousarchy_pair)) }
            }
        }
    }
}
