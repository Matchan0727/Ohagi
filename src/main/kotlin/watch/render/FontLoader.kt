package jp.simplespace.watch.render

import jp.simplespace.discord.OhagiBot
import java.awt.Font
import java.awt.GraphicsEnvironment

object FontLoader {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    private val baseRegular: Font by lazy {
        loadBundledFont("/fonts/NotoSansJP-Regular.ttf")
            ?: findSystemJapaneseFont(Font.PLAIN)
            ?: Font(Font.SANS_SERIF, Font.PLAIN, 14)
    }

    private val baseBold: Font by lazy {
        loadBundledFont("/fonts/NotoSansJP-Bold.ttf")
            ?: findSystemJapaneseFont(Font.BOLD)
            ?: baseRegular.deriveFont(Font.BOLD)
    }

    fun regular(size: Float): Font = baseRegular.deriveFont(Font.PLAIN, size)

    fun bold(size: Float): Font = baseBold.deriveFont(Font.BOLD, size)

    private fun loadBundledFont(resourcePath: String): Font? {
        return runCatching {
            FontLoader::class.java.getResourceAsStream(resourcePath)?.use { stream ->
                val font = Font.createFont(Font.TRUETYPE_FONT, stream)
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
                font
            }
        }.onFailure {
            OhagiBot.logger.warn("バンドルフォントの読み込みに失敗しました: {}", resourcePath, it)
        }.getOrNull()
    }

    private fun findSystemJapaneseFont(style: Int): Font? {
        val preferred = listOf(
            "Noto Sans CJK JP",
            "Noto Sans JP",
            "Yu Gothic UI",
            "Meiryo",
            "Hiragino Sans",
            "IPAGothic",
            "VL Gothic",
            "MS Gothic",
        )
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .toSet()
        val match = preferred.firstOrNull { it in available } ?: return null
        return Font(match, style, 14)
    }
}
