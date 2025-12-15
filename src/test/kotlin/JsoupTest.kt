package jp.simplespace

import org.jsoup.Connection
import org.jsoup.Jsoup
import org.junit.Test

class JsoupTest {

    val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    val SCHOOL_CD = "YI-TgY2xe5k-brGQYS-1OA=="
    val TOP_URL = "https://www.e-license.jp/el31/$SCHOOL_CD"
    val LOGIN_URL = "https://www.e-license.jp/el31/pc/login"
    val MAIN_URL = "https://www.e-license.jp/el31/pc/reserv/p03/p03a"
    val NEXT_WEEK_URL = MAIN_URL + "/nextWeek"
    val LAST_WEEK_URL = MAIN_URL + "/lastWeek"
    var currentPage = 1
        private set
    var cookies: Map<String, String> = mutableMapOf()
        private set
    var carModelCd: String = "301"

//    @Test
    fun testJsoup() {

        login()
        movePage(2)
        val document = movePage(1).parse()
        val elements = document.select(".yoyakuTable > tbody > tr > td.status1 > a")

        val reservs = mutableListOf<Reserv>()
        for (e in elements) {
            val r = Reserv(
                zigen = e.attr("data-zigen"),
                date = e.attr("data-date"),
                time = e.attr("data-time"),
                yoyaku = e.attr("data-yoyaku"),
                week = e.attr("data-week"),
            )
            reservs.add(r)
        }
        println(reservs)
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
            .data("schoolCd", SCHOOL_CD,
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
        return response
    }

    data class Reserv(
        val zigen: String,
        val date: String,
        val time: String,
        val yoyaku: String,
        val week: String,
    )
}