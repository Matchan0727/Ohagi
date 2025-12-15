package jp.simplespace

import jp.simplespace.db.Drive
import jp.simplespace.db.DriveService.Drives
import jp.simplespace.discord.OhagiBot
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.Random
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

class DriveReserveNotifier {

    val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    val SCHOOL_CD = "YI-TgY2xe5k-brGQYS-1OA=="
    val TOP_URL = "https://www.e-license.jp/el31/$SCHOOL_CD"
    val LOGIN_URL = "https://www.e-license.jp/el31/pc/login"
    val LOGOUT_URL = "https://www.e-license.jp/el31/logout/$SCHOOL_CD"
    val MAIN_URL = "https://www.e-license.jp/el31/pc/reserv/p03/p03a"
    val NEXT_WEEK_URL = MAIN_URL + "/nextWeek"
    val LAST_WEEK_URL = MAIN_URL + "/lastWeek"
    var currentPage = 1
        private set
    var cookies: Map<String, String> = mutableMapOf()
        private set
    var carModelCd: String = "301"
    val SELECTOR = ".yoyakuTable > tbody > tr > td.status1 > a"
    private var currentSchoolCd: String = SCHOOL_CD

    val DISCORD_USER_ID = "390393475784245248"

    private val rand = Random()
    private var timer = Timer()
    private val task = object: TimerTask() {
        override fun run() {
            OhagiBot.logger.info("教習所予約チェックが定期実行されました。")
            val document = login().parse()
            val elements = document.select(SELECTOR)
            TimeUnit.SECONDS.sleep((rand.nextInt(3)+1).toLong())
            elements.addAll(movePage(2).parse().select(SELECTOR))
            val drives = mutableListOf<Drive>()
            for (e in elements) {
                val r = Drive(
                    zigen = e.attr("data-zigen"),
                    date = e.attr("data-date"),
                    time = e.attr("data-time"),
                    yoyaku = e.attr("data-yoyaku"),
                    week = e.attr("data-week"),
                )
                val isEmpty = transaction {
                    Drives.selectAll().where{
                        (Drives.yoyaku eq r.yoyaku) and
                                (Drives.zigen eq r.zigen)
                    }
                        .empty()
                }
                if (!isEmpty) continue
                drives.add(r)
            }
            // DBにまとめて登録
            transaction {
                Drives.batchInsert(drives) {
                    this[Drives.zigen] = it.zigen
                    this[Drives.date] = it.date
                    this[Drives.time] = it.time
                    this[Drives.yoyaku] = it.yoyaku
                    this[Drives.week] = it.week
                }
            }
            logout()
            if (drives.isEmpty()) return
            // 通知処理
            // Discord
            val jda = OhagiBot.jda
            jda.openPrivateChannelById(DISCORD_USER_ID).queue { channel ->
                val sb = StringBuilder()
                sb.append("# 以下の予約枠が空きました！\n")
                for (d in drives) {
                    sb.append("- ${d.date}${d.week}の${d.zigen}時限目(${d.time})\n")
                }
                channel.sendMessage(sb.toString()).queue()
                OhagiBot.logger.info("予約の空いた枠が通知されました。\n$sb")
            }
        }
    }

    fun start() {
        reset()
        //1分から2分の間隔で実行
        timer.schedule(task,
            rand.nextInt(1000).toLong(),
            TimeUnit.SECONDS.toMillis((rand.nextInt(60)+60).toLong())
        )
    }

    fun reset() {
        timer.cancel()
        timer = Timer()
    }

    fun login(): Connection.Response {
        var response = Jsoup.connect(TOP_URL)
            .userAgent(UA)
            .method(Connection.Method.GET)
            .execute()
        response = Jsoup.connect(LOGIN_URL)
            .data("schoolCd", SCHOOL_CD,
                "studentId", "2050267",
                "password", "20050727"
            )
            .userAgent(UA)
            .cookies(response.cookies())
            .method(Connection.Method.POST)
            .followRedirects(true)
            .execute()
        cookies = response.cookies()
        currentPage = 1
        currentSchoolCd = Jsoup.parse(response.body()).select("input[name=schoolCd]").`val`()
        return response
    }

    fun logout(): Connection.Response {
        val response = Jsoup.connect(LOGOUT_URL)
            .userAgent(UA)
            .cookies(cookies)
            .method(Connection.Method.GET)
            .followRedirects(true)
            .execute()
        cookies = response.cookies()
        return response
    }

    fun movePage(page: Int) : Connection.Response {
        if (currentPage == page) throw IllegalArgumentException("ページ番号が現在と同じです: $page")
        val url = when (page) {
            1 -> LAST_WEEK_URL
            2 -> NEXT_WEEK_URL
            else -> throw IllegalArgumentException("Invalid page number: $page")
        }

        var response = Jsoup.connect(url)
            .userAgent(UA)
            .cookies(cookies)
            .method(Connection.Method.POST)
            .followRedirects(true)
            .data("schoolCd", currentSchoolCd,
                "lastScreenCd", "",
                "dateInformationType", "",
                "groupCd", "1",
                "instructorTypeCd", "1",
                "page", currentPage.toString(),
                "changeInstructorFlg", "1",
                "carModelCd", carModelCd,
                "instructorCd", "0",
                "infoPeriodNumber", "",
                "nominationInstructorCd", "0",
                "kamokuCd", "0",
                "selectTime", ""
            )
            .execute()
        this.currentPage = page
        currentSchoolCd = Jsoup.parse(response.body()).select("input[name=schoolCd]").`val`()
        return response
    }
}