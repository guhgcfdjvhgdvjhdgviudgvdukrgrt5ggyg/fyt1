package com.ghosttype.ui

import androidx.compose.runtime.Composable

@Composable
fun GatedApp(content: @Composable () -> Unit) {
    content()
}
