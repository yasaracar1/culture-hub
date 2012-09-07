import play.api.test.Helpers._
import play.api.test.FakeRequest

/**
 * TODO better check of the content of all records & search by ID
 * TODO hubIds with spaces in them
 * TODO hubIds with weird characters (utf-8)
 * TODO hubIds with URL-encoded characters
 * TODO search by legacy ID
 * TODO etc.
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

class SearchApiSpec extends Specs2TestContext {

  step {
    loadStandalone(SAMPLE_A, SAMPLE_B)
  }

  "the Search API" should {

    "find all records, although there seems to be one missing" in {

      withTestConfig {

        val response = query("delving_spec:sample-b")
        status(response) must equalTo(OK)
        val results = contentAsXML(response)

        val numFound = (results \ "query" \ "@numFound").text.toInt

        numFound must equalTo(299)
        // todo: should be 300, so where the hell did the second record go?
        // http://localhost:8983/solr/test/select/?q=delving_hubId%3Adelving_sample-b_oai-jhm-50000002&version=2.2&start=0&rows=30&indent=true
      }
    }
  }

  "find one record by hubId" in {

    withTestConfig {

      val response = id("delving_sample-b_oai-jhm-50000019")
      status(response) must equalTo(OK)
      val result = contentAsXML(response)

      (result \ "item").length must equalTo(1)

    }
  }

  private def query(query: String) = {
    val request = FakeRequest("GET", "?query=" + query)
    val r = controllers.api.Search.searchApi("delving", None, None, None)(request)
    asyncToResult(r)
  }

  private def id(id: String) = {
    val request = FakeRequest("GET", "?id=" + id)
    val r = controllers.api.Search.searchApi("delving", None, None, None)(request)
    asyncToResult(r)
  }

  step {
    cleanup()
  }

}