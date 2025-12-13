package calendar

import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.GoogleUtils
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.mtls.MtlsProvider
import com.google.api.client.googleapis.mtls.MtlsUtils
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.GeneralSecurityException
import java.security.KeyStore

class CalendarController {
    companion object {
        const val APPLICATION_NAME = "Chuken"
        val JSON_FACTORY = GsonFactory.getDefaultInstance()
        const val TOKENS_DIRECTORY_PATH = "tokens"
        val SCOPES = listOf(CalendarScopes.CALENDAR_READONLY)
        const val CREDENTIALS_FILE_PATH = "/credentials.json"
        val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()

        const val VERIFY_SERVER_PORT = 31809

        fun getCalendar(): Calendar {
            val `in` = CalendarController::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH)!!
            val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(`in`))
            val flow = GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
                .build()

            val receiver = LocalServerReceiver.Builder().setPort(VERIFY_SERVER_PORT).build()
            val credential = AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
            return Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME).build()
        }

    }
}