package controllers.search

/*
 * Copyright 2011 DELVING BV
 *
 * Licensed under the EUPL, Version 1.1 or as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * you may not use this work except in compliance with the
 * Licence.
 * You may obtain a copy of the Licence at:
 *
 * http://ec.europa.eu/idabc/eupl
 *
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

import scala.collection.JavaConversions._
import java.util. {Date, ArrayList}
import java.lang.{Boolean => JBoolean, Float => JFloat}
import org.apache.solr.client.solrj.response. {FacetField, QueryResponse}
import java.net.URL
import xml. {NodeSeq, Elem, XML}
import collection.immutable. {HashMap, Map => ImMap}
import org.apache.solr.client.solrj.response.FacetField.Count
import collection.mutable. {ListBuffer, Map}

/**
 *
 * @author Sjoerd Siebinga <sjoerd.siebinga@gmail.com>
 * @since 10 /18/10 9:01 PM
 */

object SolrBindingService {

  def getFullDocFromOaiPmh(response : QueryResponse) : FullDocItem = {
    val fullDoc = getFullDoc(response)
    val pmhId = fullDoc.getFieldValue("delving_pmhId")
    getRecordFromOaiPmh(pmhId.getFirst)
  }

  private[search] def getRecordFromOaiPmh(recordId : String, metadataPrefix: String = "abm") : FullDocItem = {
    val baseUrl = "http://localhost:8983/services/oai-pmh"
    val record = XML.load(new URL(baseUrl + "?verb=GetRecords&metadataPrefix=" + metadataPrefix + "&identifier=" + recordId))
    parseSolrDocumentFromGetRecordResponse(record)
  }

  def parseSolrDocumentFromGetRecordResponse(pmhResponse: Elem): FullDocItem = {
    val metadataElements = pmhResponse \\ "metadata"
    val recordElements: NodeSeq = metadataElements \\ "record"
    val solrDoc = SolrResultDocument()
    recordElements.foreach{
      recordNode =>
        val cleanNodes = recordNode.nonEmptyChildren.filterNot(node => node.label == "#PCDATA")
        val cleanNodeList = for {
          cleanNode <- cleanNodes
          val fieldName: String = if (!cleanNode.prefix.isEmpty) cleanNode.prefix + "_" + cleanNode.label else cleanNode.label
        } yield (FieldValueNode(fieldName, cleanNode.text, cleanNode.attributes.asAttrMap))
        val fieldNames = for (cleanNode <- cleanNodeList) yield cleanNode.fieldName
        fieldNames.toSet[String].foreach{
          fieldName =>
            solrDoc.add(fieldName, cleanNodeList.filter(node => node.fieldName == fieldName).toList)
        }
    }
    FullDocItem(solrDoc)
  }

  def getSolrDocumentList(queryResponse : QueryResponse) : List[SolrResultDocument] = {
    def addFieldNodes(key : String, values: List[Any]) : List[FieldValueNode] =
      for (value <- values; if value != null ) yield (FieldValueNode(key, value.toString))

    import java.util.{List => JList, Map => JMap}
    val highLightMap: JMap[String, JMap[String, JList[String]]] = queryResponse.getHighlighting

    val docs = new ListBuffer[SolrResultDocument]
    val ArrayListObject = classOf[ArrayList[Any]]
    val StringObject = classOf[String]
    val DateObject = classOf[Date]
    val FloatObject = classOf[JFloat]
    val BooleanObject = classOf[JBoolean]
    // check for required fields else check exception
    queryResponse.getResults.foreach{
        doc =>

          val solrDoc = SolrResultDocument()
          doc.entrySet.foreach{
            field =>
              val FieldValueClass: Class[_] = field.getValue.getClass
               FieldValueClass match {
                case ArrayListObject => solrDoc.add(field.getKey, addFieldNodes(field.getKey, field.getValue.asInstanceOf[ArrayList[Any]].toList))
                case StringObject => solrDoc.add(field.getKey, List(FieldValueNode(field.getKey, field.getValue.toString)))
                case DateObject => solrDoc.add(field.getKey, List(FieldValueNode(field.getKey, field.getValue.toString)))
                case BooleanObject => solrDoc.add(field.getKey, List(FieldValueNode(field.getKey, field.getValue.toString)))
                case FloatObject => solrDoc.add(field.getKey, List(FieldValueNode(field.getKey, field.getValue.toString)))
                case _ => println("unknown class in SolrBindingService " + field.getKey + FieldValueClass.getCanonicalName)
              }
          }
          val id = solrDoc getFirst ("id")
          if (highLightMap != null && highLightMap.containsKey(id)) {
            highLightMap.get(id).foreach(entry => solrDoc addHighLightField (entry._1, entry._2.toList))
          }
      docs add solrDoc
    }
    docs.toList
  }

  def getDocIds(queryResponse: QueryResponse): List[SolrDocId] = {
    val docIds = new ListBuffer[SolrDocId]
    getSolrDocumentList(queryResponse).foreach{
      doc =>
        docIds add (SolrDocId(doc))
    }
   docIds.toList
  }


  def getBriefDocsWithIndex(queryResponse: QueryResponse, start: Int = 1): List[BriefDocItem] = addIndexToBriefDocs(getBriefDocs(queryResponse), start)

  def getBriefDocs(queryResponse: QueryResponse): List[BriefDocItem] = {
    getSolrDocumentList(queryResponse).map(doc => BriefDocItem(doc))
  }

  // todo test this
  def addIndexToBriefDocs(docs: List[BriefDocItem], start: Int): List[BriefDocItem] = {
    docs.foreach(doc => doc.index = docs.indexOf(doc) + start)
    docs
  }

  def getFullDoc(queryResponse: QueryResponse): FullDocItem = {
    val results = getFullDocs(queryResponse)
    if (results.isEmpty) throw new RuntimeException("Full Doc not found") // todo change this to a better exception
    results.head
  }

  def getFullDocs(queryResponse: QueryResponse): List[FullDocItem] = {
    getSolrDocumentList(queryResponse).map(doc => FullDocItem(doc))
  }

  def createFacetMap(links : List[FacetQueryLinks]) = FacetMap(links.toList)

  def createFacetStatistics(facets: List[FacetField]) = FacetStatisticsMap(facets.toList)
}

case class FacetMap(private val links : List[FacetQueryLinks]) {

  val facetMap = Map[String, FacetQueryLinks]()
  links.foreach{
    facet =>
      facetMap put (facet.getType, facet)
  }

  def getFacetList = links

  def getFacet(key: String) : FacetQueryLinks = facetMap.getOrElse(key, FacetQueryLinks("unknown"))
}

case class FacetStatisticsMap(private val facets: List[FacetField]) {

  val facetsMap = Map[String, List[FacetField.Count]]()
  facets.foreach{
    facet =>
      if (facet.getValueCount != 0) facetsMap put (facet.getName, facet.getValues.toList)
  }

  def facetExists(key: String): Boolean = facetsMap.containsKey(key)

  def availableFacets : List[String] = facetsMap.keys.toList

  private def getDummyFacetField : FacetField = {
    val facetField = new FacetField("unknown")
    facetField.add("nothing", 0)
    facetField
  }

  def getFacetValueCount(key: String, facetName: String) = {
    val count : Count = getFacet(facetName).filter(fc => fc.getName == key).headOption.getOrElse(new FacetField.Count(getDummyFacetField, "unknown", 0))
    count.getCount
  }

  def getFacetCount(key: String) = facets.filter(ff => ff.getName == key).headOption.getOrElse(getDummyFacetField).getValueCount

  def getFacet(key: String) : List[FacetField.Count] = facetsMap.getOrElse(key, getDummyFacetField.getValues.toList)

}

case class SolrResultDocument(fieldMap : Map[String, List[FieldValueNode]] = Map[String, List[FieldValueNode]](), highLightMap: Map[String, List[String]] = Map[String, List[String]]()) {

  def get(field: String) : List[String] = for(node: FieldValueNode <- fieldMap.getOrElse(field, List[FieldValueNode]())) yield node.fieldValue

  def getFieldValueNode(field: String) : List[FieldValueNode] = fieldMap.getOrElse(field, List[FieldValueNode]())

  def getFieldValueNodeGroupedByLanguage(field: String) : ImMap[String, List[FieldValueNode]] = fieldMap.getOrElse(field, List[FieldValueNode]()).groupBy(fvn => fvn.getLanguage)

  def getFirst(field: String) : String = fieldMap.getOrElse(field, List[FieldValueNode]()).headOption.getOrElse(FieldValueNode("", "")).fieldValue

  private[search] def add(field: String, value : scala.List[FieldValueNode]) = fieldMap.put(field, value)

  private[search] def addHighLightField(fieldName: String, values: List[String]) = highLightMap.put(fieldName, values)

  private[search] def getFieldNames = fieldMap.keys

  def getFieldValueList : List[FieldValue] = for (key <- fieldMap.keys.toList.filter(_.matches(".*_.*"))) yield FieldValue(key, this)

  def getHighLightsAsFieldValueList : List[FieldValue] = for (key <- highLightMap.keys.toList) yield FieldValue(key, this)

  def getFieldValuesFiltered(include: Boolean, fields : List[String]) : List[FieldValue] = getFieldValueList.filter((fv => fields.contains(fv.getKey) == include))

  def getConcatenatedArray(key: String, fields: List[String]) : FieldFormatted = {
    val concatArray : Array[String] = getFieldValuesFiltered(true, fields).map(fv => fv.getValueAsArray).flatten.toArray
    FieldFormatted(key, concatArray)
  }
}

case class FieldFormatted (key: String, values: Array[String]) {
  def getKey : String = key
  def getKeyAsMessageKey = "_metadata.%s" format (key.replaceFirst("_", "."))
  def getValues : Array[String] = values
  def getValuesFormatted(separator: String = ";&#160;") : String = values.mkString(separator)
  def isNotEmpty : Boolean = !values.isEmpty

}

case class FieldValue (key: String, solrDocument: SolrResultDocument) {

  private val fieldValues = solrDocument.get(key)
  private val highLightValues: Option[List[String]] = solrDocument.highLightMap.get(key)

  /**
   * This gives back the key that was used to retrieve the fields from the SolrResultDocument
   */
  def getKey = key

  /**
   * This gives back the key that was used to retrieve the fields from the SolrResultDocument, but now replacing the "_" convention
   * used by solr to ":" so that it can be used in xml tags or to represented the fieldnames as they were before being indexed
   * by Apache Solr
   */
  def getKeyAsXml = key.replaceFirst("_", ":")


  /**
   * This gives back the key formatted as a metadata key as specified in the message.properties files.
   */
  def getKeyAsMessageKey = "_metadata.%s" format (key.replaceFirst("_", "."))

  /**
   * Only give back the first item from the fieldMap retrieved with 'key' in the SolrResultDocument as a String. When the key
   * is not found an empty String is returned.
   */
  def getFirst : String = solrDocument.getFirst(key)

  /**
   * Give back all values found in the fieldMap retrieved with 'key' in the SolrResultDocument as a String Array. When the
   * key is not found an empty String Array is returned.
   */
  def getValueAsArray : Array[String] = fieldValues.asInstanceOf[List[String]].toArray

  def getHighLightValuesAsArray : Array[String] = highLightValues.getOrElse(List.empty).asInstanceOf[List[String]].toArray

  /**
   * Give back all values found in the fieldMap retrieved with 'key' in the SolrResultDocument as a Formatted String. When the
   * key is not found an empty String is returned.
   */

  def getArrayAsString(separator: String = ";&#160;") : String = fieldValues.mkString(separator)

  /**
   * This function gives back a boolean to say if the results returned from the fieldMap in the SolrResultDocument will be empty or not
   */
  def isNotEmpty = fieldValues.length != 0

  def hasHighLights = if (highLightValues != None) true else false

}

case class FieldValueNode (fieldName : String, fieldValue: String, attributes: ImMap[String, String] = new HashMap[String, String]())  {

  def getFieldName = fieldName

  def getFieldValue = fieldValue

  def getAttribute(key : String) = attributes.getOrElse(key, "")

  def getLanguage = attributes.getOrElse("xml:lang", "unknown")

  def hasLanguageAttribute = attributes.contains("xml:lang")

  def hasAttributes = !attributes.isEmpty

  def getAttributeKeys = attributes.keys
}

case class SolrDocId(solrDocument : SolrResultDocument) {
  def getEuropeanaUri : String = solrDocument.getFirst("europeana_uri")
}

case class BriefDocItem(solrDocument : SolrResultDocument) extends MetadataAccessors  {

    override protected def assign(key: String) = solrDocument.getFirst(key)

    def getFieldValue(key : String) : FieldValue = FieldValue(key, solrDocument)

    def getFieldValuesFiltered(include: Boolean, fields: Array[String]) : List[FieldValue] = solrDocument.getFieldValuesFiltered(include, fields.toList)

    def getFieldValueList : List[FieldValue] = solrDocument.getFieldValueList

    def getAsString(key: String) : String = assign(key)

    def getHighlights: List[FieldValue] = solrDocument.getHighLightsAsFieldValueList

    var index : Int = _
    var fullDocUrl: String = _

    // debug and scoring information
    var score : Int = _
    var debugQuery : String = _
}

case class FullDocItem(solrDocument : SolrResultDocument) extends MetadataAccessors {

    override protected def assign(key: String) = solrDocument.getFirst(key)

    def getAsArray(key: String) : Array[String] = solrDocument.get(key).asInstanceOf[List[String]].toArray

    def getAsString(key: String) : String = assign(key)

    def getFieldValue(key : String) : FieldValue = FieldValue(key, solrDocument)

    def getFieldValueList: List[FieldValue] = solrDocument.getFieldValueList

    def getFieldValuesFiltered(include: Boolean, fields: Array[String]) : List[FieldValue] = solrDocument.getFieldValuesFiltered(include, fields.toList)

    def getConcatenatedArray(key: String, fields: Array[String]) : FieldFormatted = solrDocument.getConcatenatedArray(key, fields.toList)

}

abstract class MetadataAccessors {

  protected def assign(key: String): String

  // ~~~ identifiers
  def getId : String = assign("delving_chID")
  def getOrgId : String = if(getId != null && getId.split("_").length == 3) getId.split("_")(0) else ""
  def getSpec : String = if(getId != null && getId.split("_").length == 3) getId.split("_")(1) else ""
  def getRecordId : String = if(getId != null && getId.split("_").length == 3) getId.split("_")(2) else ""
  def getDelvingId : String = assign("delving_pmhId")


  // ~~~ well-known, always provided, meta-data fields
  def getTitle : String = assign("dc_title")
  def getDescription: String = assign("dc_description")
  def getThumbnail : String = assign("europeana_object")
  def getCreator : String = assign("dc_creator")
  def getYear : String = assign("europeana_year")
  def getProvider : String = assign("europeana_provider")
  def getDataProvider : String = assign("europeana_dataProvider")
  def getLanguage : String = assign("europeana_language")

}
