/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.convx.music.ui.ios.compat.AlertDialog
import com.convx.music.ui.ios.compat.Button
import androidx.compose.material3.ButtonDefaults
import com.convx.music.ui.ios.compat.Icon
import com.convx.music.ui.ios.compat.AppTheme
import com.convx.music.ui.ios.compat.OutlinedButton
import com.convx.music.ui.ios.compat.Text
import com.convx.music.ui.ios.compat.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.convx.music.R
import com.convx.music.constants.DonationKofiUrl
import com.convx.music.constants.DonationPromptDismissedKey
import com.convx.music.constants.DonationPromptLastShownKey
import com.convx.music.constants.DonationUpiUri
import com.convx.music.constants.FirstLaunchTimestampKey
import com.convx.music.ui.utils.safeOpenUri
import com.convx.music.utils.dataStore
import com.convx.music.utils.rememberPreference
import java.util.concurrent.TimeUnit

private val MIN_DAYS_BEFORE_FIRST_PROMPT = TimeUnit.DAYS.toMillis(3)
private val MIN_DAYS_BETWEEN_PROMPTS = TimeUnit.DAYS.toMillis(30)

/**
 * Self-gating donation ask: never on a fresh install, at most monthly, gone for good
 * once dismissed. Mount once near the app root.
 */
@Composable
fun DonationPromptHost() {
    val context = LocalContext.current
    val firstLaunch by rememberPreference(FirstLaunchTimestampKey, 0L)
    var lastShown by rememberPreference(DonationPromptLastShownKey, 0L)
    var dismissedForever by rememberPreference(DonationPromptDismissedKey, false)

    LaunchedEffect(context) {
        context.dataStore.edit { preferences ->
            if ((preferences[FirstLaunchTimestampKey] ?: 0L) == 0L) {
                preferences[FirstLaunchTimestampKey] = System.currentTimeMillis()
            }
        }
    }

    val now = remember { System.currentTimeMillis() }
    val shouldShow = remember(firstLaunch, lastShown, dismissedForever, now) {
        !dismissedForever &&
            firstLaunch != 0L &&
            (now - firstLaunch) >= MIN_DAYS_BEFORE_FIRST_PROMPT &&
            (lastShown == 0L || (now - lastShown) >= MIN_DAYS_BETWEEN_PROMPTS)
    }

    var visible by remember(shouldShow) { mutableStateOf(shouldShow) }
    if (!visible) return

    val uriHandler = LocalUriHandler.current

    fun dismiss() {
        visible = false
        lastShown = System.currentTimeMillis()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        icon = { SymbolIcon(symbol = sf("heart.fill"), contentDescription = null) },
        title = { Text(stringResource(R.string.donation_prompt_title)) },
        text = { Text(stringResource(R.string.donation_prompt_message)) },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        uriHandler.safeOpenUri(context, DonationUpiUri)
                        dismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.donation_prompt_upi))
                }
                OutlinedButton(
                    onClick = {
                        uriHandler.safeOpenUri(context, DonationKofiUrl)
                        dismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.donation_prompt_kofi))
                }
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(R.string.donation_prompt_later))
                }
                TextButton(
                    onClick = {
                        dismissedForever = true
                        visible = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = AppTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.donation_prompt_dont_ask))
                }
            }
        },
    )
}
