package controllers

import scala.collection.JavaConversions._
import search.{PresentationQuery, BriefItemView, CHResponse, SolrQueryService}

/**
 *
 * @author Sjoerd Siebinga <sjoerd.siebinga@gmail.com>
 * @since 8/10/11 2:40 PM  
 */

object Search extends DelvingController {

  def index = {
  if(params.allSimple().keySet().filter(key => List("query", "id", "explain").contains(key)).size == 0) {
     params.put("query", "*:*")
   }
   // for now hardcode the facets in
    if (!params._contains("facet.field"))
      params.put("facet.field", Array("TYPE", "YEAR"))

    val chQuery = SolrQueryService.createCHQuery(request, theme, true)
    val response = CHResponse(params, theme, SolrQueryService.getSolrResponseFromServer(chQuery.solrQuery, true), chQuery)
    val briefItemView = BriefItemView(response)
    Template('briefDocs -> briefItemView.getBriefDocs, 'pagination -> briefItemView.getPagination, 'facets -> briefItemView.getFacetQueryLinks)
  }
}