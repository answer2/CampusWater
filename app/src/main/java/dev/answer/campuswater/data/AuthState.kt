package dev.answer.campuswater.data

import dev.answer.campuswater.api.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录态的界面视图。
 *
 * <p>为什么还需要这么个小对象：界面只在组合开始时读了一次 `Session.isLoggedIn()`，
 * 会话中途失效（服务端返回 errorCode 8）时不会自动回到登录页。
 * 这里给界面一个可观察的入口，任何地方判定失效都统一走 [markExpired]。
 */
object AuthState {

    private val _loggedIn = MutableStateFlow(Session.get().isLoggedIn())
    val loggedIn = _loggedIn.asStateFlow()

    fun markLoggedIn() {
        _loggedIn.value = Session.get().isLoggedIn()
    }

    /** 清空本地凭据并让界面回到登录页。 */
    fun markExpired() {
        Session.get().clear()
        _loggedIn.value = false
    }
}
