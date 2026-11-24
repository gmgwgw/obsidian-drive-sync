package com.obsidiandrivesync

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.obsidiandrivesync.data.auth.AuthTokenStore
import com.obsidiandrivesync.data.auth.CryptoManager
import com.obsidiandrivesync.data.auth.GoogleOAuthManager
import com.obsidiandrivesync.data.db.AppDatabase
import com.obsidiandrivesync.data.drive.GoogleDriveClient
import com.obsidiandrivesync.data.prefs.SyncPreferencesRepository
import com.obsidiandrivesync.sync.SyncCoordinator
import com.obsidiandrivesync.worker.NotificationHelper
import com.obsidiandrivesync.worker.SyncScheduler
import com.obsidiandrivesync.worker.SyncWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "vaultsync.db"
    ).build()

    val syncDao = database.syncDao()

    val preferencesRepository = SyncPreferencesRepository(appContext)

    val tokenStore = AuthTokenStore(
        context = appContext,
        cryptoManager = CryptoManager()
    )

    val oauthManager = GoogleOAuthManager(
        context = appContext,
        tokenStore = tokenStore,
        httpClient = httpClient
    )

    val driveClient = GoogleDriveClient(
        httpClient = httpClient,
        tokenProvider = { forceRefresh -> oauthManager.getValidAccessToken(forceRefresh) }
    )

    val notificationHelper = NotificationHelper(appContext)

    val syncCoordinator = SyncCoordinator(
        context = appContext,
        preferencesRepository = preferencesRepository,
        syncDao = syncDao,
        oauthManager = oauthManager,
        driveClient = driveClient
    )

    val syncScheduler = SyncScheduler(appContext)

    val workerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker? {
            return when (workerClassName) {
                SyncWorker::class.java.name -> SyncWorker(
                    appContext = appContext,
                    params = workerParameters,
                    syncCoordinator = syncCoordinator,
                    notificationHelper = notificationHelper
                )
                else -> null
            }
        }
    }
}
