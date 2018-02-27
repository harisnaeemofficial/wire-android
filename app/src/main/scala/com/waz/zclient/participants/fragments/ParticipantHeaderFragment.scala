/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.participants.fragments

import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.Toolbar
import android.util.TypedValue
import android.view._
import android.view.animation.{AlphaAnimation, Animation}
import android.view.inputmethod.{EditorInfo, InputMethodManager}
import android.widget.TextView
import com.waz.ZLog
import com.waz.ZLog.ImplicitTag._
import com.waz.api.{NetworkMode, Verification}
import com.waz.model.UserData
import com.waz.service.ZMessaging
import com.waz.utils.events.{Signal, Subscription}
import com.waz.utils.returning
import com.waz.zclient.common.controllers.ThemeController
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.common.views.UserDetailsView
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.participants.ParticipantsController
import com.waz.zclient.ui.text.AccentColorEditText
import com.waz.zclient.ui.utils.KeyboardUtils
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{ContextUtils, RichView, ViewUtils}
import com.waz.zclient.views.e2ee.ShieldView
import com.waz.zclient.{FragmentHelper, R}

class ParticipantHeaderFragment extends BaseFragment[ParticipantHeaderFragment.Container] with FragmentHelper {
  import com.waz.threading.Threading.Implicits.Ui

  implicit def cxt: Context = getActivity

  private lazy val participantsController = inject[ParticipantsController]
  private lazy val screenController       = inject[IConversationScreenController]

  private val editInProgress = Signal(false)

  private lazy val internetAvailable = for {
    z           <- inject[Signal[ZMessaging]]
    networkMode <- z.network.networkMode
  } yield networkMode != NetworkMode.OFFLINE && networkMode != NetworkMode.UNKNOWN

  private lazy val editAllowed = for {
    isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
    edit                <- editInProgress
  } yield !isSingleParticipant && !edit

  private var subs = Set.empty[Subscription]

  private lazy val toolbar = view[Toolbar](R.id.t__participants__toolbar)

  private lazy val membersCountTextView = returning(view[TextView](R.id.ttv__participants__sub_header)) { vh =>
    participantsController.otherParticipants.map(_.size).onUi { participants =>
      vh.foreach { mc =>
        mc.setText(getQuantityString(R.plurals.participants__sub_header_xx_people, participants, participants.asInstanceOf[java.lang.Integer]))
        mc.setTextSize(TypedValue.COMPLEX_UNIT_PX, getDimen(R.dimen.wire__text_size__small))
      }
    }

    (for {
      isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
      edit                <- editInProgress
    } yield !isSingleParticipant && !edit)
      .onUi(vis => vh.foreach(_.setVisible(vis)))
  }

  private lazy val userDetailsView = returning(view[UserDetailsView](R.id.udv__participants__user_details)) { vh =>
    participantsController.otherParticipant.collect { case Some(userId) => userId }.onUi(id => vh.foreach(_.setUserId(id)))

    (for {
      isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
      edit                <- editInProgress
    } yield isSingleParticipant && !edit)
      .onUi(vis => vh.foreach(_.setVisible(vis)))
  }

  private lazy val headerEditText = returning(view[AccentColorEditText](R.id.taet__participants__header__editable)) { vh =>
    (for {
      true        <- inject[ThemeController].darkThemeSet
      accentColor <- inject[AccentColorController].accentColor
    } yield accentColor)
      .onUi(c => vh.foreach(_.setAccentColor(c.getColor)))

    editInProgress.onUi {
      case true => vh.foreach { he =>
        he.requestFocus()
        he.setVisible(true)
        headerReadOnlyTextView.foreach { hr =>
          he.setText(hr.getText)
          he.setSelection(hr.getText.length)
        }
      }
      case false => vh.foreach { he =>
        he.clearFocus()
        he.requestLayout()
        he.setVisible(false)
      }
    }
  }

  private lazy val headerReadOnlyTextView = returning(view[TextView](R.id.ttv__participants__header)) { vh =>
    (for {
      convName <- participantsController.conv.map(_.displayName)
      userId   <- participantsController.otherParticipant
      user     <- userId.fold(Signal.const(Option.empty[UserData]))(id => Signal.future(participantsController.getUser(id)))
    } yield user.fold(convName)(_.name))
      .onUi(name => vh.foreach(_.setText(name)))

    editInProgress.onUi(edit => vh.foreach(_.setVisible(!edit)))
    vh.onClick(_ => triggerEditingOfConversationNameIfInternet())
  }

  private lazy val penIcon = returning(view[TextView](R.id.gtv__participants_header__pen_icon)) { vh =>
    editAllowed.onUi(edit => vh.foreach(pi => pi.setVisible(edit)))
    vh.onClick(_ => triggerEditingOfConversationNameIfInternet())
  }

  private lazy val closeIcon = returning(view[TextView](R.id.participants_header__close_icon)) { vh =>
    editInProgress.onUi(edit => vh.foreach(pi => pi.setVisible(!edit)))
    vh.onClick(_ => screenController.hideParticipants(true, false))
  }

  private lazy val shieldView = returning(view[ShieldView](R.id.verified_shield)) { vh =>
    (for {
      isGroupOrBot <- participantsController.isGroupOrBot
      user         <- if (isGroupOrBot) Signal.const(Option.empty[UserData])
      else participantsController.otherParticipant.flatMap {
        case Some(userId) => Signal.future(participantsController.getUser(userId))
        case None         => Signal.const(Option.empty[UserData])
      }
    } yield user.fold(false)(_.verified == Verification.VERIFIED))
      .onUi(isVerified => vh.foreach(_.setVisible(isVerified)))
  }

  private def renameConversation() =
    internetAvailable.head.foreach {
      case true =>
        headerEditText.foreach { he =>
          val text = he.getText.toString.trim
          inject[ConversationController].setCurrentConvName(text)
          headerReadOnlyTextView.foreach(_.setText(text))
        }
      case false =>
        participantsController.conv.map(_.displayName).head.foreach { name =>
          headerReadOnlyTextView.foreach(_.setText(name))
        }
        showOfflineRenameError()
    }

  private val editorActionListener: TextView.OnEditorActionListener = new TextView.OnEditorActionListener() {
    override def onEditorAction(textView: TextView, actionId: Int, event: KeyEvent): Boolean =
      if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode == KeyEvent.KEYCODE_ENTER)) {
        renameConversation()

        headerEditText.foreach { he =>
          he.clearFocus()
          getActivity
            .getSystemService(Context.INPUT_METHOD_SERVICE).asInstanceOf[InputMethodManager]
            .hideSoftInputFromWindow(he.getWindowToken, 0)
        }

        editInProgress ! false
        true
      } else false
  }

  private def triggerEditingOfConversationNameIfInternet(): Unit = (for {
    edit     <- editAllowed.head
    internet <- internetAvailable.head
  } yield (edit, internet)).foreach {
    case (true, false) => showOfflineRenameError()
    case (true, true)  => editInProgress ! true
    case _ =>
  }

  private def showOfflineRenameError(): Unit =
    ViewUtils.showAlertDialog(
      getActivity,
      R.string.alert_dialog__no_network__header,
      R.string.rename_conversation__no_network__message,
      R.string.alert_dialog__confirmation,
      null,
      true
    )

  // This is a workaround for the bug where child fragments disappear when
  // the parent is removed (as all children are first removed from the parent)
  // See https://code.google.com/p/android/issues/detail?id=55228
  // Apply the workaround only if this is a child fragment, and the parent is being removed.
  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation = Option(getParentFragment) match {
    case Some(parent: Fragment) if enter && parent.isRemoving => returning(new AlphaAnimation(1, 1)){
      _.setDuration(ViewUtils.getNextAnimationDuration(parent))
    }
    case _ => super.onCreateAnimation(transit, enter, nextAnim)
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    inflater.inflate(R.layout.fragment_participants_header, container, false)
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    toolbar

    membersCountTextView
    userDetailsView
    shieldView
    headerEditText
    headerReadOnlyTextView
    penIcon
    closeIcon

    subs += editInProgress.onUi {
      case true =>
        screenController.editConversationName(true)
        KeyboardUtils.showKeyboard(getActivity)
      case false =>
        screenController.editConversationName(false)
        KeyboardUtils.hideKeyboard(getActivity)
    }
  }

  def onBackPressed(): Boolean =
    if (editInProgress.currentValue.getOrElse(false)) {
      ZLog.verbose(s"ParticipantFragment editInProgress")
      renameConversation()
      editInProgress ! false
      true
    } else false

  override def onPause(): Unit = {
    toolbar.foreach(_.setNavigationOnClickListener(null))

    super.onPause()
  }

  override def onResume(): Unit = {
    super.onResume()
    toolbar.foreach(_.setNavigationOnClickListener(new View.OnClickListener() {
      override def onClick(v: View): Unit = getActivity.onBackPressed()
    }))

    headerEditText.foreach { _.setOnEditorActionListener(editorActionListener) }
  }

  override def onDestroyView(): Unit = {
    subs.foreach(_.destroy())
    subs = Set.empty

    super.onDestroyView()
  }
}

object ParticipantHeaderFragment {
  val TAG: String = classOf[ParticipantHeaderFragment].getName

  def newInstance: ParticipantHeaderFragment = new ParticipantHeaderFragment

  trait Container {
  }
}