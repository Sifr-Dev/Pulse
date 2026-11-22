package io.pulse.observability

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.pulse.observability.ui.MeasurementViewModel
import io.pulse.observability.ui.PulseApp

class MainActivity : ComponentActivity() {
    private val viewModel: MeasurementViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PulseApp(viewModel) }
    }
}
