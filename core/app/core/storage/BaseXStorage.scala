package core.storage

import eu.delving.basex.client._
import eu.delving.basex.client.BaseX
import org.basex.server.ClientSession
import java.io.ByteArrayInputStream
import scala._
import xml.Node

/**
 * BaseX-based Storage engine.
 *
 * One BaseX db == One collection
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object BaseXStorage {

  // TODO use real config, and non-embedded
  val storage = new BaseX("localhost", 1984, "admin", "admin")

  def createCollection(orgId: String, collectionName: String): Collection = {
    val c = Collection(orgId, collectionName)
    storage.createDatabase(c.databaseName)
    c
  }

  def openCollection(c: Collection): Option[Collection] = openCollection(c.orgId, c.name)

  def openCollection(orgId: String, collectionName: String): Option[Collection] = {
    val c = Collection(orgId, collectionName)
    try {
      storage.openDatabase(c.databaseName)
      Some(c)
    } catch {
      case _ => None
    }
  }

  def withSession[T](collection: Collection)(block: ClientSession => T) = {
    storage.withSession(collection.databaseName) {
      session =>
        block(session)
    }
  }

  def withBulkSession[T](collection: Collection)(block: ClientSession => T) = {
    storage.withSession(collection.databaseName) {
      session =>
        session.setAutoflush(false)
        block(session)
        session.setAutoflush(true)
    }
  }

  def store(collection: Collection, records: Iterator[Record], namespaces: Map[String, String]): Int = {
    var inserted: Int = 0
    BaseXStorage.withBulkSession(collection) {
      session =>
        val versions: Map[String, Int] = (session.find("""for $i in /record let $id := $i/@id group by $id return <version id="{$id}">{count($i)}</version>""") map {
          v: Node =>
            ((v \ "@id").text -> v.text.toInt)
        }).toMap

        val it = records.zipWithIndex
        while(it.hasNext) {
          val next = it.next()
          if(next._2 % 10000 == 0) session.flush()
          session.add(next._1.id, buildRecord(next._1, versions.get(next._1.id).getOrElse(0), namespaces, next._2))
          inserted = next._2
        }
        session.flush()
        session.createAttributeIndex()
      }
    inserted
  }

  def count(collection: Collection): Int = {
    val c = openCollection(collection)
    c.map {
      col =>
        withSession(col) {
          implicit session => count
        }
    }.getOrElse {
      0
    }
  }

  def buildRecord(record: Record, version: Int, namespaces: Map[String, String], index: Int) = {

    val ns = namespaces.map(ns => """xmlns:%s="%s"""".format(ns._1, ns._2)).mkString(" ")

    new ByteArrayInputStream("""<record id="%s" version="%s" %s>
      <system>
        <schemaPrefix>%s</schemaPrefix>
        <index>%s</index>
      </system>
      <document>%s</document>
      <links/>
    </record>""".format(record.id, version, ns, record.schemaPrefix, index, record.document).getBytes("utf-8"))
  }


  // ~~~ Collection queries

  def currentCollectionVersion(implicit session: ClientSession): Int = {
    session.findOne("let $r := /record return <currentCollectionVersion>{max($r/@version)}</currentCollectionVersion>").get.text.toInt
  }

  def count(implicit session: ClientSession): Int = {
    session.findOne("let $r := /record[@version = %s] return <count>{count($r)}</count>".format(currentCollectionVersion)).get.text.toInt
  }

  def findAllCurrent(implicit session: ClientSession) = {
    session.find("for $i in /record[@version = %s] order by $i/system/index return $i".format(currentCollectionVersion))
  }

}

case class Record(id: String, schemaPrefix: String, document: String)

case class Collection(orgId: String, name: String) {
  val databaseName = orgId + "____" + name
}




