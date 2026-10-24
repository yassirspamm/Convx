/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convx.music.jam.JamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class JamViewModel @Inject constructor(private val manager: JamManager) : ViewModel() {
    val session = manager.session
    val busy = manager.busy
    val message = manager.message

    val invite = manager.invite

    fun consumeInvite() = manager.consumeInvite()

    fun warm() = viewModelScope.launch { manager.warm() }

    fun inviteLink(code: String) = manager.inviteLink(code)

    var serverUrl: String
        get() = manager.serverUrl
        set(value) { manager.serverUrl = value }

    var displayName: String
        get() = manager.displayName
        set(value) { manager.displayName = value }

    fun create(name: String) = viewModelScope.launch { manager.create(name) }

    fun join(code: String, name: String) = viewModelScope.launch { manager.join(code, name) }

    fun leave() = manager.leave()

    fun setHostOnlyControl(enabled: Boolean) = manager.setHostOnlyControl(enabled)
}
