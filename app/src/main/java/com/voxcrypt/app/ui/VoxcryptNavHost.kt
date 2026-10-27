package com.voxcrypt.app.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voxcrypt.app.ui.screens.DecryptScreen
import com.voxcrypt.app.ui.screens.EncryptScreen
import com.voxcrypt.app.ui.screens.HomeScreen
import com.voxcrypt.app.ui.screens.LibraryScreen
import com.voxcrypt.app.ui.screens.RecordScreen
import com.voxcrypt.app.ui.screens.SecurityScreen

object Routes {
    const val HOME = "home"
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val SECURITY = "security"
    const val ENCRYPT = "encrypt?pending={pending}&uri={uri}"
    const val DECRYPT = "decrypt?vault={vault}&uri={uri}"

    fun encrypt(pending: String? = null, uri: Uri? = null) =
        "encrypt?pending=${pending?.let(Uri::encode) ?: ""}&uri=${uri?.let { Uri.encode(it.toString()) } ?: ""}"

    fun decrypt(vault: String? = null, uri: Uri? = null) =
        "decrypt?vault=${vault?.let(Uri::encode) ?: ""}&uri=${uri?.let { Uri.encode(it.toString()) } ?: ""}"
}

private fun optional(name: String) = navArgument(name) { type = NavType.StringType; defaultValue = ""; nullable = true }

@Composable
fun VoxcryptNavHost(incoming: Uri?, onIncomingConsumed: () -> Unit, nav: NavHostController = rememberNavController()) {
    LaunchedEffect(incoming) {
        if (incoming != null) {
            nav.navigate(Routes.decrypt(uri = incoming)) { launchSingleTop = true }
            onIncomingConsumed()
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn() + slideInHorizontally { it / 6 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() + slideOutHorizontally { it / 6 } },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onRecord = { nav.navigate(Routes.RECORD) },
                onEncrypt = { nav.navigate(Routes.encrypt()) },
                onDecrypt = { nav.navigate(Routes.decrypt()) },
                onLibrary = { nav.navigate(Routes.LIBRARY) },
                onSecurity = { nav.navigate(Routes.SECURITY) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onBack = { nav.popBackStack() },
                onEncrypt = { rec -> nav.navigate(Routes.encrypt(pending = rec.file.name)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.ENCRYPT, arguments = listOf(optional("pending"), optional("uri"))) { entry ->
            val pending = entry.arguments?.getString("pending")?.takeIf { it.isNotEmpty() }
            val uri = entry.arguments?.getString("uri")?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            EncryptScreen(
                pendingName = pending,
                uri = uri,
                onBack = { nav.popBackStack() },
                onLibrary = { nav.navigate(Routes.LIBRARY) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.DECRYPT, arguments = listOf(optional("vault"), optional("uri"))) { entry ->
            val vault = entry.arguments?.getString("vault")?.takeIf { it.isNotEmpty() }
            val uri = entry.arguments?.getString("uri")?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            DecryptScreen(vaultName = vault, uri = uri, onBack = { nav.popBackStack() })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { nav.popBackStack() },
                onDecrypt = { nav.navigate(Routes.decrypt(vault = it.file.name)) },
                onEncryptPending = { nav.navigate(Routes.encrypt(pending = it.file.name)) },
                onRecord = { nav.navigate(Routes.RECORD) },
            )
        }
        composable(Routes.SECURITY) { SecurityScreen(onBack = { nav.popBackStack() }) }
    }
}
