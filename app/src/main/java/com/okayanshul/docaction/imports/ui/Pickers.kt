package com.okayanshul.docaction.imports.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.okayanshul.docaction.core.designsystem.DocAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The platform's own pickers, wrapped once.
 *
 * A date is always chosen on a calendar and a time on a clock, never typed — a typed date is
 * exactly the `03/04` ambiguity the engine refuses to resolve on the user's behalf, and it
 * would be perverse to reintroduce it in the correction UI.
 *
 * Both dialogs report a cancel as `null` rather than as the unchanged value, so callers can
 * tell "kept it the same" apart from "backed out".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickDialog(initial: LocalDate, onResult: (LocalDate?) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = { onResult(null) },
        confirmButton = {
            TextButton(onClick = {
                // The picker works in UTC midnights. Reading the selection back in UTC is
                // what stops it drifting a day either side depending on the device's zone.
                onResult(
                    state.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    },
                )
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickDialog(title: String, initial: LocalTime, onResult: (LocalTime?) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        // Follows the device's 12/24-hour setting rather than the locale's default, because
        // that is the one the user actually changed.
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title, style = DocAction.type.subject) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onResult(LocalTime.of(state.hour, state.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}
