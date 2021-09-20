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
 *
 */

package org.matrix.android.sdk.internal.session.presence.service

import org.matrix.android.sdk.api.session.presence.PresenceService
import org.matrix.android.sdk.internal.session.presence.messages.PresenceEnum
import org.matrix.android.sdk.internal.session.presence.service.task.GetPresenceTask
import org.matrix.android.sdk.internal.session.presence.service.task.SetPresenceTask
import javax.inject.Inject

internal class DefaultPresenceService @Inject constructor(private val setPresenceTask: SetPresenceTask,
                                                          private val getPresenceTask: GetPresenceTask) : PresenceService {

    override suspend fun setPresence(userId: String, presence: PresenceEnum, message: String?) {
        setPresenceTask.execute(SetPresenceTask.Params(userId,presence,message))
    }

    override suspend fun getPresence(userId: String) =  getPresenceTask.execute(GetPresenceTask.Params(userId))
}
