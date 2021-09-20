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

package org.matrix.android.sdk.api.session.presence

import org.matrix.android.sdk.internal.session.presence.messages.GetPresenceResponse
import org.matrix.android.sdk.internal.session.presence.messages.PresenceEnum

/**
 * This interface defines methods for handling user presence information.
 */

interface PresenceService {

    /**
     * Update the presence status for this user
     * @param userId the userId to update the display name of
     * @param presence the new user presence
     * @param message the new user status message
     */
    suspend fun setPresence(userId: String, presence: PresenceEnum, message: String? = null)

    /**
     * Returns the presence status for this user
     * @param userId the userId to update the avatar of
     */
    suspend fun getPresence(userId: String): GetPresenceResponse

}
