/*
 *
 *  * Copyright 2019 New Vector Ltd
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package im.vector.riotx.features.home.createdirect

import arrow.core.Option
import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.*
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.user.model.User
import im.vector.matrix.android.internal.util.firstLetterOfDisplayName
import im.vector.riotx.R
import im.vector.riotx.core.epoxy.NoResultItem_
import im.vector.riotx.core.epoxy.errorWithRetryItem
import im.vector.riotx.core.epoxy.loadingItem
import im.vector.riotx.core.epoxy.noResultItem
import im.vector.riotx.core.error.ErrorFormatter
import im.vector.riotx.core.resources.StringProvider
import im.vector.riotx.features.home.AvatarRenderer
import javax.inject.Inject

class CreateDirectRoomController @Inject constructor(private val session: Session,
                                                     private val avatarRenderer: AvatarRenderer,
                                                     private val stringProvider: StringProvider,
                                                     private val errorFormatter: ErrorFormatter) : EpoxyController() {

    private var state: CreateDirectRoomViewState? = null
    var displayMode = CreateDirectRoomViewState.DisplayMode.KNOWN_USERS

    var callback: Callback? = null

    init {
        requestModelBuild()
    }

    fun setData(state: CreateDirectRoomViewState) {
        this.state = state
        requestModelBuild()
    }

    override fun buildModels() {
        val currentState = state ?: return
        val hasSearch = currentState.searchTerm.isNotBlank()
        val asyncUsers = if (displayMode == CreateDirectRoomViewState.DisplayMode.DIRECTORY_USERS) {
            currentState.directoryUsers
        } else {
            currentState.knownUsers
        }
        when (asyncUsers) {
            is Uninitialized -> renderEmptyState(false)
            is Loading       -> renderLoading()
            is Success       -> renderSuccess(asyncUsers(), currentState.selectedUsers.map { it.userId }, hasSearch)
            is Fail          -> renderFailure(asyncUsers.error)
        }
    }

    private fun renderLoading() {
        loadingItem {
            id("loading")
        }
    }

    private fun renderFailure(failure: Throwable) {
        errorWithRetryItem {
            id("error")
            text(errorFormatter.toHumanReadable(failure))
            listener { callback?.retryDirectoryUsersRequest() }
        }
    }

    private fun renderSuccess(users: List<User>,
                              selectedUsers: List<String>,
                              hasSearch: Boolean) {
        if (users.isEmpty()) {
            renderEmptyState(hasSearch)
        } else {
            renderUsers(users, selectedUsers)
        }
    }

    private fun renderUsers(users: List<User>, selectedUsers: List<String>) {
        var lastFirstLetter: String? = null
        for (user in users) {
            if (user.userId == session.myUserId) {
                continue
            }
            val isSelected = selectedUsers.contains(user.userId)
            val currentFirstLetter = user.displayName.firstLetterOfDisplayName()
            val showLetter = currentFirstLetter.isNotEmpty() && lastFirstLetter != currentFirstLetter
            lastFirstLetter = currentFirstLetter

            CreateDirectRoomLetterHeaderItem_()
                    .id(currentFirstLetter)
                    .letter(currentFirstLetter)
                    .addIf(showLetter, this)

            createDirectRoomUserItem {
                id(user.userId)
                selected(isSelected)
                userId(user.userId)
                name(user.displayName)
                avatarUrl(user.avatarUrl)
                avatarRenderer(avatarRenderer)
                clickListener { _ ->
                    callback?.onItemClick(user)
                }
            }
        }
    }

    private fun renderEmptyState(hasSearch: Boolean) {
        val noResultRes = if (displayMode == CreateDirectRoomViewState.DisplayMode.DIRECTORY_USERS) {
            if (hasSearch) {
                R.string.no_result_placeholder
            } else {
                R.string.direct_room_start_search
            }
        } else {
            R.string.direct_room_no_known_users
        }
        noResultItem {
            id("noResult")
            text(stringProvider.getString(noResultRes))
        }
    }

    interface Callback {
        fun onItemClick(user: User)
        fun retryDirectoryUsersRequest() {
            // NO-OP
        }
    }

}