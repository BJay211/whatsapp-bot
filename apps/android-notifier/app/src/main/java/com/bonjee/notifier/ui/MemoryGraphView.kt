package com.bonjee.notifier.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.bonjee.notifier.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Small force-style graph for the Memory Core screen.
 *
 * It draws what the app actually knows on this phone: the user in the centre,
 * contact rules around them, captured messages and notes on the outer ring.
 * There is no animation and no invented data - an empty graph stays empty and
 * says so through [setGraph] plus the activity's status line.
 */
class MemoryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Kind { CENTER, IMPORTANT, AUTO_REPLY, OTHER, MESSAGE, NOTE }

    data class Node(val id: String, val label: String, val kind: Kind, val detail: String = "")

    data class Edge(val from: Int, val to: Int)

    private var nodes: List<Node> = emptyList()
    private var edges: List<Edge> = emptyList()
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var radii = FloatArray(0)

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.border)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.border_cyan)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val centerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.bg_primary)
        isFakeBoldText = true
    }

    /** Called when a node is tapped; the activity decides what detail to show. */
    var onNodeTap: ((Node) -> Unit)? = null

    fun setGraph(newNodes: List<Node>, newEdges: List<Edge>) {
        nodes = newNodes
        edges = newEdges.filter { it.from in newNodes.indices && it.to in newNodes.indices }
        contentDescription = if (newNodes.isEmpty()) {
            "Memory graph is empty"
        } else {
            "Memory graph with ${newNodes.size} nodes"
        }
        layoutNodes()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutNodes()
    }

    private fun layoutNodes() {
        if (nodes.isEmpty() || width == 0 || height == 0) {
            xs = FloatArray(0)
            ys = FloatArray(0)
            radii = FloatArray(0)
            return
        }
        xs = FloatArray(nodes.size)
        ys = FloatArray(nodes.size)
        radii = FloatArray(nodes.size)

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f - 18f * density
        val inner = maxRadius * 0.52f

        val contacts = ArrayList<Int>()
        val outer = ArrayList<Int>()
        nodes.forEachIndexed { index, node ->
            when (node.kind) {
                Kind.CENTER -> Unit
                Kind.IMPORTANT, Kind.AUTO_REPLY, Kind.OTHER -> contacts.add(index)
                Kind.MESSAGE, Kind.NOTE -> outer.add(index)
            }
        }

        contacts.forEachIndexed { slot, index ->
            val angle = if (contacts.isEmpty()) {
                0.0
            } else {
                -Math.PI / 2.0 + (2.0 * Math.PI * slot / contacts.size)
            }
            xs[index] = cx + (cos(angle) * inner).toFloat()
            ys[index] = cy + (sin(angle) * inner).toFloat()
            radii[index] = 10f * density
        }

        outer.forEachIndexed { slot, index ->
            val angle = if (outer.isEmpty()) {
                0.0
            } else {
                -Math.PI / 2.0 + (2.0 * Math.PI * slot / outer.size) + 0.22
            }
            val ring = maxRadius * if (slot % 2 == 0) 0.92f else 0.98f
            xs[index] = cx + (cos(angle) * ring).toFloat()
            ys[index] = cy + (sin(angle) * ring).toFloat()
            radii[index] = if (nodes[index].kind == Kind.NOTE) 8f * density else 6.5f * density
        }

        val center = nodes.indexOfFirst { it.kind == Kind.CENTER }
        if (center >= 0) {
            xs[center] = cx
            ys[center] = cy
            radii[center] = 23f * density
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty() || xs.size != nodes.size) {
            drawEmpty(canvas)
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f - 18f * density
        canvas.drawCircle(cx, cy, maxRadius * 0.52f, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.94f, ringPaint)

        for (edge in edges) {
            canvas.drawLine(xs[edge.from], ys[edge.from], xs[edge.to], ys[edge.to], edgePaint)
        }

        val centerIndex = nodes.indexOfFirst { it.kind == Kind.CENTER }
        nodes.forEachIndexed { index, node ->
            val fill = fillColor(node.kind)
            val stroke = strokeColor(node.kind)
            nodePaint.color = fill
            strokePaint.color = stroke
            canvas.drawCircle(xs[index], ys[index], radii[index], nodePaint)
            canvas.drawCircle(xs[index], ys[index], radii[index], strokePaint)

            if (index == centerIndex) {
                canvas.drawText("YOU", xs[index], ys[index] + 3.5f * density, centerLabelPaint)
            }
            drawLabel(canvas, node, xs[index], ys[index] + radii[index] + 11f * density)
        }
    }

    private fun drawLabel(canvas: Canvas, node: Node, x: Float, y: Float) {
        if (node.kind == Kind.CENTER) return
        val shown = if (node.label.length > 13) node.label.take(12) + "…" else node.label
        val textWidth = labelPaint.measureText(shown)
        val half = textWidth / 2f
        val minX = half + 2f * density
        val maxX = this.width - half - 2f * density
        val centre = if (maxX < minX) this.width / 2f else x.coerceIn(minX, maxX)
        canvas.drawText(shown, centre, y.coerceAtMost(height - 2f * density), labelPaint)
    }

    private fun drawEmpty(canvas: Canvas) {
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "No memory nodes yet - add a note or receive a WhatsApp message.",
            width / 2f,
            height / 2f,
            labelPaint,
        )
    }

    private fun fillColor(kind: Kind): Int = when (kind) {
        Kind.CENTER -> ContextCompat.getColor(context, R.color.cyan)
        Kind.IMPORTANT -> ContextCompat.getColor(context, R.color.orange)
        Kind.AUTO_REPLY -> ContextCompat.getColor(context, R.color.green)
        Kind.OTHER -> ContextCompat.getColor(context, R.color.text_dim)
        Kind.MESSAGE -> ContextCompat.getColor(context, R.color.purple)
        Kind.NOTE -> ContextCompat.getColor(context, R.color.cyan_glow)
    }

    private fun strokeColor(kind: Kind): Int = when (kind) {
        Kind.CENTER -> ContextCompat.getColor(context, R.color.cyan_bright)
        Kind.IMPORTANT -> ContextCompat.getColor(context, R.color.orange_bright)
        Kind.AUTO_REPLY -> ContextCompat.getColor(context, R.color.green_bright)
        Kind.OTHER -> ContextCompat.getColor(context, R.color.text_tertiary)
        Kind.MESSAGE -> ContextCompat.getColor(context, R.color.purple_bright)
        Kind.NOTE -> ContextCompat.getColor(context, R.color.cyan)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (index in nodes.indices) {
            val distance = hypot(event.x - xs[index], event.y - ys[index])
            val hit = radii[index] + 12f * density
            if (distance <= hit && distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        if (best >= 0) {
            onNodeTap?.invoke(nodes[best])
            performClick()
            return true
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
