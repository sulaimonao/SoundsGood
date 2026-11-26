package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.IOException

/** Bundled disclosures remain readable without a browser or network connection. */
@Composable
internal fun LegalDisclosureDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val disclosures = remember(context) {
        try {
            context.assets.open("legal/personal-disclosures.txt")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) {
            "Legal information could not be opened. Please contact thedream.akeems@gmail.com."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SoundsGood Personal Edition") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("personal_legal_disclosures"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Source-available · Personal Use Only",
                    style = MaterialTheme.typography.titleSmall
                )
                SelectionContainer {
                    Text(disclosures, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
