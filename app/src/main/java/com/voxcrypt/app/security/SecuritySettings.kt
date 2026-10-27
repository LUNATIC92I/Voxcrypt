package com.voxcrypt.app.security

import android.content.Context
import com.voxcrypt.app.crypto.VxcFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non sensitive preferences (no key, no password is ever stored).
 * The PBKDF2 iteration count is configurable so it can follow hardware evolution;
 * the value used is written in every file header, so old files stay readable.
 */
class SecuritySettings(context: Context) {
    private val prefs = context.getSharedPreferences("voxcrypt_settings", Context.MODE_PRIVATE)

    data class Values(
        val iterations: Int,
        val blockScreenshots: Boolean,
        val lastBenchmarkMillis: Long,
        val lastBenchmarkIterations: Int,
    )

    private val state = MutableStateFlow(read())
    val values: StateFlow<Values> = state.asStateFlow()

    private fun read() = Values(
        iterations = prefs.getInt(KEY_ITERATIONS, VxcFormat.DEFAULT_PBKDF2_ITERATIONS)
            .coerceIn(VxcFormat.MIN_PBKDF2_ITERATIONS, VxcFormat.MAX_PBKDF2_ITERATIONS),
        blockScreenshots = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, true),
        lastBenchmarkMillis = prefs.getLong(KEY_BENCH_MS, -1),
        lastBenchmarkIterations = prefs.getInt(KEY_BENCH_IT, 0),
    )

    fun setIterations(value: Int) {
        prefs.edit().putInt(KEY_ITERATIONS, value.coerceIn(VxcFormat.MIN_PBKDF2_ITERATIONS, VxcFormat.MAX_PBKDF2_ITERATIONS)).apply()
        state.value = read()
    }

    fun setBlockScreenshots(value: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, value).apply()
        state.value = read()
    }

    fun recordBenchmark(iterations: Int, millis: Long) {
        prefs.edit().putInt(KEY_BENCH_IT, iterations).putLong(KEY_BENCH_MS, millis).apply()
        state.value = read()
    }

    companion object {
        private const val KEY_ITERATIONS = "pbkdf2_iterations"
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        private const val KEY_BENCH_MS = "bench_ms"
        private const val KEY_BENCH_IT = "bench_iterations"

        val ITERATION_CHOICES = listOf(310_000, 600_000, 1_000_000, 2_000_000)
    }
}
