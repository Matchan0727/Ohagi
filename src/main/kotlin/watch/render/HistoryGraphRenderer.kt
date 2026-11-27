package jp.simplespace.watch.render

import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchScoreEventType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

enum class GraphRange(val key: String, val label: String, val durationMillis: Long?) {
    DAY_1("1d", "1日", 24L * 3600_000L),
    WEEK_1("7d", "1週間", 7L * 24L * 3600_000L),
    MONTH_1("30d", "1ヶ月", 30L * 24L * 3600_000L),
    ALL("all", "累計", null);

    companion object {
        fun fromKey(key: String?): GraphRange =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: WEEK_1
    }
}

object HistoryGraphRenderer {
    private const val WIDTH = 920
    private const val HEIGHT = 460

    private val ZONE_JST = ZoneId.of("Asia/Tokyo")
    private val BG_COLOR = Color(0x1E, 0x20, 0x25)
    private val PLOT_BG = Color(0x26, 0x29, 0x30)
    private val GRID_COLOR = Color(0x3D, 0x42, 0x4D)
    private val TEXT_PRIMARY = Color(0xF2, 0xF3, 0xF5)
    private val TEXT_MUTED = Color(0xA6, 0xAC, 0xB8)
    private val LINE_COLOR = Color(0xFF, 0x6B, 0x6B)
    private val VIOLATION_DOT = Color(0xE7, 0x4C, 0x3C)
    private val DECAY_DOT = Color(0x2E, 0xCC, 0x71)
    private val MANUAL_DOT = Color(0x36, 0xB8, 0xFA)
    private val BAN_LINE_COLOR = Color(0xFF, 0x3B, 0x30, 210)

    fun render(
        displayName: String,
        range: GraphRange,
        events: List<WatchScoreEvent>,
        initialScoreBeforeRange: Double,
        nowMillis: Long = System.currentTimeMillis(),
    ): ByteArray {
        val img = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            g.color = BG_COLOR
            g.fillRect(0, 0, WIDTH, HEIGHT)

            val padLeft = 68
            val padRight = 32
            val padTop = 64
            val padBottom = 64
            val plotW = WIDTH - padLeft - padRight
            val plotH = HEIGHT - padTop - padBottom

            // 凡例（右端基準で動的配置して幅を取得）
            g.font = FontLoader.regular(13f)
            val legendStartX = drawLegendRightAligned(g, WIDTH - padRight, 24)

            // タイトル（凡例と被らない幅でトリミング）
            g.font = FontLoader.bold(20f)
            g.color = TEXT_PRIMARY
            val fullTitle = "違反度推移グラフ - $displayName (${range.label})"
            val maxTitleW = (legendStartX - padLeft - 16).coerceAtLeast(180)
            g.drawString(ellipsizeToWidth(fullTitle, g.fontMetrics, maxTitleW), padLeft, 36)

            // プロット背景
            g.color = PLOT_BG
            g.fillRect(padLeft, padTop, plotW, plotH)

            // Y軸グリッド (0, 25, 50, 75, 100%)
            g.font = FontLoader.regular(12f)
            for (yVal in listOf(0, 25, 50, 75, 100)) {
                val py = padTop + plotH - (plotH * (yVal / 100.0)).roundToInt()
                if (yVal == 100) {
                    g.color = BAN_LINE_COLOR
                    g.stroke = BasicStroke(
                        1.5f,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER,
                        10f,
                        floatArrayOf(6f, 4f),
                        0f,
                    )
                } else {
                    g.color = GRID_COLOR
                    g.stroke = BasicStroke(1f)
                }
                g.drawLine(padLeft, py, padLeft + plotW, py)

                g.color = if (yVal == 100) Color(0xFF, 0x6B, 0x6B) else TEXT_MUTED
                val label = "$yVal%"
                val lw = g.fontMetrics.stringWidth(label)
                g.drawString(label, padLeft - 10 - lw, py + 4)
            }

            // 時間範囲の決定
            val startMillis = when {
                range.durationMillis != null -> nowMillis - range.durationMillis
                events.isNotEmpty() -> (events.first().createdAt - 30L * 60_000L).coerceAtMost(nowMillis - 30L * 60_000L)
                else -> nowMillis - 7L * 24L * 3600_000L
            }
            val endMillis = nowMillis.coerceAtLeast(startMillis + 60_000L)
            val spanMillis = (endMillis - startMillis).toDouble()

            // X軸グリッドとラベル（期間長に応じて自動フォーマット）
            val tickCount = 5
            val formatter = chooseAxisFormatter(range, spanMillis.toLong())
            g.stroke = BasicStroke(1f)
            for (i in 0..tickCount) {
                val ratio = i.toDouble() / tickCount
                val ts = startMillis + (spanMillis * ratio).toLong()
                val px = padLeft + (plotW * ratio).roundToInt()
                g.color = GRID_COLOR
                g.drawLine(px, padTop, px, padTop + plotH)

                val text = Instant.ofEpochMilli(ts).atZone(ZONE_JST).format(formatter)
                g.color = TEXT_MUTED
                val tw = g.fontMetrics.stringWidth(text)
                g.drawString(text, (px - tw / 2).coerceIn(padLeft, WIDTH - padRight - tw), padTop + plotH + 22)
            }

            // 時系列データ点構築
            val points = mutableListOf<Pair<Long, Double>>()
            points += startMillis to initialScoreBeforeRange.coerceIn(0.0, 100.0)
            events.forEach { ev ->
                val t = ev.createdAt.coerceIn(startMillis, endMillis)
                points += t to ev.scoreAfter.coerceIn(0.0, 100.0)
            }
            val lastScore = points.last().second
            points += endMillis to lastScore

            fun toX(ts: Long): Double = padLeft + plotW * ((ts - startMillis) / spanMillis)
            fun toY(score: Double): Double = padTop + plotH - plotH * (score.coerceIn(0.0, 100.0) / 100.0)

            // エリア塗りつぶし
            val area = Path2D.Double()
            area.moveTo(toX(points.first().first), (padTop + plotH).toDouble())
            points.forEach { (ts, sc) -> area.lineTo(toX(ts), toY(sc)) }
            area.lineTo(toX(points.last().first), (padTop + plotH).toDouble())
            area.closePath()
            g.paint = GradientPaint(
                0f,
                padTop.toFloat(),
                Color(0xE7, 0x4C, 0x3C, 95),
                0f,
                (padTop + plotH).toFloat(),
                Color(0x2E, 0xCC, 0x71, 20),
            )
            g.fill(area)

            // 折れ線
            val line = Path2D.Double()
            line.moveTo(toX(points.first().first), toY(points.first().second))
            points.drop(1).forEach { (ts, sc) -> line.lineTo(toX(ts), toY(sc)) }
            g.color = LINE_COLOR
            g.stroke = BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(line)

            // イベントマーカー（BAN縦線・増減ドット）
            events.forEach { ev ->
                val x = toX(ev.createdAt.coerceIn(startMillis, endMillis)).roundToInt()
                val y = toY(ev.scoreAfter).roundToInt()
                if (ev.eventType == WatchScoreEventType.BANNED) {
                    g.color = BAN_LINE_COLOR
                    g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f)
                    g.drawLine(x, padTop, x, padTop + plotH)
                    g.font = FontLoader.bold(11f)
                    g.drawString("BAN", (x + 4).coerceAtMost(padLeft + plotW - 28), padTop + 16)
                } else {
                    val dotColor = when (ev.eventType) {
                        WatchScoreEventType.AI_VIOLATION -> VIOLATION_DOT
                        WatchScoreEventType.DECAY -> DECAY_DOT
                        WatchScoreEventType.MANUAL_ADJUST -> MANUAL_DOT
                        else -> TEXT_MUTED
                    }
                    g.color = dotColor
                    g.fillOval(x - 4, y - 4, 8, 8)
                }
            }

            if (events.isEmpty()) {
                g.font = FontLoader.regular(15f)
                g.color = TEXT_MUTED
                val emptyMsg = "この期間のスコア変動履歴はありません"
                val mw = g.fontMetrics.stringWidth(emptyMsg)
                g.drawString(emptyMsg, padLeft + (plotW - mw) / 2, padTop + plotH / 2)
            }

            // 外枠
            g.color = GRID_COLOR
            g.stroke = BasicStroke(1.5f)
            g.drawRect(padLeft, padTop, plotW, plotH)
        } finally {
            g.dispose()
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }

    private fun chooseAxisFormatter(range: GraphRange, spanMillis: Long): DateTimeFormatter {
        val hours = spanMillis / 3600_000L
        return when {
            range == GraphRange.DAY_1 || hours <= 24L -> DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
            hours <= 72L -> DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.JAPAN)
            hours <= 60L * 24L -> DateTimeFormatter.ofPattern("MM/dd", Locale.JAPAN)
            else -> DateTimeFormatter.ofPattern("yy/MM/dd", Locale.JAPAN)
        }
    }

    private fun drawLegendRightAligned(g: java.awt.Graphics2D, rightX: Int, y: Int): Int {
        val items = listOf(
            VIOLATION_DOT to "違反加算",
            DECAY_DOT to "減衰回復",
            MANUAL_DOT to "手動調整",
        )
        val fm = g.fontMetrics
        val gap = 18
        val dotSize = 10
        val dotLabelGap = 6
        val totalWidth = items.sumOf { dotSize + dotLabelGap + fm.stringWidth(it.second) } + gap * (items.size - 1)
        val startX = rightX - totalWidth
        var x = startX
        items.forEach { (color, label) ->
            g.color = color
            g.fillOval(x, y, dotSize, dotSize)
            g.color = TEXT_MUTED
            g.drawString(label, x + dotSize + dotLabelGap, y + 10)
            x += dotSize + dotLabelGap + fm.stringWidth(label) + gap
        }
        return startX
    }

    private fun ellipsizeToWidth(text: String, fm: FontMetrics, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = fm.stringWidth(ellipsis)
        val sb = StringBuilder()
        for (ch in text) {
            if (fm.stringWidth(sb.toString() + ch) + ellipsisW > maxWidth) break
            sb.append(ch)
        }
        return sb.toString() + ellipsis
    }
}
