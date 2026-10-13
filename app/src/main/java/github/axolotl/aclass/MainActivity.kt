package github.axolotl.aclass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import github.axolotl.aclass.ui.TimetableApp

class MainActivity : ComponentActivity() {
    private val model: TimetableViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TimetableViewModel((application as TimetableApplication).repository) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TimetableApp(model) }
    }
    override fun onStart() { super.onStart(); model.onForeground() }
    override fun onStop() { model.onBackground(); super.onStop() }
}
