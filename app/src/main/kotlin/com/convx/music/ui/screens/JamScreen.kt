/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.convx.music.R
import com.convx.music.jam.JamSession
import com.convx.music.ui.component.SymbolIcon
import com.convx.music.ui.component.sf
import com.convx.music.ui.ios.compat.AppTheme
import com.convx.music.ui.ios.compat.Button
import com.convx.music.ui.ios.compat.CircularProgressIndicator
import com.convx.music.ui.ios.compat.HorizontalDivider
import com.convx.music.ui.ios.compat.OutlinedButton
import com.convx.music.ui.ios.compat.OutlinedTextField
import com.convx.music.ui.ios.compat.Surface
import com.convx.music.ui.ios.compat.Switch
import com.convx.music.ui.ios.compat.Text
import com.convx.music.ui.component.ScreenPage
import com.convx.music.ui.utils.backToMain
import com.convx.music.viewmodels.JamViewModel

/**
 * Listen together over a BitChord party server: create a code, share it, and everyone who joins
 * hears the same song at the same moment.
 */
@Composable
fun JamScreen(navController: NavController, asTab: Boolean = false) {
    val viewModel: JamViewModel = hiltViewModel()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // Wake a sleeping server while the user types a name, and take a party link opened from outside.
    val pendingInvite by viewModel.invite.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.warm() }
    LaunchedEffect(pendingInvite) {
        val link = viewModel.consumeInvite() ?: return@LaunchedEffect
        link.server?.let { viewModel.serverUrl = it }
        if (viewModel.session.value == null) viewModel.join(link.code, viewModel.displayName)
    }

    ScreenPage(
        title = stringResource(R.string.jam_title),
        onBack = if (asTab) null else ({ navController.navigateUp(); Unit }),
        onBackLongClick = if (asTab) null else navController::backToMain,
        pageColor = AppTheme.colorScheme.background,
        horizontalPadding = 24.dp,
    ) {
        Text(
            text = stringResource(R.string.jam_desc),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )

        message?.let {
            Text(it, style = AppTheme.typography.bodyMedium, color = AppTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
        }

        val current = session
        if (current == null) {
            StartParty(viewModel, busy)
        } else {
            InParty(current, viewModel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StartParty(viewModel: JamViewModel, busy: Boolean) {
    var name by remember { mutableStateOf(viewModel.displayName) }
    var code by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(viewModel.serverUrl) }

    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text(stringResource(R.string.jam_name)) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.serverUrl = server; viewModel.create(name) },
        enabled = !busy && name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else {
            SymbolIcon(symbol = sf("plus"), contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.jam_create))
        }
    }

    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = code, onValueChange = { code = it.uppercase().take(12) },
        label = { Text(stringResource(R.string.jam_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { viewModel.serverUrl = server; viewModel.join(code, name) },
        enabled = !busy && code.isNotBlank() && name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.jam_join)) }

    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = server, onValueChange = { server = it },
        label = { Text(stringResource(R.string.jam_server)) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InParty(session: JamSession, viewModel: JamViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AppTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.jam_code), style = AppTheme.typography.labelMedium, color = AppTheme.colorScheme.onSurfaceVariant)
            Text(
                text = session.code,
                fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                text = stringResource(if (session.connected) R.string.jam_connected else R.string.jam_reconnecting),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(session.code)) }) {
                    SymbolIcon(symbol = sf("doc.on.doc"), contentDescription = null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.jam_copy))
                }
                Button(onClick = {
                    val text = context.getString(R.string.jam_share_text, session.code, viewModel.inviteLink(session.code))
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            null,
                        ),
                    )
                }) {
                    SymbolIcon(symbol = sf("square.and.arrow.up"), contentDescription = null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.jam_share))
                }
            }
        }
    }

    session.playback.track?.let { track ->
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.jam_now_playing), style = AppTheme.typography.labelMedium, color = AppTheme.colorScheme.onSurfaceVariant)
        Text(track.title, style = AppTheme.typography.titleSmall, maxLines = 1)
        Text(track.artist, style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.jam_members, session.members.size, session.maxMembers),
        style = AppTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AppTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            session.members.forEachIndexed { index, member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).background(AppTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(member.displayName.take(1).uppercase(), fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = member.displayName + if (member.memberId == session.you.memberId) " " + stringResource(R.string.jam_you) else "",
                            maxLines = 1,
                        )
                        if (member.isHost) {
                            Text(stringResource(R.string.jam_host), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(
                        Modifier.size(10.dp).background(
                            if (member.connected) AppTheme.colorScheme.primary else AppTheme.colorScheme.outline,
                            CircleShape,
                        ),
                    )
                }
                if (index != session.members.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    if (session.you.isHost) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.jam_host_only))
                Text(stringResource(R.string.jam_host_only_desc), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = session.hostOnlyControl, onCheckedChange = viewModel::setHostOnlyControl)
        }
    } else if (!session.canControl) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.jam_view_only), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = { viewModel.leave() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.jam_leave))
    }
}
