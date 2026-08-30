/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import fcitx5.slei.stats.InputStatsData
import fcitx5.slei.stats.InputStatsManager
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class SleiWeeklyReportFragment : Fragment() {
    private lateinit var rangeText: TextView
    private lateinit var totalText: TextView
    private lateinit var topWordsText: TextView
    private lateinit var chart: WeeklyBarChartView
    private lateinit var selectorButtons: List<TextView>
    private var snapshot = InputStatsData()
    private var selectedWeeksAgo = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(28))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.apple_grouped_bg))
        }

        content.addView(TextView(ctx).apply {
            text = getString(R.string.slei_weekly_report_title)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
        })

        val selector = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(10).toFloat())
        }
        val labels = listOf(R.string.slei_this_week, R.string.slei_last_week, R.string.slei_two_weeks_ago)
        selectorButtons = labels.mapIndexed { index, label ->
            TextView(ctx).apply {
                text = getString(label)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(8), ctx.dp(9), ctx.dp(8), ctx.dp(9))
                setOnClickListener {
                    selectedWeeksAgo = index
                    render()
                }
            }.also {
                selector.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        content.addView(selector, marginParams(top = ctx.dp(16)))

        rangeText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.grey_700))
        }
        content.addView(rangeText, marginParams(top = ctx.dp(16)))

        chart = WeeklyBarChartView(ctx)
        content.addView(
            chart,
            marginParams(height = ctx.dp(250), top = ctx.dp(8)).apply {
                leftMargin = 0; rightMargin = 0
            }
        )

        totalText = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(12).toFloat())
        }
        content.addView(totalText, marginParams(top = ctx.dp(12)))

        content.addView(TextView(ctx).apply {
            text = getString(R.string.slei_weekly_top_words)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
        }, marginParams(top = ctx.dp(22)))

        topWordsText = TextView(ctx).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(12).toFloat())
        }
        content.addView(topWordsText, marginParams(top = ctx.dp(8)))

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            snapshot = InputStatsManager.snapshot()
            render()
        }
    }

    private fun render() {
        if (!::chart.isInitialized) return
        val days = weekDays(selectedWeeksAgo)
        val values = days.map { snapshot.dailyCharacters[it] ?: 0L }
        val weekKey = days.first()
        val top = snapshot.weeklyPhraseFrequency[weekKey].orEmpty().entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(10)

        selectorButtons.forEachIndexed { index, button ->
            val selected = index == selectedWeeksAgo
            button.setTextColor(
                if (selected) android.graphics.Color.WHITE
                else ContextCompat.getColor(requireContext(), R.color.apple_label)
            )
            button.background = if (selected) {
                rounded(ContextCompat.getColor(requireContext(), R.color.apple_blue), requireContext().dp(9).toFloat())
            } else null
        }
        rangeText.text = getString(R.string.slei_week_range, displayDate(days.first()), displayDate(days.last()))
        chart.setValues(values)
        totalText.text = getString(R.string.slei_week_total, values.sum())
        topWordsText.text = if (top.isEmpty()) {
            getString(R.string.slei_weekly_no_words)
        } else {
            top.mapIndexed { index, entry -> "${index + 1}. ${entry.key}    ${entry.value} 次" }.joinToString("\n")
        }
    }

    private fun weekDays(weeksAgo: Int): List<String> {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val start = parser.parse(InputStatsManager.weekStartKey(weeksAgo)) ?: return emptyList()
        val calendar = Calendar.getInstance().apply { time = start }
        return List(7) {
            parser.format(calendar.time).also { calendar.add(Calendar.DAY_OF_YEAR, 1) }
        }
    }

    private fun displayDate(value: String): String = runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        SimpleDateFormat("MM月dd日", Locale.SIMPLIFIED_CHINESE).format(parser.parse(value)!!)
    }.getOrDefault(value)

    private fun marginParams(
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Int = 0
    ) = LinearLayout.LayoutParams(width, height).apply { topMargin = top }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
}

private class WeeklyBarChartView(context: Context) : View(context) {
    private val values = MutableList(7) { 0L }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.apple_blue)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.apple_label)
        textAlign = Paint.Align.CENTER
        textSize = context.sp(12f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.apple_label)
        textAlign = Paint.Align.CENTER
        textSize = context.sp(11f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.apple_separator)
        strokeWidth = context.dp(1).toFloat()
    }
    private val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    init {
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(context.resolveColor(android.R.attr.colorBackgroundFloating))
        }
        setPadding(context.dp(12), context.dp(14), context.dp(12), context.dp(12))
    }

    fun setValues(newValues: List<Long>) {
        values.indices.forEach { values[it] = newValues.getOrElse(it) { 0L } }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val top = paddingTop + context.dp(22f)
        val baseline = height - paddingBottom - context.dp(24f)
        val chartHeight = max(1f, baseline - top)
        val slot = (right - left) / 7f
        val barWidth = slot * 0.52f
        val maximum = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

        canvas.drawLine(left, baseline, right, baseline, axisPaint)
        values.forEachIndexed { index, value ->
            val center = left + slot * (index + 0.5f)
            val heightRatio = value.toFloat() / maximum.toFloat()
            val barTop = baseline - chartHeight * heightRatio
            canvas.drawRoundRect(
                center - barWidth / 2f,
                barTop,
                center + barWidth / 2f,
                baseline,
                context.dp(5f),
                context.dp(5f),
                barPaint
            )
            canvas.drawText(value.toString(), center, max(top - context.dp(3f), barTop - context.dp(5f)), valuePaint)
            canvas.drawText(dayLabels[index], center, baseline + context.dp(18f), labelPaint)
        }
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
private fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
private fun Context.sp(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP,
    value,
    resources.displayMetrics
)
private fun Context.resolveColor(attribute: Int): Int {
    val typed = TypedValue()
    theme.resolveAttribute(attribute, typed, true)
    return typed.data
}
