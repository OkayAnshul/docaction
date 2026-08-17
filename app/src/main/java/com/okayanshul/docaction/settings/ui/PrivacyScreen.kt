package com.okayanshul.docaction.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget

/**
 * What happens to a document, in plain sentences.
 *
 * No badges, no shield icons, no percentages. The claims here are structural facts about the
 * app — it has no network permission, so a document cannot be uploaded even by a mistake in
 * our own code — and stating them plainly is more convincing than decorating them. A lock
 * icon is what an app shows when it wants credit for privacy; a sentence is what it shows
 * when the privacy is real.
 *
 * Each paragraph is something a sceptical person would actually ask, in the order they would
 * ask it. Nothing here overclaims: the last two say what we *do* keep and why.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DocAction.space.default),
        verticalArrangement = Arrangement.spacedBy(DocAction.space.default),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
            Text("Back", style = DocAction.type.label)
        }

        Text(
            text = "Your documents stay on this phone",
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        Point(
            "There is no internet permission.",
            "Android will not let this app open a network connection, because it never asked " +
                "for one. That is not a promise we are making — it is a permission we do not " +
                "have, and you can check it in your phone's app settings.",
        )
        Point(
            "Reading happens here.",
            "Every page, spreadsheet cell and photo is read on this device. Nothing is " +
                "uploaded, and there is no account to sign in to.",
        )
        Point(
            "Calendar access is used for two things.",
            "Adding the events you confirm, and finding them again if you undo. We never read " +
                "your calendar for anything else.",
        )
        Point(
            "We keep filenames and counts, not content.",
            "So the history list can show you what you imported and take it back out. The text " +
                "inside your documents is not stored.",
        )
        Point(
            "Reminders are the one exception.",
            "A notification has to be able to say what it is about, so a reminder keeps the " +
                "title of the event it belongs to. That is excluded from device backups.",
        )
        Point(
            "\"Where did this come from?\" needs the document.",
            "While an import is open we keep a copy so we can show you the page a value came " +
                "from. It is deleted when the import finishes or you walk away.",
        )

        Spacer(Modifier.padding(bottom = DocAction.space.section))
    }
}

@Composable
private fun Point(headline: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(DocAction.space.tight)) {
        Text(
            text = headline,
            style = DocAction.type.subject,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
