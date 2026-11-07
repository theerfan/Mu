package com.theerfan.mu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theerfan.mu.song.SongRepository
import com.theerfan.mu.ui.CalibrationScreen
import com.theerfan.mu.ui.HomeScreen
import com.theerfan.mu.ui.MuTheme
import com.theerfan.mu.ui.PlayScreen
import com.theerfan.mu.ui.TunerScreen

class MainActivity : ComponentActivity() {
    private lateinit var repo: SongRepository
    /** Bumped when the song list changes (e.g. a shared file was imported). */
    private val libraryVersion = mutableIntStateOf(0)

    private val micGranted = mutableStateOf(false)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted.value = granted
        if (!granted) Toast.makeText(this, "Mu needs the microphone to hear your guitar", Toast.LENGTH_LONG).show()
    }

    fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun requestMic() = micPermission.launch(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.Transparent.toArgb()), SystemBarStyle.dark(Color.Transparent.toArgb()))
        super.onCreate(savedInstanceState)
        repo = SongRepository(applicationContext)
        micGranted.value = hasMic()
        if (!micGranted.value) requestMic()
        handleIntent(intent)
        setContent {
            MuTheme {
                val nav = rememberNavController()
                NavHost(
                    nav, startDestination = "home",
                    enterTransition = { slideIntoContainer(SlideDirection.Start, tween(280)) + fadeIn(tween(280)) },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(200)) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(260)) + fadeOut(tween(260)) },
                ) {
                    composable("home") {
                        HomeScreen(
                            repo = repo, version = libraryVersion.intValue,
                            onChanged = { libraryVersion.intValue++ },
                            onPlay = { id -> nav.navigate("play/" + Uri.encode(id)) },
                            onTuner = { nav.navigate("tuner") }, onCalibrate = { nav.navigate("calibrate") },
                        )
                    }
                    composable("tuner") { TunerScreen(hasMic = micGranted.value, requestMic = ::requestMic, onBack = { nav.popBackStack() }) }
                    composable("calibrate") { CalibrationScreen(hasMic = micGranted.value, requestMic = ::requestMic, onBack = { nav.popBackStack() }) }
                    composable("play/{id}") { entry ->
                        val id = Uri.decode(entry.arguments?.getString("id") ?: "")
                        PlayScreen(repo = repo, songId = id, hasMic = micGranted.value, requestMic = ::requestMic,
                            onBack = { nav.popBackStack() }, onCalibrate = { nav.navigate("calibrate") })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        micGranted.value = hasMic()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Import a song JSON shared to the app (ACTION_SEND) or opened with it (ACTION_VIEW). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val result = runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    when {
                        uri != null -> repo.importUri(uri)
                        intent.getStringExtra(Intent.EXTRA_TEXT) != null -> repo.importText(intent.getStringExtra(Intent.EXTRA_TEXT)!!)
                        else -> return
                    }
                }
                Intent.ACTION_VIEW -> repo.importUri(intent.data ?: return)
                else -> return
            }
        }
        result.onSuccess { Toast.makeText(this, "Imported “${it.song.title}”", Toast.LENGTH_SHORT).show(); libraryVersion.intValue++ }
            .onFailure { Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        setIntent(Intent(Intent.ACTION_MAIN))
    }
}
