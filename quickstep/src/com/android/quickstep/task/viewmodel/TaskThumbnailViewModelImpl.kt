/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language goveryning permissions and
 * limitations under the License.
 */

package com.android.quickstep.task.viewmodel

import android.annotation.ColorInt
import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.graphics.Matrix
import android.util.Log
import androidx.core.graphics.ColorUtils
import com.android.launcher3.util.coroutines.DispatcherProvider
import com.android.quickstep.recents.data.RecentTasksRepository
import com.android.quickstep.recents.usecase.GetThumbnailPositionUseCase
import com.android.quickstep.recents.usecase.ThumbnailPositionState
import com.android.quickstep.recents.viewmodel.RecentsViewData
import com.android.quickstep.task.thumbnail.SplashAlphaUseCase
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.BackgroundOnly
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.LiveTile
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Snapshot
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.SnapshotSplash
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Uninitialized
import com.android.systemui.shared.recents.model.Task
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class TaskThumbnailViewModelImpl(
    recentsViewData: RecentsViewData,
    taskContainerData: TaskContainerData,
    dispatcherProvider: DispatcherProvider,
    private val tasksRepository: RecentTasksRepository,
    private val getThumbnailPositionUseCase: GetThumbnailPositionUseCase,
    private val splashAlphaUseCase: SplashAlphaUseCase,
) : TaskThumbnailViewModel {
    private val task = MutableStateFlow<Flow<Task?>>(flowOf(null))
    private val splashProgress = MutableStateFlow(flowOf(0f))
    private var taskId: Int = INVALID_TASK_ID

    override val dimProgress: Flow<Float> =
        combine(taskContainerData.taskMenuOpenProgress, recentsViewData.tintAmount) {
                taskMenuOpenProgress,
                tintAmount ->
                max(taskMenuOpenProgress * MAX_SCRIM_ALPHA, tintAmount)
            }
            .flowOn(dispatcherProvider.background)

    override val splashAlpha =
        splashProgress.flatMapLatest { it }.flowOn(dispatcherProvider.background)

    private val isLiveTile =
        combine(
                task.flatMapLatest { it }.map { it?.key?.id }.distinctUntilChanged(),
                recentsViewData.runningTaskIds,
                recentsViewData.runningTaskShowScreenshot,
            ) { taskId, runningTaskIds, runningTaskShowScreenshot ->
                runningTaskIds.contains(taskId) && !runningTaskShowScreenshot
            }
            .distinctUntilChanged()

    override val uiState: Flow<TaskThumbnailUiState> =
        combine(task.flatMapLatest { it }, isLiveTile) { taskVal, isRunning ->
                // TODO(b/369339561) This log is firing a lot. Reduce emissions from TasksRepository
                //  then re-enable this log.
                //                Log.d(
                //                    TAG,
                //                    "Received task and / or live tile update. taskVal: $taskVal"
                //                    + " isRunning: $isRunning.",
                //                )
                when {
                    taskVal == null -> Uninitialized
                    isRunning -> LiveTile
                    isBackgroundOnly(taskVal) ->
                        BackgroundOnly(taskVal.colorBackground.removeAlpha())
                    isSnapshotSplashState(taskVal) ->
                        SnapshotSplash(createSnapshotState(taskVal), taskVal.icon)
                    else -> Uninitialized
                }
            }
            .distinctUntilChanged()
            .flowOn(dispatcherProvider.background)

    override fun bind(taskId: Int) {
        Log.d(TAG, "bind taskId: $taskId")
        this.taskId = taskId
        task.value = tasksRepository.getTaskDataById(taskId)
        splashProgress.value = splashAlphaUseCase.execute(taskId)
    }

    override fun getThumbnailPositionState(width: Int, height: Int, isRtl: Boolean): Matrix {
        return runBlocking {
            when (
                val thumbnailPositionState =
                    getThumbnailPositionUseCase.run(taskId, width, height, isRtl)
            ) {
                is ThumbnailPositionState.MatrixScaling -> thumbnailPositionState.matrix
                is ThumbnailPositionState.MissingThumbnail -> Matrix.IDENTITY_MATRIX
            }
        }
    }

    private fun isBackgroundOnly(task: Task): Boolean = task.isLocked || task.thumbnail == null

    private fun isSnapshotSplashState(task: Task): Boolean {
        val thumbnailPresent = task.thumbnail?.thumbnail != null
        val taskLocked = task.isLocked

        return thumbnailPresent && !taskLocked
    }

    private fun createSnapshotState(task: Task): Snapshot {
        val thumbnailData = task.thumbnail
        val bitmap = thumbnailData?.thumbnail!!
        return Snapshot(bitmap, thumbnailData.rotation, task.colorBackground.removeAlpha())
    }

    @ColorInt private fun Int.removeAlpha(): Int = ColorUtils.setAlphaComponent(this, 0xff)

    private companion object {
        const val MAX_SCRIM_ALPHA = 0.4f
        const val TAG = "TaskThumbnailViewModel"
    }
}
