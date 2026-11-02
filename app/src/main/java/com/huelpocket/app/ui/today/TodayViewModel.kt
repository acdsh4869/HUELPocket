package com.huelpocket.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val loggedIn: Boolean = false,
)

class TodayViewModel : ViewModel() {

    private val auth: AuthRepository = AppGraph.authRepository

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(loggedIn = auth.isLoggedIn()) }
        }
    }

    /** 从登录页返回、或从后台切回来时调用 */
    fun onResume() {
        viewModelScope.launch {
            _state.update { it.copy(loggedIn = auth.isLoggedIn()) }
        }
    }
}
