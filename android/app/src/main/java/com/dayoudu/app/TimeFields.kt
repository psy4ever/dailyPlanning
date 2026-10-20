package com.dayoudu.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A compact clock selector shared by plans, meals and sleep. Null means the user has not chosen a time. */
@Composable
internal fun ClockDropdown(label: String, minuteOfDay: Int?, onChange: (Int) -> Unit) {
    var hourOpen by remember { mutableStateOf(false) }
    var minuteOpen by remember { mutableStateOf(false) }
    val hour = minuteOfDay?.div(60)
    val minute = minuteOfDay?.rem(60)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { hourOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(hour?.let { "%02d 时".format(it) } ?: "选择小时")
                }
                DropdownMenu(expanded = hourOpen, onDismissRequest = { hourOpen = false }) {
                    (0..23).forEach { option ->
                        DropdownMenuItem(text = { Text("%02d 时".format(option)) }, onClick = {
                            onChange(option * 60 + (minute ?: 0))
                            hourOpen = false
                        })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { minuteOpen = true }, enabled = hour != null,
                    modifier = Modifier.fillMaxWidth()) { Text(minute?.let { "%02d 分".format(it) } ?: "选择分钟") }
                DropdownMenu(expanded = minuteOpen, onDismissRequest = { minuteOpen = false }) {
                    (0..59).forEach { option ->
                        DropdownMenuItem(text = { Text("%02d 分".format(option)) }, onClick = {
                            onChange((hour ?: 0) * 60 + option)
                            minuteOpen = false
                        })
                    }
                }
            }
        }
    }
}
