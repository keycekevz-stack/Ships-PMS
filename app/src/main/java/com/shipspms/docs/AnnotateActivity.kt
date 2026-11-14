package com.shipspms.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Draw on a photo: freehand pen, arrow, box, circle and text, in several colours.
 * Saving burns the marks into the photo file.
 */
class AnnotateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var file: File
    private lateinit var canvasView: AnnotationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: run {
            Toast.makeText(this, "Can't open this photo", Toast.LENGTH_SHORT).show(); finish(); return
        }
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.navy))
            title = "Mark up photo"
            setTitleTextColor(Color.WHITE)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(Color.WHITE)
            setNavigationOnClickListener { confirmLeave() }
            menu.add(0, 1, 0, "Undo").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 2, 1, "Save").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 3, 2, "Clear all")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> canvasView.undo()
                    2 -> save()
                    3 -> canvasView.clear()
                }
                true
            }
        }
        // Make menu text white on the dark toolbar.
        toolbar.post {
            for (i in 0 until toolbar.childCount) {
                (toolbar.getChildAt(i) as? androidx.appcompat.widget.ActionMenuView)?.let { amv ->
                    for (j in 0 until amv.childCount) (amv.getChildAt(j) as? android.widget.TextView)?.setTextColor(Color.WHITE)
                }
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))

        canvasView = AnnotationView(this, bitmap) { x, y -> askText(x, y) }
        root.addView(FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(40, 40, 40))
            addView(canvasView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Tools
        val tools = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        AnnotationView.Tool.entries.forEach { t ->
            tools.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = t.label
                isAllCaps = false
                tag = t
            })
        }
        tools.addOnButtonCheckedListener { g, id, checked ->
            if (checked) canvasView.tool = g.findViewById<View>(id).tag as AnnotationView.Tool
        }
        tools.check(tools.getChildAt(1).id) // arrow by default
        root.addView(HorizontalScrollView(this).apply {
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), 0)
            addView(tools)
        })

        // Colours
        val colors = listOf(Color.RED, Color.YELLOW, Color.rgb(0, 200, 83), Color.rgb(41, 121, 255), Color.WHITE, Color.BLACK)
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        val swatches = mutableListOf<View>()
        colors.forEach { c ->
            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c); setStroke((2 * dp).toInt(), Color.GRAY)
                }
                contentDescription = "Colour"
                setOnClickListener {
                    canvasView.color = c
                    swatches.forEach { s -> s.scaleX = 1f; s.scaleY = 1f }
                    scaleX = 1.3f; scaleY = 1.3f
                }
            }
            swatches += v
            colorRow.addView(v, LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
        }
        swatches.first().apply { scaleX = 1.3f; scaleY = 1.3f }
        root.addView(colorRow)

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    private fun askText(x: Float, y: Float) {
        val input = EditText(this).apply {
            hint = "Text to place on the photo"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle("Add text")
            .setView(FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setPositiveButton(R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) canvasView.addLabel(x, y, t)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun confirmLeave() {
        if (!canvasView.hasChanges()) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save your marks?")
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (!canvasView.hasChanges()) { finish(); return }
        try {
            val out = canvasView.render()
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Shows a photo and lets the user draw shapes on it. Shapes are stored in photo (bitmap) coordinates. */
class AnnotationView(
    context: Context,
    private val bitmap: Bitmap,
    private val onTextTap: (Float, Float) -> Unit,
) : View(context) {

    enum class Tool(val label: String) { PEN("✎ Pen"), ARROW("➜ Arrow"), BOX("▭ Box"), CIRCLE("◯ Circle"), TEXT("T Text") }

    private sealed class Shape(val color: Int) {
        class Free(color: Int, val path: Path) : Shape(color)
        class Arrow(color: Int, var x1: Float, var y1: Float, var x2: Float, var y2: Float) : Shape(color)
        class Box(color: Int, val rect: RectF) : Shape(color)
        class Circle(color: Int, val rect: RectF) : Shape(color)
        class Label(color: Int, val x: Float, val y: Float, val text: String) : Shape(color)
    }

    var tool = Tool.ARROW
    var color = Color.RED
    private val shapes = mutableListOf<Shape>()
    private var current: Shape? = null
    private var startX = 0f
    private var startY = 0f

    private val toView = Matrix()
    private val toBitmap = Matrix()
    private val stroke = max(bitmap.width, bitmap.height) / 180f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }

    fun hasChanges() = shapes.isNotEmpty()
    fun undo() { if (shapes.isNotEmpty()) { shapes.removeAt(shapes.lastIndex); invalidate() } }
    fun clear() { shapes.clear(); invalidate() }

    fun addLabel(x: Float, y: Float, text: String) {
        shapes += Shape.Label(color, x, y, text)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        toView.setRectToRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            Matrix.ScaleToFit.CENTER,
        )
        toView.invert(toBitmap)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.concat(toView)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        drawShapes(canvas)
        canvas.restore()
    }

    private fun drawShapes(canvas: Canvas) {
        (shapes + listOfNotNull(current)).forEach { s ->
            paint.color = s.color
            paint.strokeWidth = stroke
            when (s) {
                is Shape.Free -> canvas.drawPath(s.path, paint)
                is Shape.Box -> canvas.drawRect(s.rect, paint)
                is Shape.Circle -> canvas.drawOval(s.rect, paint)
                is Shape.Arrow -> {
                    canvas.drawLine(s.x1, s.y1, s.x2, s.y2, paint)
                    val angle = atan2(s.y2 - s.y1, s.x2 - s.x1)
                    val head = stroke * 6
                    for (side in listOf(-1, 1)) {
                        val a = angle + Math.PI.toFloat() + side * 0.5f
                        canvas.drawLine(s.x2, s.y2, s.x2 + head * cos(a), s.y2 + head * sin(a), paint)
                    }
                }
                is Shape.Label -> {
                    textPaint.textSize = stroke * 9
                    // Outline so the text is readable on any background.
                    textPaint.style = Paint.Style.STROKE
                    textPaint.strokeWidth = stroke * 1.2f
                    textPaint.color = if (s.color == Color.BLACK) Color.WHITE else Color.BLACK
                    canvas.drawText(s.text, s.x, s.y, textPaint)
                    textPaint.style = Paint.Style.FILL
                    textPaint.color = s.color
                    canvas.drawText(s.text, s.x, s.y, textPaint)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pts = floatArrayOf(e.x, e.y)
        toBitmap.mapPoints(pts)
        val (x, y) = pts[0] to pts[1]
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x; startY = y
                current = when (tool) {
                    Tool.PEN -> Shape.Free(color, Path().apply { moveTo(x, y) })
                    Tool.ARROW -> Shape.Arrow(color, x, y, x, y)
                    Tool.BOX -> Shape.Box(color, RectF(x, y, x, y))
                    Tool.CIRCLE -> Shape.Circle(color, RectF(x, y, x, y))
                    Tool.TEXT -> null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (val c = current) {
                    is Shape.Free -> c.path.lineTo(x, y)
                    is Shape.Arrow -> { c.x2 = x; c.y2 = y }
                    is Shape.Box -> c.rect.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                    is Shape.Circle -> c.rect.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (tool == Tool.TEXT) {
                    onTextTap(x, y)
                } else {
                    current?.let { c ->
                        val tiny = when (c) {
                            is Shape.Arrow -> hypot(c.x2 - c.x1, c.y2 - c.y1) < stroke * 3
                            is Shape.Box -> c.rect.width() < stroke * 2 && c.rect.height() < stroke * 2
                            is Shape.Circle -> c.rect.width() < stroke * 2 && c.rect.height() < stroke * 2
                            else -> false
                        }
                        if (!tiny) shapes += c
                    }
                }
                current = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { current = null; invalidate() }
        }
        return true
    }

    /** The photo with all marks drawn on it, at full resolution. */
    fun render(): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        drawShapes(Canvas(out))
        return out
    }
}
