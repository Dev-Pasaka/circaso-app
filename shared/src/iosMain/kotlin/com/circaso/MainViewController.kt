package com.circaso

import androidx.compose.ui.window.ComposeUIViewController
import com.circaso.di.initKoinIos

fun MainViewController() = ComposeUIViewController(configure = { initKoinIos() }) { App() }
