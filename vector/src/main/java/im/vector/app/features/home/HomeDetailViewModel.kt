/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.home

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.AppStateHandler
import im.vector.app.RoomGroupingMethod
import im.vector.app.core.di.HasScreenInjector
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.call.dialpad.DialPadLookup
import im.vector.app.features.call.lookup.CallProtocolsChecker
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.createdirect.DirectRoomHelper
import im.vector.app.features.invite.AutoAcceptInvites
import im.vector.app.features.invite.showInvites
import im.vector.app.features.ui.UiStateRepository
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.ActiveSpaceFilter
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.query.RoomTagQueryFilter
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.rx.asObservable
import org.matrix.android.sdk.rx.rx
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * View model used to update the home bottom bar notification counts, observe the sync state and
 * change the selected room list view
 */
class HomeDetailViewModel @AssistedInject constructor(@Assisted initialState: HomeDetailViewState,
                                                      private val session: Session,
                                                      private val uiStateRepository: UiStateRepository,
                                                      private val callManager: WebRtcCallManager,
                                                      private val directRoomHelper: DirectRoomHelper,
                                                      private val appStateHandler: AppStateHandler,
private val autoAcceptInvites: AutoAcceptInvites)
    : VectorViewModel<HomeDetailViewState, HomeDetailAction, HomeDetailViewEvents>(initialState),
        CallProtocolsChecker.Listener {

    @AssistedFactory
    interface Factory {
        fun create(initialState: HomeDetailViewState): HomeDetailViewModel
    }

    companion object : MvRxViewModelFactory<HomeDetailViewModel, HomeDetailViewState> {

        override fun initialState(viewModelContext: ViewModelContext): HomeDetailViewState? {
            val uiStateRepository = (viewModelContext.activity as HasScreenInjector).injector().uiStateRepository()
            return HomeDetailViewState(
                    currentTab = HomeTab.RoomList(uiStateRepository.getDisplayMode())
            )
        }

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: HomeDetailViewState): HomeDetailViewModel? {
            val fragment: HomeDetailFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.homeDetailViewModelFactory.create(state)
        }
    }

    init {
        observeSyncState()
        observeRoomGroupingMethod()
        observeRoomSummaries()
        updateShowDialPadTab()
        callManager.addProtocolsCheckerListener(this)
        session.rx().liveUser(session.myUserId).execute {
            copy(
                    myMatrixItem = it.invoke()?.getOrNull()?.toMatrixItem()
            )
        }
    }

    override fun handle(action: HomeDetailAction) {
        when (action) {
            is HomeDetailAction.SwitchTab                -> handleSwitchTab(action)
            HomeDetailAction.MarkAllRoomsRead            -> handleMarkAllRoomsRead()
            is HomeDetailAction.StartCallWithPhoneNumber -> handleStartCallWithPhoneNumber(action)
        }
    }

    private fun handleStartCallWithPhoneNumber(action: HomeDetailAction.StartCallWithPhoneNumber) {
        viewModelScope.launch {
            try {
                _viewEvents.post(HomeDetailViewEvents.Loading)
                val result = DialPadLookup(session, callManager, directRoomHelper).lookupPhoneNumber(action.phoneNumber)
                callManager.startOutgoingCall(result.roomId, result.userId, isVideoCall = false)
                _viewEvents.post(HomeDetailViewEvents.CallStarted)
            } catch (failure: Throwable) {
                _viewEvents.post(HomeDetailViewEvents.FailToCall(failure))
            }
        }
    }

    private fun handleSwitchTab(action: HomeDetailAction.SwitchTab) = withState { state ->
        if (state.currentTab != action.tab) {
            setState {
                copy(currentTab = action.tab)
            }
            if (action.tab is HomeTab.RoomList) {
                uiStateRepository.storeDisplayMode(action.tab.displayMode)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        callManager.removeProtocolsCheckerListener(this)
    }

    override fun onPSTNSupportUpdated() {
        updateShowDialPadTab()
    }

    private fun updateShowDialPadTab() {
        setState {
            copy(showDialPadTab = callManager.supportsPSTNProtocol)
        }
    }

    // PRIVATE METHODS *****************************************************************************

    private fun handleMarkAllRoomsRead() = withState { _ ->
        // questionable to use viewmodelscope
        viewModelScope.launch(Dispatchers.Default) {
            val roomIds = session.getRoomSummaries(
                    roomSummaryQueryParams {
                        memberships = listOf(Membership.JOIN)
                        roomCategoryFilter = RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS
                    }
            )
                    .map { it.roomId }
            try {
                session.markAllAsRead(roomIds)
            } catch (failure: Throwable) {
                Timber.d(failure, "Failed to mark all as read")
            }
        }
    }

    private fun observeSyncState() {
        session.rx()
                .liveSyncState()
                .subscribe { syncState ->
                    setState {
                        copy(syncState = syncState)
                    }
                }
                .disposeOnClear()
    }

    private fun observeRoomGroupingMethod() {
        appStateHandler.selectedRoomGroupingObservable
                .subscribe {
                    setState {
                        copy(
                                roomGroupingMethod = it.orNull() ?: RoomGroupingMethod.BySpace(null)
                        )
                    }
                }
                .disposeOnClear()
    }

    private fun observeRoomSummaries() {
        appStateHandler.selectedRoomGroupingObservable.distinctUntilChanged().switchMap {
            // we use it as a trigger to all changes in room, but do not really load
            // the actual models
            session.getPagedRoomSummariesLive(
                    roomSummaryQueryParams {
                        memberships = Membership.activeMemberships()
                    },
                    sortOrder = RoomSortOrder.NONE
            ).asObservable()
        }
                .observeOn(Schedulers.computation())
                .throttleFirst(300, TimeUnit.MILLISECONDS)
                .subscribe {
                    when (val groupingMethod = appStateHandler.getCurrentRoomGroupingMethod()) {
                        is RoomGroupingMethod.ByLegacyGroup -> {
                            // TODO!!
                        }
                        is RoomGroupingMethod.BySpace       -> {
                            val activeSpaceRoomId = groupingMethod.spaceSummary?.roomId
                            var dmInvites = 0
                            var roomsInvite = 0
                            if (autoAcceptInvites.showInvites()) {
                                dmInvites = session.getRoomSummaries(
                                        roomSummaryQueryParams {
                                            memberships = listOf(Membership.INVITE)
                                            roomCategoryFilter = RoomCategoryFilter.ONLY_DM
                                            activeSpaceFilter = activeSpaceRoomId?.let { ActiveSpaceFilter.ActiveSpace(it) } ?: ActiveSpaceFilter.None
                                        }
                                ).size

                                roomsInvite = session.getRoomSummaries(
                                        roomSummaryQueryParams {
                                            memberships = listOf(Membership.INVITE)
                                            roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
                                            activeSpaceFilter = ActiveSpaceFilter.ActiveSpace(groupingMethod.spaceSummary?.roomId)
                                        }
                                ).size
                            }

                            val dmRooms = session.getNotificationCountForRooms(
                                    roomSummaryQueryParams {
                                        memberships = listOf(Membership.JOIN)
                                        roomCategoryFilter = RoomCategoryFilter.ONLY_DM
                                        roomTagQueryFilter = RoomTagQueryFilter(false, null, null)
                                        activeSpaceFilter = activeSpaceRoomId?.let { ActiveSpaceFilter.ActiveSpace(it) } ?: ActiveSpaceFilter.None
                                    }
                            )

                            val otherRooms = session.getNotificationCountForRooms(
                                    roomSummaryQueryParams {
                                        memberships = listOf(Membership.JOIN)
                                        roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
                                        roomTagQueryFilter = RoomTagQueryFilter(false, null, null)
                                        activeSpaceFilter = ActiveSpaceFilter.ActiveSpace(groupingMethod.spaceSummary?.roomId)
                                    }
                            )

                            val favRooms = session.getNotificationCountForRooms(
                                    roomSummaryQueryParams {
                                        memberships = listOf(Membership.JOIN)
                                        roomTagQueryFilter = RoomTagQueryFilter(true, null, null)
                                        activeSpaceFilter = ActiveSpaceFilter.ActiveSpace(groupingMethod.spaceSummary?.roomId)
                                    }
                            )

                            setState {
                                copy(
                                        notificationCountHome = roomsInvite + dmInvites + favRooms.totalCount,
                                        notificationHighlightHome = dmInvites + roomsInvite > 0 || favRooms.totalCount > 0,
                                        notificationCountPeople = dmRooms.totalCount,
                                        notificationHighlightPeople = dmRooms.isHighlight,
                                        notificationCountRooms = otherRooms.totalCount + roomsInvite,
                                        notificationHighlightRooms = otherRooms.isHighlight,
                                        hasUnreadMessages = dmRooms.totalCount + otherRooms.totalCount > 0
                                )
                            }
                        }
                    }
                }
                .disposeOnClear()
    }
}
