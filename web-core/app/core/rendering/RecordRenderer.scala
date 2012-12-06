package core.rendering

import core.Constants._
import core.HubId
import core.search.{DelvingIdType, BriefDocItem, DocItemReference, SolrQueryService}
import models.{Role, RecordDefinition, MetadataCache, DomainConfiguration}
import java.net.{URLDecoder, URLEncoder}
import play.api.Logger
import xml._
import play.api.i18n.Lang
import eu.delving.schema.SchemaVersion

/**
 * Renders a single record, with or without related items. Makes use of the search engine to retrieve related records and IDs.
 *
 * TODO simplify and consolidate this mess when we've got some time.
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object RecordRenderer {

  val log = Logger("CultureHub")

  /**
   * Gets the full view of a record, for access via the search API.
   * This is complex because we have to cater for legacy deployments with special idTypes
   */
  def getRenderedFullView(id: String,
                          idType: DelvingIdType,
                          lang: Lang,
                          schema: Option[String] = None,
                          renderRelatedItems: Boolean,
                          mltCount: Int)(implicit configuration: DomainConfiguration): Either[String, RenderedView] = {


    SolrQueryService.getSolrItemReference(URLEncoder.encode(id, "utf-8"), idType, renderRelatedItems, mltCount) match {
      case Some(DocItemReference(hubId, defaultSchema, publicSchemas, relatedItems, item)) =>
        val prefix = if(schema.isDefined && publicSchemas.contains(schema.get)) {
          schema.get
        } else if(schema.isDefined && !publicSchemas.contains(schema.get)) {
          val m = "Schema '%s' not available for hubId '%s'".format(schema.get, hubId)
          Logger("Search").info(m)
          return Left(m)
        } else {
          defaultSchema
        }

        idType match {
          case DelvingIdType.ITIN =>
            // TODO legacy support, to be removed on 01.06.2013
            renderItinItem(item, relatedItems)
          case DelvingIdType.INDEX_ITEM =>
            renderIndexItem(id)
          case _ =>
            renderMetadataRecord(prefix, URLDecoder.decode(hubId, "utf-8"), ViewType.API, lang, renderRelatedItems, relatedItems)
        }
      case None =>
        Left("Could not resolve identifier for hubId '%s' and idType '%s'".format(id, idType.idType))
    }
  }

  private def renderItinItem(item: Option[BriefDocItem], relatedItems: Seq[BriefDocItem]) = {

    val document = <result xmlns:delving="http://www.delving.eu/schemas/"
                           xmlns:icn="http://www.icn.nl/"
                           xmlns:dc="http://purl.org/dc/elements/1.1/"
                           xmlns:custom="http://www.delving.eu/schemas/"
                           xmlns:dcterms="http://purl.org/dc/termes/"
                           xmlns:itin="http://www.itin.nl/namespace"
                           xmlns:drup="http://www.itin.nl/drupal"
                           xmlns:europeana="http://www.europeana.eu/schemas/ese/">

      { item.map { i => {i.toXml()} }.getOrElse(<item/>) }
      <relatedItems>{ relatedItems.map { ri => {ri.toXml()} }}</relatedItems>
    </result>

    Right(new RenderedView {
      def toXml: NodeSeq = document
      def toViewTree: RenderNode = null
      def toXmlString: String = document.toString()
      def toJson: String = "JSON rendering is unsupported"
    })

  }

  private def renderIndexItem(id: String): Either[String, RenderedView] = {
    if(id.split("_").length < 3) {
      Left("Invalid hubId")
    } else {
      val hubId = HubId(id)
      val cache = MetadataCache.get(hubId.orgId, "indexApiItems", hubId.spec)
      val indexItem = cache.findOne(hubId.localId).getOrElse(return Left("Could not find IndexItem with id '%s".format(id)))
      Right(new RenderedView {
        def toXmlString: String = indexItem.xml("raw")
        def toJson: String = "JSON rendering not supported"
        def toXml: NodeSeq = scala.xml.XML.loadString(indexItem.xml("raw"))
        def toViewTree: RenderNode = null
      })
    }
  }

  private def renderMetadataRecord(prefix: String, hubId: String, viewType: ViewType, lang: Lang, renderRelatedItems: Boolean, relatedItems: Seq[BriefDocItem])(implicit configuration: DomainConfiguration): Either[String, RenderedView] = {
    if(hubId.split("_").length < 3) return Left("Invalid hubId " + hubId)
    val id = HubId(hubId)
    val cache = MetadataCache.get(id.orgId, id.spec, ITEM_TYPE_MDR)
    val record = cache.findOne(hubId)
    val rawRecord: Option[String] = record.flatMap(_.xml.get(prefix))
    if (rawRecord.isEmpty) {
      Logger("Search").info("Could not find cached record in mongo with format %s for hubId %s".format(prefix, hubId))
      Left("Could not find full record with hubId '%s' for format '%s'".format(hubId, prefix))
    } else {

      // handle legacy formats
      val legacyApiFormats = List("tib", "abm", "ese", "abc")
      val legacyHtmlFormats = List("abm", "ese", "abc")
      val viewDefinitionFormatName = if (viewType == ViewType.API) {
        if(legacyApiFormats.contains(prefix)) "legacy" else prefix
      } else {
        if(legacyHtmlFormats.contains(prefix)) "legacy" else prefix
      }

      val schemaVersion = record.get.schemaVersions(prefix)

      renderMetadataRecord(hubId, rawRecord.get, new SchemaVersion(prefix, schemaVersion), viewDefinitionFormatName, viewType, lang, renderRelatedItems, relatedItems.map(_.toXml()))
    }
  }

  /**
   * Renders a metadata record
   *
   * @param hubId the hubId
   * @param recordXml the raw record (XML string)
   * @param schema the schema being rendered
   * @param viewDefinitionFormatName prefix of the view rendering schema. in principle the same as the one of the record, but there may be exceptions (e.g. "legacy")
   * @param viewType viewType (API / HTML)
   * @param lang rendering languages
   * @param renderRelatedItems whether to render related items
   * @param relatedItems the related items
   * @param configuration DomainConfiguration
   * @return a rendered view if successful, or an error message
   */
  def renderMetadataRecord(hubId: String,
                           recordXml: String,
                           schema: SchemaVersion,
                           viewDefinitionFormatName: String,
                           viewType: ViewType,
                           lang: Lang,
                           renderRelatedItems: Boolean = false,
                           relatedItems: Seq[NodeSeq] = Seq.empty,
                           roles: Seq[Role] = Seq.empty,
                           parameters: Map[String, String] = Map.empty)(implicit configuration: DomainConfiguration): Either[String, RenderedView]  = {

      // let's do some rendering
      RecordDefinition.getRecordDefinition(schema) match {
        case Some(definition) =>
          val viewRenderer = ViewRenderer.fromDefinition(viewDefinitionFormatName, viewType)
          if (viewRenderer.isEmpty) {
            log.warn("Tried rendering full record with id '%s' for non-existing view type '%s'".format(hubId, viewType.name))
            Left("Could not render full record with hubId '%s' for view type '%s': view type does not exist".format(hubId, viewType.name))
          } else {
            try {
              val cleanRawRecord = {
                val record = scala.xml.XML.loadString(recordXml)
                var mutableRecord: Elem = if (renderRelatedItems) {
                  // mix the related items to the record coming from mongo
                  val relatedItemsXml = <relatedItems>{relatedItems}</relatedItems>
                  addChild(record, relatedItemsXml).get // we know what we're doing here
                } else {
                  record
                }
                  // prepend the delving namespace if it ain't there
                  // and yes this check is ugly but Scala's XML <-> namespace support ain't pretty to say the least
                  if(!recordXml.contains("xmlns:delving")) {
                    mutableRecord = mutableRecord % new UnprefixedAttribute("xmlns:delving", "http://schemas.delving.eu/", Null)
                  }
                  if (!recordXml.contains("xmlns:xml")) {
                    mutableRecord = mutableRecord % new UnprefixedAttribute("xmlns:xml", "http://www.w3.org/XML/1998/namespace", Null)
                  }
                  if (!recordXml.contains("xmlns:xsi")) {
                    mutableRecord = mutableRecord % new UnprefixedAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance", Null)
                  }

                  mutableRecord.toString().replaceFirst("<\\?xml.*?>", "")
              }
              log.debug(cleanRawRecord)

              // TODO see what to do with roles
              val defaultNamespaces = Seq(
                "delving" -> "http://schemas.delving.eu/",
                "xml" -> "http://www.w3.org/XML/1998/namespace",
                "xsi" -> "http://www.w3.org/2001/XMLSchema-instance"
                )

              val rendered: RenderedView = viewRenderer.get.renderRecord(cleanRawRecord, roles, definition.getNamespaces ++ defaultNamespaces, lang, parameters)
              Right(rendered)
            } catch {
              case t: Throwable =>
                log.error("Exception while rendering view %s for record %s".format(schema, hubId), t)
                Left("Error while rendering view '%s' for record with hubId '%s'".format(schema, hubId))
            }
          }
        case None =>
          val m = "Error while rendering view '%s' for record with hubId '%s': could not find record definition with prefix '%s'".format(schema, hubId, schema)
          log.error(m)
          Left(m)
      }
  }

  private def addChild(n: Node, newChild: Node): Option[Elem] = n match {
    case Elem(prefix, label, attribs, scope, child @ _*) => Some(Elem(prefix, label, attribs, scope, child ++ newChild : _*))
    case _ =>
      Logger("CultureHub").error("Can only add children to elements!")
      None
  }

}

case class ViewType(name: String)

object ViewType {
  val API = ViewType("api")
  val HTML = ViewType("html")
}
