package jp.simplespace.watch.render

import jp.simplespace.discord.OhagiBot
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File

object FontLoader {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    private val baseRegular: Font by lazy {
        loadBundledFont("/fonts/NotoSansJP-Regular.ttf")
            ?: loadBundledFont("/fonts/NotoSansJP-Regular.otf")
            ?: loadExternalFont("/usr/app/fonts/NotoSansJP-Regular.ttf")
            ?: loadExternalFont("/usr/app/fonts/NotoSansJP-Regular.otf")
            ?: findSystemJapaneseFont(Font.PLAIN)
            ?: Font(Font.SANS_SERIF, Font.PLAIN, 14)
    }

    private val baseBold: Font by lazy {
        loadBundledFont("/fonts/NotoSansJP-Bold.ttf")
            ?: loadBundledFont("/fonts/NotoSansJP-Bold.otf")
            ?: loadExternalFont("/usr/app/fonts/NotoSansJP-Bold.ttf")
            ?: loadExternalFont("/usr/app/fonts/NotoSansJP-Bold.otf")
            ?: findSystemJapaneseFont(Font.BOLD)
            ?: baseRegular.deriveFont(Font.BOLD)
    }

    fun regular(size: Float): Font = baseRegular.deriveFont(Font.PLAIN, size)

    fun bold(size: Float): Font = baseBold.deriveFont(Font.BOLD, size)

    fun supportsJapanese(font: Font): Boolean =
        font.canDisplay('あ') && font.canDisplay('漢') && font.canDisplay('監')

    private fun loadBundledFont(resourcePath: String): Font? {
        return runCatching {
            FontLoader::class.java.getResourceAsStream(resourcePath)?.use { stream ->
                val font = Font.createFont(Font.TRUETYPE_FONT, stream)
                if (!supportsJapanese(font)) return null
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
                OhagiBot.logger.info("バンドル日本語フォントを読み込みました: {} ({})", resourcePath, font.fontName)
                font
            }
        }.onFailure {
            OhagiBot.logger.warn("バンドルフォントの読み込みに失敗しました: {}", resourcePath, it)
        }.getOrNull()
    }

    private fun loadExternalFont(filePath: String): Font? {
        val file = File(filePath)
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            val font = Font.createFont(Font.TRUETYPE_FONT, file)
            if (!supportsJapanese(font)) return null
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
            OhagiBot.logger.info("外部日本語フォントを読み込みました: {} ({})", filePath, font.fontName)
            font
        }.onFailure {
            OhagiBot.logger.warn("外部フォントの読み込みに失敗しました: {}", filePath, it)
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
            "IPAexGothic",
            "VL Gothic",
            "MS Gothic",
            "TakaoPGothic",
        )
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val available = ge.availableFontFamilyNames.toSet()

        for (name in preferred) {
            if (name in available) {
                val candidate = Font(name, style, 14)
                if (supportsJapanese(candidate)) {
                    OhagiBot.logger.info("システム日本語フォントを使用します: {}", candidate.fontName)
                    return candidate
                }
            }
        }

        // フォールバック: OSに登録されている全フォントから日本語表示可能なものを探索
        val fallback = ge.allFonts.firstOrNull { supportsJapanese(it) }
        if (fallback != null) {
            OhagiBot.logger.info("フォールバック日本語フォントを使用します: {}", fallback.fontName)
            return fallback.deriveFont(style, 14f)
        }

        OhagiBot.logger.warn("日本語表示可能なフォントが見つかりませんでした。文字化け・空白が発生する可能性があります。")
        return null
    }
}
