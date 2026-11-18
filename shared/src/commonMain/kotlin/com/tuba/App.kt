package com.tuba

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuba.di.AppContainer
import com.tuba.ui.map.MapScreen
import com.tuba.ui.map.MapViewModel
import com.tuba.ui.theme.TubaTheme

@Composable
fun App() {
    TubaTheme {
        val viewModel = viewModel { MapViewModel(AppContainer.instance) }
        MapScreen(viewModel)
    }
}
