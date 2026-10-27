package com.voxcrypt.app.ui.util

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Creates AndroidViewModels bound to the CURRENT application instance.
 * (The default AndroidViewModelFactory caches the first Application in a static
 * field, which breaks isolation when several application instances exist, as in
 * JVM UI tests.) AndroidX keeps the (Application) constructors under R8.
 */
@Composable
inline fun <reified VM : ViewModel> vxViewModel(): VM {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                modelClass.getConstructor(Application::class.java).newInstance(app)
        },
    )
}
