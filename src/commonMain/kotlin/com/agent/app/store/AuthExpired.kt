package com.agent.app.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Auth-expired signal — the compose port of flutter auth_gate.dart.
//
// Every agent RPC requires a bearer token; when the server answers 401/403
// (unauthenticated / permission denied) the worst behavior is a silently
// empty list. Platform stores an `authExpired` tick; the root shell shows a
// one-tap dialog to return to the setup screen.

object AuthExpired {
    /** Bumped whenever an RPC fails with unauthenticated/permission-denied. */
    var tick by mutableStateOf(0)
        private set

    /** True when at least one auth failure was observed this session. */
    var seen by mutableStateOf(false)
        private set

    /** Whether the dialog is currently visible (avoids stacking dialogs). */
    var dialogOpen by mutableStateOf(false)

    fun notifyAuth() {
        seen = true
        tick++
    }

    fun reset() {
        seen = false
        tick = 0
        dialogOpen = false
    }
}
