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

/**
 * 输入数据报表：本周 / 近 30 天趋势 / 近 7 天小时热力 / 近 30 天应用排行。
 */
class SleiWeeklyReportFragment : Fragment() {
    private enum class Tab(val labelRes: Int) {
        Week(R.string.slei_this_week),
        Days30(R.string.slei_stats_tab_30d),
        Hours(R.string.slei_stats_tab_hours),
        Apps(R.string.slei_stats_tab_apps)
    }

    private lateinit var rangeText: TextView
    private lateinit var totalText: TextView
    private lateinit var topWordsTitle: TextView
    private lateinit var topWordsText: TextView
    private lateinit var chart: BarChartView
    private lateinit var heat: HourHeatView
    private lateinit var appsText: TextView
    private lateinit var tabButtons: List<TextView>
    private lateinit var weekSubSelector: LinearLayout
    private lateinit var weekSubButtons: List<TextView>
    private var snapshot = InputStatsData()
    private var selectedTab = Tab.Week
    private var selectedWeeksAgo = 0
    private val appLabels = mutableMapOf<String, String>()

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

        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(10).toFloat())
        }
        tabButtons = Tab.values().map { tab ->
            TextView(ctx).apply {
                text = getString(tab.labelRes)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(8), ctx.dp(9), ctx.dp(8), ctx.dp(9))
                setOnClickListener {
                    selectedTab = tab
                    render()
                }
            }.also { tabs.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        content.addView(tabs, marginParams(top = ctx.dp(16)))

        weekSubSelector = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(10).toFloat())
        }
        val labels = listOf(R.string.slei_this_week, R.string.slei_last_week, R.string.slei_two_weeks_ago)
        weekSubButtons = labels.mapIndexed { index, label ->
            TextView(ctx).apply {
                text = getString(label)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(6), ctx.dp(8), ctx.dp(6), ctx.dp(8))
                setOnClickListener {
                    selectedWeeksAgo = index
                    render()
                }
            }.also {
                weekSubSelector.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        content.addView(weekSubSelector, marginParams(top = ctx.dp(10)))

        rangeText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.grey_700))
        }
        content.addView(rangeText, marginParams(top = ctx.dp(16)))

        chart = BarChartView(ctx)
        content.addView(
            chart,
            marginParams(height = ctx.dp(250), top = ctx.dp(8)).apply {
                leftMargin = 0; rightMargin = 0
            }
        )

        heat = HourHeatView(ctx)
        heat.visibility = View.GONE
        content.addView(
            heat,
            marginParams(height = ctx.dp(210), top = ctx.dp(8)).apply {
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

        topWordsTitle = TextView(ctx).apply {
            text = getString(R.string.slei_weekly_top_words)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
        }
        content.addView(topWordsTitle, marginParams(top = ctx.dp(22)))

        topWordsText = TextView(ctx).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(12).toFloat())
        }
        content.addView(topWordsText, marginParams(top = ctx.dp(8)))

        appsText = TextView(ctx).apply {
            textSize = 16f
            setLineSpacing(0f, 1.4f)
            visibility = View.GONE
            setTextColor(ContextCompat.getColor(ctx, R.color.apple_label))
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            background = rounded(ctx.resolveColor(android.R.attr.colorBackgroundFloating), ctx.dp(12).toFloat())
        }
        content.addView(appsText, marginParams(top = ctx.dp(8)))

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
        when (selectedTab) {
            Tab.Week -> renderWeek()
            Tab.Days30 -> renderDays30()
            Tab.Hours -> renderHours()
            Tab.Apps -> renderApps()
        }
    }

    private fun renderWeek() {
        showChart()
        weekSubSelector.visibility = View.VISIBLE
        topWordsTitle.visibility = View.VISIBLE
        topWordsText.visibility = View.VISIBLE
        appsText.visibility = View.GONE
        val days = weekDays(selectedWeeksAgo)
        val values = days.map { snapshot.dailyCharacters[it] ?: 0L }
        val weekKey = days.first()
        val top = snapshot.weeklyPhraseFrequency[weekKey].orEmpty().entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(10)

        weekSubButtons.forEachIndexed { index, button ->
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
        chart.setValues(values, listOf("一", "二", "三", "四", "五", "六", "日"), showValues = true)
        totalText.text = getString(R.string.slei_week_total, values.sum())
        topWordsText.text = if (top.isEmpty()) {
            getString(R.string.slei_weekly_no_words)
        } else {
            top.mapIndexed { index, entry -> "${index + 1}. ${entry.key}    ${entry.value} 次" }.joinToString("\n")
        }
    }

    private fun renderDays30() {
        showChart()
        weekSubSelector.visibility = View.GONE
        topWordsTitle.visibility = View.GONE
        topWordsText.visibility = View.GONE
        appsText.visibility = View.GONE
        val days = lastDays(30)
        val values = days.map { snapshot.dailyCharacters[it] ?: 0L }
        val labels = days.mapIndexed { index, day ->
            if (index % 5 == 0) shortDate(day) else ""
        }
        rangeText.text = getString(R.string.slei_week_range, displayDate(days.first()), displayDate(days.last()))
        chart.setValues(values, labels, showValues = false)
        totalText.text = getString(R.string.slei_stats_days30_total, values.sum())
    }

    private fun renderHours() {
        chart.visibility = View.GONE
        heat.visibility = View.VISIBLE
        weekSubSelector.visibility = View.GONE
        topWordsTitle.visibility = View.GONE
        topWordsText.visibility = View.GONE
        appsText.visibility = View.GONE
        val days = lastDays(7)
        val rows = days.asReversed().map { day ->
            // 逆序后首行是今天
            val label = if (day == days.last()) "今天" else day.substring(8)
            HourHeatView.Row(label, snapshot.hourlyByDay[day].orEmpty())
        }
        rangeText.text = getString(
            R.string.slei_week_range,
            displayDate(days.first()),
            displayDate(days.last())
        ) + "（每列 1 小时，最深色为该时段峰值）"
        heat.setRows(rows)
        val total = snapshot.hourlyByDay.filterKeys { it in days.toSet() }
            .values.sumOf { it.values.sum() }
        totalText.text = getString(R.string.slei_stats_hours_total, total)
    }

    private fun renderApps() {
        chart.visibility = View.GONE
        heat.visibility = View.GONE
        appsText.visibility = View.VISIBLE
        weekSubSelector.visibility = View.GONE
        topWordsTitle.visibility = View.GONE
        topWordsText.visibility = View.GONE
        val window = lastDays(30).toSet()
        val perApp = mutableMapOf<String, Long>()
        snapshot.charactersByAppByDay.forEach { (day, apps) ->
            if (day in window) apps.forEach { (pkg, value) ->
                perApp[pkg] = (perApp[pkg] ?: 0L) + value
            }
        }
        val total = perApp.values.sum()
        val top = perApp.entries.sortedByDescending { it.value }.take(8)
        appsText.text = if (top.isEmpty()) {
            getString(R.string.slei_stats_apps_none)
        } else {
            top.joinToString("\n") { (pkg, value) ->
                val percent = if (total > 0) value * 100 / total else 0L
                "${numberOf(value)} 字 · $percent%    ${appLabel(pkg)}"
            }
        }
        rangeText.text = getString(R.string.slei_stats_apps_range)
        totalText.text = if (total > 0) {
            getString(R.string.slei_stats_days30_total, total)
        } else {
            getString(R.string.slei_stats_apps_none)
        }
    }

    /** 显示柱状图视图（应用页里隐藏，供其他标签切换复位）。 */
    private fun showChart() {
        chart.visibility = View.VISIBLE
        heat.visibility = View.GONE
    }

    private fun appLabel(pkg: String): String {
        appLabels[pkg]?.let { return it }
        val label = runCatching {
            requireContext().packageManager.getApplicationInfo(pkg, 0)
                .loadLabel(requireContext().packageManager).toString()
        }.getOrDefault(pkg)
        appLabels[pkg] = label
        return label
    }

    private fun numberOf(value: Long): String = String.format(Locale.getDefault(), "%,d", value)

    private fun lastDays(count: Int): List<String> {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(count - 1))
        }
        return (0 until count).map {
            parser.format(calendar.time).also { _ -> calendar.add(Calendar.DAY_OF_YEAR, 1) }
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

    private fun shortDate(value: String): String = runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        SimpleDateFormat("M/d", Locale.SIMPLIFIED_CHINESE).format(parser.parse(value)!!)
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

/**
 * 通用柱状图：7 天/30 天共用，条数与标签由调用方决定，密集时提供隐藏条顶数值的选项。
 */
private class BarChartView(context: Context) : View(context) {
    private var values: List<Long> = List(7) { 0L }
    private var labels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")
    private var showValues = true
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
        textSize = context.sp(10f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.apple_separator)
        strokeWidth = context.dp(1).toFloat()
    }

    init {
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(context.resolveColor(android.R.attr.colorBackgroundFloating))
        }
        setPadding(context.dp(8), context.dp(14), context.dp(8), context.dp(12))
    }

    fun setValues(newValues: List<Long>, newLabels: List<String>, showValues: Boolean) {
        values = newValues.ifEmpty { List(1) { 0L } }
        labels = newLabels.take(values.size)
        if (labels.size < values.size) labels = labels + List(values.size - labels.size) { "" }
        this.showValues = showValues
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = values.size
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val top = paddingTop + context.dp(16f)
        val baseline = height - paddingBottom - context.dp(24f)
        val chartHeight = max(1f, baseline - top)
        val slot = (right - left) / count.toFloat()
        val barWidth = (slot * 0.6f).coerceAtMost(context.dp(22f))
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
                context.dp(4f),
                context.dp(4f),
                barPaint
            )
            if (showValues) {
                canvas.drawText(value.toString(), center, max(top - context.dp(3f), barTop - context.dp(5f)), valuePaint)
            }
            labels.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { label ->
                canvas.drawText(label, center, baseline + context.dp(16f), labelPaint)
            }
        }
    }
}

/**
 * 近 7 天 × 24 小时的输入热力网格，最上行为今天。
 */
private class HourHeatView(context: Context) : View(context) {
    data class Row(val label: String, val values: Map<Int, Long>)

    private var rows: List<Row> = emptyList()
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grey_700)
        textAlign = Paint.Align.CENTER
        textSize = context.sp(10f)
    }
    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grey_700)
        textAlign = Paint.Align.CENTER
        textSize = context.sp(10f)
    }
    private val baseColor = ContextCompat.getColor(context, R.color.apple_blue)
    private val emptyColor = ContextCompat.getColor(context, R.color.apple_separator)

    init {
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(context.resolveColor(android.R.attr.colorBackgroundFloating))
        }
        setPadding(context.dp(10), context.dp(14), context.dp(10), context.dp(12))
    }

    fun setRows(newRows: List<Row>) {
        rows = newRows
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val top = paddingTop.toFloat()
        val bottom = height - paddingBottom - context.dp(14f)
        val gutterWidth = context.dp(22f)
        val gridLeft = left + gutterWidth
        val cellSize = (right - gridLeft) / 24f
        val rowHeight = minOf(cellSize, (bottom - top) / rows.size)
        val maximum = rows.maxOfOrNull { row -> row.values.values.maxOrNull() ?: 0L }?.coerceAtLeast(1L) ?: 1L

        rows.forEachIndexed { rowIndex, row ->
            val rowTop = bottom - rowHeight * (rowIndex + 1)
            canvas.drawText(
                row.label,
                left + gutterWidth / 2f,
                rowTop + rowHeight / 2f - (gutterPaint.ascent() + gutterPaint.descent()) / 2f,
                gutterPaint
            )
            for (hour in 0 until 24) {
                val value = row.values[hour] ?: 0L
                val cellLeft = gridLeft + cellSize * hour
                cellPaint.color = baseColor
                cellPaint.alpha = when {
                    value <= 0L -> {
                        cellPaint.color = emptyColor
                        60 // 空格子不喧宾夺主
                    }
                    else -> (18 + 237 * (value.toFloat() / maximum)).toInt().coerceIn(24, 255)
                }
                val inset = context.dp(1f)
                canvas.drawRoundRect(
                    cellLeft + inset,
                    rowTop + inset * 2,
                    cellLeft + cellSize - inset,
                    rowTop + rowHeight - inset * 2,
                    context.dp(2.5f),
                    context.dp(2.5f),
                    cellPaint
                )
            }
        }
        listOf(0, 6, 12, 18).forEach { hour ->
            canvas.drawText(
                hour.toString(),
                gridLeft + cellSize * hour + cellSize / 2f,
                bottom + context.dp(11f),
                hourPaint
            )
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
