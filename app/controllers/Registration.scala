/*
 * Copyright 2011 Delving B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import notifiers.Mails
import play.api.Play.current
import models.OrganizationConfiguration
import models.HubUser
import extensions.MissingLibs
import play.api._
import cache.Cache
import data.Form._
import http.{ ContentTypeOf, Writeable }
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.i18n.Messages
import validation.{ ValidationError, Valid, Invalid, Constraint }
import play.libs.Time
import play.libs.Images.Captcha
import core.{ RegistrationService, DomainServiceLocator, ThemeInfo, OrganizationService, UserProfileService }
import com.escalatesoft.subcut.inject.BindingModule

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

class Registration(implicit val bindingModule: BindingModule) extends ApplicationController {

  val registrationServiceLocator = inject[DomainServiceLocator[RegistrationService]]
  val organizationServiceLocator = inject[DomainServiceLocator[OrganizationService]]
  val userProfileServiceLocator = inject[DomainServiceLocator[UserProfileService]]

  case class RegistrationInfo(
    firstName: String,
    lastName: String,
    email: String,
    userName: String,
    password1: String,
    password2: String,
    code: String,
    randomId: String)

  def samePassword(implicit configuration: OrganizationConfiguration) = Constraint[RegistrationInfo]("hub.PasswordsAreNotTheSame") {
    case r if r.password1 == r.password2 => Valid
    case _ => Invalid(ValidationError(Messages("hub.PasswordsAreNotTheSame")))
  }

  def captchaConstraint(implicit configuration: OrganizationConfiguration) = Constraint[RegistrationInfo]("hub.InvalidCode") {
    case r if Cache.get(r.randomId) == Some(r.code) || Play.isTest => Valid
    case e => Invalid(ValidationError(Messages("hub.InvalidCode")))
  }

  def emailTaken(implicit configuration: OrganizationConfiguration) = Constraint[RegistrationInfo]("hub.ThereIsAlreadyAUserWithThisEmailAddress") {
    case r if !registrationServiceLocator.byDomain.isEmailTaken(r.email) => Valid
    case _ => Invalid(ValidationError(Messages("hub.ThereIsAlreadyAUserWithThisEmailAddress")))
  }

  def userNameTaken(implicit configuration: OrganizationConfiguration) = Constraint[RegistrationInfo]("hub.ThereIsAlreadyAUserWithThisUsername") {
    case r if !registrationServiceLocator.byDomain.isUserNameTaken(r.userName) => Valid
    case _ => Invalid(ValidationError(Messages("hub.ThereIsAlreadyAUserWithThisUsername")))
  }

  def orgIdTaken(implicit configuration: OrganizationConfiguration) = Constraint[RegistrationInfo]("hub.ThereIsAlreadyAUserWithThisUsername") {
    case r if !organizationServiceLocator.byDomain.exists(r.userName) => Valid
    case _ => Invalid(ValidationError(Messages("hub.ThereIsAlreadyAUserWithThisUsername")))
  }

  def registrationForm(implicit configuration: OrganizationConfiguration): Form[RegistrationInfo] = Form(
    mapping(
      "firstName" -> nonEmptyText,
      "lastName" -> nonEmptyText,
      "email" -> email,
      "userName" -> text.verifying(
        pattern(
          "^[a-z0-9]{3,15}$".r,
          error = Messages("hub.ThisUsernameIsNotValid")
        )
      ),
      "password1" -> nonEmptyText,
      "password2" -> nonEmptyText,
      "code" -> text,
      "randomId" -> text
    )(RegistrationInfo.apply)(RegistrationInfo.unapply).verifying(
        samePassword, captchaConstraint, emailTaken, userNameTaken, orgIdTaken
      )

  )

  def index() = ApplicationAction {
    MultitenantAction {
      implicit request =>
        Ok(Template('randomId -> MissingLibs.UUID, 'registrationForm -> registrationForm))
    }
  }

  def register() = ApplicationAction {
    MultitenantAction(parse.urlFormEncoded) {
      implicit request =>
        registrationForm.bindFromRequest.fold(
          formWithErrors => {
            registrationForm.value.map(r => Cache.set(r.randomId, null))
            BadRequest(Template(
              "/Registration/index.html",
              'randomId -> MissingLibs.UUID,
              'registrationForm -> formWithErrors
            ))
          },
          registration => {
            val r = registration

            Cache.set(r.randomId, null)

            val activationToken = registrationServiceLocator.byDomain.registerUser(
              r.userName,
              configuration.commonsService.nodeName,
              r.firstName,
              r.lastName,
              r.email,
              r.password1
            )

            val index = Redirect("/")

            activationToken match {
              case Some(token) =>
                try {
                  Mails.activation(
                    r.email,
                    r.firstName + " " + r.lastName,
                    token,
                    request.host
                  )
                  index.flashing(("registrationSuccess", r.email))
                } catch {
                  case t: Throwable => {
                    logError(t, t.getMessage, r.userName)
                    index.flashing("registrationError" -> t.getMessage)
                  }
                }
              case None =>
                logError("Could not save new user %s", r.userName)
                index.flashing("registrationError" -> Messages("hub.ErrorCreatingYourAccount"))
            }
          }
        )
    }
  }

  implicit val contentTypeOf_captcha = ContentTypeOf[Captcha](Some("image/png"))

  // there must be a way to stream this tough
  implicit val wCaptcha: Writeable[Captcha] = Writeable[Captcha] { c: Captcha => Stream.continually(c.read).takeWhile(-1 !=).map(_.toByte).toArray }

  def captcha(id: String) = Action {
    implicit request =>
      val captcha = play.libs.Images.captcha
      val code = captcha.getText("#000000")
      Cache.set(id, code, Time.parseDuration("10mn"))
      Ok(captcha)
  }

  def activate(activationToken: String) = ApplicationAction {
    MultitenantAction {
      implicit request =>
        val indexAction = Redirect("/")
        if (Option(activationToken).isEmpty) {
          log.warn("Empty activation token received")
          indexAction.flashing(("activation", "false"))
        } else {
          val activated = registrationServiceLocator.byDomain.activateUser(activationToken)
          if (activated.isDefined) {
            try {
              val userName = activated.get.userName

              // create a local user
              userProfileServiceLocator.byDomain.getUserProfile(userName).map { p =>
                {
                  val newHubUser = HubUser(
                    userName = userName,
                    firstName = p.firstName,
                    lastName = p.lastName,
                    email = p.email,
                    userProfile = models.UserProfile(
                      isPublic = p.isPublic,
                      fixedPhone = p.fixedPhone,
                      description = p.description,
                      funFact = p.funFact,
                      websites = p.websites,
                      twitter = p.twitter,
                      linkedIn = p.linkedIn
                    ),
                    organizations = if (configuration.registeredUsersAddedToOrg) List(configuration.orgId) else List.empty
                  )
                  HubUser.dao.insert(newHubUser)
                }
              }

              Mails.newUser(
                "New user activated on " + configuration.commonsService.nodeName,
                configuration.commonsService.nodeName,
                activated.get.userName,
                activated.get.fullName,
                activated.get.email
              )
              indexAction.flashing(("activation", "true"))
            } catch {
              case t: Throwable =>
                logError(t, "Could not send activation email")
                indexAction.flashing(("activation", "false"))
            }
          } else {
            indexAction.flashing(("activation", "false"))
          }
        }
    }
  }

  def lostPassword = ApplicationAction {
    MultitenantAction {
      implicit request => Ok(Template('resetPasswordForm -> resetPasswordForm))
    }
  }

  def accountNotFound(
    implicit configuration: OrganizationConfiguration) = Constraint[String]("hub.NoAccountCouldBeFound") {
    case r if registrationServiceLocator.byDomain.isEmailTaken(r) => Valid
    case _ => Invalid(ValidationError(Messages("hub.NoAccountCouldBeFound")))
  }

  def accountNotActive(
    implicit configuration: OrganizationConfiguration) = Constraint[String]("hub.ThisAccountIsNotActiveYet") {
    case r if registrationServiceLocator.byDomain.isAccountActive(r) => Valid
    case _ => Invalid(ValidationError(Messages("hub.ThisAccountIsNotActiveYet")))
  }

  case class ResetPassword(email: String)

  def resetPasswordForm(implicit configuration: OrganizationConfiguration): Form[ResetPassword] = Form(
    mapping(
      "email" -> email.verifying(accountNotFound, accountNotActive)
    )(ResetPassword.apply)(ResetPassword.unapply)
  )

  def resetPasswordEmail = ApplicationAction {
    MultitenantAction {
      implicit request =>
        resetPasswordForm.bindFromRequest().fold(
          formWithErrors => BadRequest(Template("Registration/lostPassword.html", 'resetPasswordForm -> formWithErrors)),
          resetPassword => {
            registrationServiceLocator.byDomain.preparePasswordReset(resetPassword.email) match {
              case Some(resetPasswordToken) =>
                Mails.resetPassword(
                  resetPassword.email,
                  resetPasswordToken,
                  request.host
                )
                Redirect("/").flashing(("resetPasswordEmail", "true"))
              case None =>
                // TODO adjust view for this case
                Redirect("/").flashing(("resetPasswordEmail", "false"))
            }
          }
        )

    }
  }

  def resetPassword(resetPasswordToken: String) = MultitenantAction {
    implicit request =>
      renderArgs += ("themeInfo" -> new ThemeInfo(configuration))
      val indexAction = Redirect("/")
      if (Option(resetPasswordToken).isEmpty) {
        indexAction.flashing(("resetPasswordError", Messages("hub.ResetPasswordTokenNotFound")))
      } else {
        Ok(Template('resetPasswordToken -> resetPasswordToken, 'newPasswordForm -> newPasswordForm))
      }
  }

  val sameNewPassword = Constraint[NewPassword]("hub.PasswordsAreNotTheSame") {
    case r if r.password1 == r.password2 => Valid
    case _ => Invalid(ValidationError(Messages("hub.PasswordsAreNotTheSame")))
  }

  case class NewPassword(password1: String, password2: String)

  val newPasswordForm: Form[NewPassword] = Form(
    mapping(
      "password1" -> nonEmptyText,
      "password2" -> nonEmptyText
    )(NewPassword.apply)(NewPassword.unapply) verifying sameNewPassword
  )

  def newPassword(resetPasswordToken: String) = MultitenantAction {
    implicit request =>
      renderArgs += ("themeInfo" -> new ThemeInfo(configuration))
      if (Option(resetPasswordToken).isEmpty) {
        Redirect("/").flashing(
          ("resetPasswordError", Messages("hub.ResetPasswordTokenNotFound"))
        )
      } else {
        newPasswordForm.bindFromRequest().fold(
          formWithErrors => BadRequest(Template("Registration/resetPassword.html", 'newPasswordForm -> formWithErrors)),
          newPassword => {
            val passwordChanged = registrationServiceLocator.byDomain.resetPassword(
              resetPasswordToken,
              newPassword.password1
            )
            if (passwordChanged) {
              Redirect("/").flashing(
                ("resetPasswordSuccess", "true")
              )
            } else {
              Redirect("/").flashing(
                ("resetPasswordError", Messages("hub.ErrorResettingYourPassword"))
              )
            }
          }
        )
      }
  }

}