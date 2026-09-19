package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

// Shared dialogs — port of flutter widgets/dialogs.dart.

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    confirmLabel: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.appCard()
                .padding(AppSpacing.LG.dp),
        ) {
            Text(title, style = AppText.title)
            Spacer(Modifier.height(AppSpacing.MD.dp))
            AppTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpacing.MD.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(t_cancel(), Modifier.appClickable(onTap = onDismiss).padding(8.dp), style = AppText.small)
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Box(
                    Modifier.appClickable(shape = AppRadius.sm, hoverWash = false) { onConfirm(value.trim()) }
                        .background(colors.primary, AppRadius.sm)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(confirmLabel ?: "OK", color = colors.onPrimary, style = AppText.small)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String = "",
    confirmLabel: String = "OK",
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.appCard()
                .padding(AppSpacing.LG.dp),
        ) {
            Text(title, style = AppText.title)
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.SM.dp))
                Text(body, style = AppText.small, color = colors.mutedForeground)
            }
            Spacer(Modifier.height(AppSpacing.MD.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(t_cancel(), Modifier.appClickable(onTap = onDismiss).padding(8.dp), style = AppText.small)
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Box(
                    Modifier.appClickable(shape = AppRadius.sm, hoverWash = false, onTap = onConfirm)
                        .background(
                            if (destructive) colors.destructive else colors.primary,
                            AppRadius.sm,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(confirmLabel, color = androidx.compose.ui.graphics.Color.White, style = AppText.small)
                }
            }
        }
    }
}

@Composable
fun ActionSheet(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.appCard()
                .padding(vertical = AppSpacing.SM.dp),
        ) {
            Text(
                title, style = AppText.small, color = colors.mutedForeground,
                modifier = Modifier.padding(horizontal = AppSpacing.LG.dp, vertical = AppSpacing.SM.dp),
            )
            for ((label, action) in actions) {
                Box(
                    Modifier.fillMaxWidth().appClickable(enabled = true, shape = AppRadius.sm) {
                        onDismiss()
                        action()
                    }.padding(horizontal = AppSpacing.LG.dp, vertical = 12.dp),
                ) {
                    Text(label, style = AppText.body)
                }
            }
        }
    }
}

@Composable
fun Toast(text: String, error: Boolean = false) {
    val colors = LocalAppColors.current
    Box(Modifier.padding(4.dp)) {
        Text(
            text,
            color = if (error) androidx.compose.ui.graphics.Color.White else colors.foreground,
            style = AppText.meta,
            modifier = Modifier
                .background(
                    if (error) colors.destructive else colors.popover,
                    AppRadius.md,
                )
                .border(1.dp, colors.border.copy(alpha = 0.6f), AppRadius.md)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun t_cancel(): String = com.agent.app.i18n.I18n.t("cancel")
