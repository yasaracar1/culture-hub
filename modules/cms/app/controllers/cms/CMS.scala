package controllers.cms

import play.api.mvc.{ AnyContent, Action }
import models.cms.{ MenuEntry, CMSPage }
import com.mongodb.casbah.Imports._
import core.MenuElement
import scala.collection.JavaConverters._
import controllers.DelvingController
import com.escalatesoft.subcut.inject.BindingModule

/**
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */
class CMS(implicit val bindingModule: BindingModule) extends DelvingController {

  def listPages = Root {
    MultitenantAction {
      implicit request =>
        val visible = CMSPage.dao.entryList(getLang, None).filter(_.page.published)
        val keyNameMap = visible.map { entry =>
          Map("key" -> entry.page.key, "name" -> entry.page.title)
        }

        Json(keyNameMap)
    }
  }

  def page(key: String, menuKey: Option[String]): Action[AnyContent] = Root {
    MultitenantAction {
      implicit request =>
        CMSPage.dao.find(
          MongoDBObject("key" -> key, "lang" -> getLang.code)
        ).$orderby(MongoDBObject("_id" -> -1)).limit(1).toList.headOption match {
            case None => NotFound(key)
            case Some(page) =>
              if (menuKey.isDefined) {
                val subMenu = MenuEntry.dao.findEntries(menuKey.get).map { e =>
                  MenuElement(
                    url = "/site/" + menuKey.get + "/page/" + e.targetPageKey.getOrElse(""),
                    titleKey = e.title.get(getLang.language).getOrElse(e.menuKey)
                  )
                }.toSeq.asJava
                Ok(Template('page -> page, 'menuEntries -> subMenu, 'menuKey -> menuKey.get))
              } else {
                Ok(Template('page -> page))
              }
          }
    }
  }

}