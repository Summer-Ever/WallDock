package com.walldock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.walldock.app.ui.EditorScreen
import com.walldock.app.ui.theme.WallDockTheme
import com.walldock.app.vm.EditorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WallDockTheme {
                val vm: EditorViewModel = viewModel()
                EditorScreen(vm)
            }
        }
    }
}
