package com.shipspms.docs

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

/** Plays a video stored in the app (offline), with the standard play/pause/seek controls. */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val STATE_POS = "pos"
    }

    private lateinit var video: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        val file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = file.nameWithoutExtension
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, 1, 0, R.string.share).apply {
            setIcon(R.drawable.ic_share); icon?.setTint(Color.WHITE)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 2, 1, R.string.open_with)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == 1) shareFile(this, file) else openWithOtherApp(this, file)
            true
        }

        // Replace the page list of the shared viewer layout with a video view.
        val pages = findViewById<View>(R.id.pages)
        val container = pages.parent as FrameLayout
        container.setBackgroundColor(Color.BLACK)
        pages.visibility = View.GONE
        val progress = findViewById<ProgressBar>(R.id.progress)

        video = VideoView(this)
        container.addView(video, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setVideoPath(file.absolutePath)
        val start = savedInstanceState?.getInt(STATE_POS) ?: 0
        video.setOnPreparedListener {
            progress.visibility = View.GONE
            if (start > 0) video.seekTo(start)
            video.start()
            controller.show(3000)
        }
        video.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            Toast.makeText(this, "This video format can't be played here. Try “Open with other app”.", Toast.LENGTH_LONG).show()
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::video.isInitialized) outState.putInt(STATE_POS, video.currentPosition)
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized) video.pause()
    }
}
