/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync

import okhttp3.ResponseBody
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.initsync.InitSyncStep
import org.matrix.android.sdk.api.session.initsync.SyncStatusService
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.TimeOutInterceptor
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.toFailure
import org.matrix.android.sdk.internal.session.filter.FilterRepository
import org.matrix.android.sdk.internal.session.homeserver.GetHomeServerCapabilitiesTask
import org.matrix.android.sdk.internal.session.initsync.DefaultSyncStatusService
import org.matrix.android.sdk.internal.session.initsync.reportSubtask
import org.matrix.android.sdk.internal.session.sync.model.LazyRoomSyncEphemeral
import org.matrix.android.sdk.internal.session.sync.parsing.InitialSyncResponseParser
import org.matrix.android.sdk.internal.session.user.UserStore
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.logDuration
import retrofit2.Response
import retrofit2.awaitResponse
import timber.log.Timber
import java.io.File
import java.net.SocketTimeoutException
import javax.inject.Inject

private val loggerTag = LoggerTag("SyncTask", LoggerTag.SYNC)

internal interface SyncTask : Task<SyncTask.Params, Unit> {

    data class Params(
            val timeout: Long,
            val presence: SyncPresence?
    )
}

internal class DefaultSyncTask @Inject constructor(
        private val syncAPI: SyncAPI,
        @UserId private val userId: String,
        private val filterRepository: FilterRepository,
        private val syncResponseHandler: SyncResponseHandler,
        private val defaultSyncStatusService: DefaultSyncStatusService,
        private val syncTokenStore: SyncTokenStore,
        private val getHomeServerCapabilitiesTask: GetHomeServerCapabilitiesTask,
        private val userStore: UserStore,
        private val syncTaskSequencer: SyncTaskSequencer,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionFilesDirectory
        private val fileDirectory: File,
        private val syncResponseParser: InitialSyncResponseParser,
        private val roomSyncEphemeralTemporaryStore: RoomSyncEphemeralTemporaryStore
) : SyncTask {

    private val workingDir = File(fileDirectory, "is")
    private val initialSyncStatusRepository: InitialSyncStatusRepository = FileInitialSyncStatusRepository(workingDir)

    override suspend fun execute(params: SyncTask.Params) {
        syncTaskSequencer.post {
            doSync(params)
        }
    }

    private suspend fun doSync(params: SyncTask.Params) {
        Timber.tag(loggerTag.value).d("Sync task started on Thread: ${Thread.currentThread().name}")

        val requestParams = HashMap<String, String>()
        var timeout = 0L
        val token = syncTokenStore.getLastToken()
        if (token != null) {
            requestParams["since"] = token
            timeout = params.timeout
        }
        requestParams["timeout"] = timeout.toString()
        requestParams["filter"] = filterRepository.getFilter()
        params.presence?.let { requestParams["set_presence"] = it.value }

        val isInitialSync = token == null
        if (isInitialSync) {
            // We might want to get the user information in parallel too
            userStore.createOrUpdate(userId)
            defaultSyncStatusService.startRoot(InitSyncStep.ImportingAccount, 100)
        }
        // Maybe refresh the homeserver capabilities data we know
        getHomeServerCapabilitiesTask.execute(GetHomeServerCapabilitiesTask.Params(forceRefresh = false))

        val readTimeOut = (params.timeout + TIMEOUT_MARGIN).coerceAtLeast(TimeOutInterceptor.DEFAULT_LONG_TIMEOUT)

        if (isInitialSync) {
            Timber.tag(loggerTag.value).d("INIT_SYNC with filter: ${requestParams["filter"]}")
            val initSyncStrategy = initialSyncStrategy
            logDuration("INIT_SYNC strategy: $initSyncStrategy", loggerTag) {
                if (initSyncStrategy is InitialSyncStrategy.Optimized) {
                    roomSyncEphemeralTemporaryStore.reset()
                    workingDir.mkdirs()
                    val file = downloadInitSyncResponse(requestParams)
                    reportSubtask(defaultSyncStatusService, InitSyncStep.ImportingAccount, 1, 0.7F) {
                        handleSyncFile(file, initSyncStrategy)
                    }
                    // Delete all files
                    workingDir.deleteRecursively()
                } else {
                    val syncResponse = logDuration("INIT_SYNC Request", loggerTag) {
                        executeRequest(globalErrorReceiver) {
                            syncAPI.sync(
                                    params = requestParams,
                                    readTimeOut = readTimeOut
                            )
                        }
                    }

                    logDuration("INIT_SYNC Database insertion", loggerTag) {
                        syncResponseHandler.handleResponse(syncResponse, token, defaultSyncStatusService)
                    }
                }
            }
            defaultSyncStatusService.endAll()
        } else {
            Timber.tag(loggerTag.value).d("Start incremental sync request")
            defaultSyncStatusService.setStatus(SyncStatusService.Status.IncrementalSyncIdle)
            val syncResponse = try {
                executeRequest(globalErrorReceiver) {
                    syncAPI.sync(
                            params = requestParams,
                            readTimeOut = readTimeOut
                    )
                }
            } catch (throwable: Throwable) {
                Timber.tag(loggerTag.value).e(throwable, "Incremental sync request error")
                defaultSyncStatusService.setStatus(SyncStatusService.Status.IncrementalSyncError)
                throw throwable
            }
            val nbRooms = syncResponse.rooms?.invite.orEmpty().size + syncResponse.rooms?.join.orEmpty().size + syncResponse.rooms?.leave.orEmpty().size
            val nbToDevice = syncResponse.toDevice?.events.orEmpty().size
            Timber.tag(loggerTag.value).d("Incremental sync request parsing, $nbRooms room(s) $nbToDevice toDevice(s)")
            defaultSyncStatusService.setStatus(SyncStatusService.Status.IncrementalSyncParsing(
                    rooms = nbRooms,
                    toDevice = nbToDevice
            ))
            syncResponseHandler.handleResponse(syncResponse, token, null)
            Timber.tag(loggerTag.value).d("Incremental sync done")
            defaultSyncStatusService.setStatus(SyncStatusService.Status.IncrementalSyncDone)
        }
        Timber.tag(loggerTag.value).d("Sync task finished on Thread: ${Thread.currentThread().name}")
    }

    private suspend fun downloadInitSyncResponse(requestParams: Map<String, String>): File {
        val workingFile = File(workingDir, "initSync.json")
        val status = initialSyncStatusRepository.getStep()
        if (workingFile.exists() && status >= InitialSyncStatus.STEP_DOWNLOADED) {
            Timber.tag(loggerTag.value).d("INIT_SYNC file is already here")
            reportSubtask(defaultSyncStatusService, InitSyncStep.Downloading, 1, 0.3f) {
                // Empty task
            }
        } else {
            initialSyncStatusRepository.setStep(InitialSyncStatus.STEP_DOWNLOADING)
            val syncResponse = logDuration("INIT_SYNC Perform server request", loggerTag) {
                reportSubtask(defaultSyncStatusService, InitSyncStep.ServerComputing, 1, 0.2f) {
                    getSyncResponse(requestParams, MAX_NUMBER_OF_RETRY_AFTER_TIMEOUT)
                }
            }

            if (syncResponse.isSuccessful) {
                logDuration("INIT_SYNC Download and save to file", loggerTag) {
                    reportSubtask(defaultSyncStatusService, InitSyncStep.Downloading, 1, 0.1f) {
                        syncResponse.body()?.byteStream()?.use { inputStream ->
                            workingFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                }
            } else {
                throw syncResponse.toFailure(globalErrorReceiver)
                        .also { Timber.tag(loggerTag.value).w("INIT_SYNC request failure: $this") }
            }
            initialSyncStatusRepository.setStep(InitialSyncStatus.STEP_DOWNLOADED)
        }
        return workingFile
    }

    private suspend fun getSyncResponse(requestParams: Map<String, String>, maxNumberOfRetries: Int): Response<ResponseBody> {
        var retry = maxNumberOfRetries
        while (true) {
            retry--
            try {
                return syncAPI.syncStream(
                        params = requestParams
                ).awaitResponse()
            } catch (throwable: Throwable) {
                if (throwable is SocketTimeoutException && retry > 0) {
                    Timber.tag(loggerTag.value).w("INIT_SYNC timeout retry left: $retry")
                } else {
                    Timber.tag(loggerTag.value).e(throwable, "INIT_SYNC timeout, no retry left, or other error")
                    throw throwable
                }
            }
        }
    }

    private suspend fun handleSyncFile(workingFile: File, initSyncStrategy: InitialSyncStrategy.Optimized) {
        logDuration("INIT_SYNC handleSyncFile()", loggerTag) {
            val syncResponse = logDuration("INIT_SYNC Read file and parse", loggerTag) {
                syncResponseParser.parse(initSyncStrategy, workingFile)
            }
            initialSyncStatusRepository.setStep(InitialSyncStatus.STEP_PARSED)
            // Log some stats
            val nbOfJoinedRooms = syncResponse.rooms?.join?.size ?: 0
            val nbOfJoinedRoomsInFile = syncResponse.rooms?.join?.values?.count { it.ephemeral is LazyRoomSyncEphemeral.Stored }
            Timber.tag(loggerTag.value).d("INIT_SYNC $nbOfJoinedRooms rooms, $nbOfJoinedRoomsInFile ephemeral stored into files")

            logDuration("INIT_SYNC Database insertion", loggerTag) {
                syncResponseHandler.handleResponse(syncResponse, null, defaultSyncStatusService)
            }
            initialSyncStatusRepository.setStep(InitialSyncStatus.STEP_SUCCESS)
        }
    }

    companion object {
        private const val MAX_NUMBER_OF_RETRY_AFTER_TIMEOUT = 50

        private const val TIMEOUT_MARGIN: Long = 10_000
    }
}
