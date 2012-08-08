package models {

import core.Constants
import org.apache.solr.client.solrj.SolrQuery
import core.search.{SolrSortElement, SolrFacetElement}
import play.api.{Configuration, Play, Logger}
import Play.current
import collection.JavaConverters._

/**
 * Holds configuration that is used when a specific domain is accessed. It overrides a default configuration.
 *
 * @author Sjoerd Siebinga <sjoerd.siebinga@gmail.com>
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

case class DomainConfiguration(

  // ~~~ core
  name:                        String,
  orgId:                       String,
  domains:                     List[String] = List.empty,

  // ~~~ mail
  emailTarget:                 EmailTarget = EmailTarget(),

  // ~~~ data
  mongoDatabase:               String,
  baseXConfiguration:          BaseXConfiguration,
  solrBaseUrl:                 String,

  // ~~~ services
  commonsService:              CommonsServiceConfiguration,
  objectService:               ObjectServiceConfiguration,
  oaiPmhService:               OaiPmhServiceConfiguration,
  searchService:               SearchServiceConfiguration,
  directoryService:            DirectoryServiceConfiguration,

  plugins:                     Seq[String],

  // ~~~ schema
  schemas:                     Seq[String],
  crossWalks:                  Seq[String],

  // ~~~ user interface
  ui:                          UserInterfaceConfiguration,

  // ~~~ access control
  roles:                       Seq[Role],

  // ~~~ search
  apiWsKey:                    Boolean = false

) {

  def getFacets: List[SolrFacetElement] = {
    searchService.facets.split(",").filter(k => k.split(":").size > 0 && k.split(":").size < 4).map {
      entry => {
        val k = entry.split(":")
        k.length match {
          case 1 => SolrFacetElement(k.head, k.head)
          case 2 => SolrFacetElement(k(0), k(1))
          case 3 =>
            try {
              SolrFacetElement(k(0), k(1), k(2).toInt)
            } catch {
              case  _ : java.lang.NumberFormatException =>
                Logger("CultureHub").warn("Wrong value %s for facet display column number for theme %s".format(k(2), name))
                SolrFacetElement(k(0), k(1))
            }
        }
      }
    }.toList
  }

  def getSortFields: List[SolrSortElement] = {
    searchService.sortFields.split(",").filter(sf => sf.split(":").size > 0 && sf.split(":").size < 3).map {
      entry => {
        val k = entry.split(":")
        k.length match {
          case 1 => SolrSortElement(k.head)
          case 2 =>
            SolrSortElement(
              k(1),
              if (k(2).equalsIgnoreCase("desc")) SolrQuery.ORDER.desc else SolrQuery.ORDER.asc
            )
        }
      }
    }.toList
  }

}

case class UserInterfaceConfiguration(
  themeDir:                    String,
  defaultLanguage:             String = "en",
  siteName:                    Option[String],
  siteSlogan:                  Option[String]
)

case class CommonsServiceConfiguration(
  commonsHost:                 String,
  nodeName:                    String, // TODO deprecate this. We keep it for now to ease migration
  apiToken:                    String
)

case class ObjectServiceConfiguration(
  fileStoreDatabaseName:       String,
  imageCacheDatabaseName:      String,
  imageCacheEnabled:           Boolean,
  tilesOutputBaseDir:          String,
  tilesWorkingBaseDir:         String
)

case class OaiPmhServiceConfiguration(
  repositoryName:              String,
  adminEmail:                  String,
  earliestDateStamp:           String,
  repositoryIdentifier:        String,
  sampleIdentifier:            String,
  responseListSize:            Int
)

case class DirectoryServiceConfiguration(
  providerDirectoryUrl:        String
)

case class SearchServiceConfiguration(
  hiddenQueryFilter:           String,
  facets:                      String, // dc_creator:crea:Creator,dc_type
  sortFields:                  String, // dc_creator,dc_provider:desc
  moreLikeThis:                MoreLikeThis,
  searchIn:                    Map[String, String],
  apiWsKey:                    Boolean = false
)

/** See http://wiki.apache.org/solr/MoreLikeThis **/
case class MoreLikeThis(
  fieldList: Seq[String] = Seq(Constants.DESCRIPTION, "dc_creator_text"),
  minTermFrequency: Int = 1,
  minDocumentFrequency: Int = 2,
  minWordLength: Int = 0,
  maxWordLength: Int = 0,
  maxQueryTerms: Int = 25,
  maxNumToken: Int = 5000,
  boost: Boolean = false,
  queryFields: Seq[String] = Seq()
)

case class BaseXConfiguration(
  host: String,
  port: Int,
  eport: Int,
  user: String,
  password: String
)

case class EmailTarget(adminTo: String = "test-user@delving.eu",
                       exceptionTo: String = "test-user@delving.eu",
                       feedbackTo: String = "test-user@delving.eu",
                       registerTo: String = "test-user@delving.eu",
                       systemFrom: String = "noreply@delving.eu",
                       feedbackFrom: String = "noreply@delving.eu")

object DomainConfiguration {

  val log = Logger("CultureHub")

  // ~~~ keys
  val ORG_ID = "orgId"

  val SOLR_BASE_URL = "solr.baseUrl"
  val MONGO_DATABASE = "mongoDatabase"

  val COMMONS_HOST = "services.commons.host"
  val COMMONS_NODE_NAME = "services.commons.nodeName"
  val COMMONS_API_TOKEN = "services.commons.apiToken"

  val PROVIDER_DIRECTORY_URL = "services.directory.providerDirectoryUrl"

  val FILESTORE_DATABASE = "services.dos.fileStoreDatabase"
  val IMAGE_CACHE_DATABASE = "services.dos.imageCacheDatabase"
  val IMAGE_CACHE_ENABLED = "services.dos.imageCacheEnabled"
  val TILES_WORKING_DIR = "services.dos.tilesWorkingBaseDir"
  val TILES_OUTPUT_DIR = "services.dos.tilesOutputBaseDir"

  val PLUGINS = "plugins"

  val SCHEMAS = "schemas"
  val CROSSWALKS = "crossWalks"

  val SEARCH_HQF = "services.search.hiddenQueryFilter"
  val SEARCH_FACETS = "services.search.facets"
  val SEARCH_SORTFIELDS = "services.search.sortFields"
  val SEARCH_APIWSKEY = "services.search.apiWsKey"
  val SEARCH_MORELIKETHIS = "services.search.moreLikeThis"
  val SEARCH_SEARCHIN = "services.search.searchIn"

  val OAI_REPO_NAME = "services.pmh.repositoryName"
  val OAI_ADMIN_EMAIL = "services.pmh.adminEmail"
  val OAI_EARLIEST_TIMESTAMP = "services.pmh.earliestDateStamp"
  val OAI_REPO_IDENTIFIER = "services.pmh.repositoryIdentifier"
  val OAI_SAMPLE_IDENTIFIER = "services.pmh.sampleIdentifier"
  val OAI_RESPONSE_LIST_SIZE = "services.pmh.responseListSize"

  val BASEX_HOST = "basex.host"
  val BASEX_PORT = "basex.port"
  val BASEX_EPORT = "basex.eport"
  val BASEX_USER = "basex.user"
  val BASEX_PASSWORD = "basex.password"

  val EMAIL_ADMINTO = "emailTarget.adminTo"
  val EMAIL_EXCEPTIONTO = "emailTarget.exceptionTo"
  val EMAIL_FEEDBACKTO = "emailTarget.feedbackTo"
  val EMAIL_REGISTERTO = "emailTarget.registerTo"
  val EMAIL_SYSTEMFROM = "emailTarget.systemFrom"
  val EMAIL_FEEDBACKFROM = "emailTarget.feedbackFrom"


  val MANDATORY_OVERRIDABLE_KEYS = Seq(
    SOLR_BASE_URL,
    COMMONS_HOST, COMMONS_NODE_NAME,
    IMAGE_CACHE_DATABASE, FILESTORE_DATABASE, TILES_WORKING_DIR, TILES_OUTPUT_DIR,
    OAI_REPO_NAME, OAI_ADMIN_EMAIL, OAI_EARLIEST_TIMESTAMP, OAI_REPO_IDENTIFIER, OAI_SAMPLE_IDENTIFIER, OAI_RESPONSE_LIST_SIZE,
    SEARCH_FACETS, SEARCH_SORTFIELDS, SEARCH_APIWSKEY,
    BASEX_HOST, BASEX_PORT, BASEX_EPORT, BASEX_USER, BASEX_PASSWORD,
    PROVIDER_DIRECTORY_URL,
    EMAIL_ADMINTO, EMAIL_EXCEPTIONTO, EMAIL_FEEDBACKTO, EMAIL_REGISTERTO, EMAIL_SYSTEMFROM, EMAIL_FEEDBACKFROM
  )

  val MANDATORY_DOMAIN_KEYS = Seq(ORG_ID, MONGO_DATABASE, COMMONS_API_TOKEN, IMAGE_CACHE_ENABLED, SCHEMAS, CROSSWALKS, PLUGINS)


  /**
   * Computes all domain configurations based on the default Play configuration mechanism.
   */
  def getAll = {

    var missingKeys = new collection.mutable.HashMap[String, Seq[String]]

    val config = Play.configuration.getConfig("configurations").get
      val allDomainConfigurations: Seq[String] = config.keys.filterNot(_.indexOf(".") < 0).map(_.split("\\.").head).toList.distinct
      val configurations: Seq[DomainConfiguration] = allDomainConfigurations.flatMap {
        configurationKey => {
          val configuration = config.getConfig(configurationKey).get

          // check if all mandatory values are provided
          val missing = MANDATORY_OVERRIDABLE_KEYS.filter(
            key =>
              !configuration.keys.contains(key) &&
              !Play.configuration.keys.contains(key)
          ) ++ MANDATORY_DOMAIN_KEYS.filter(!configuration.keys.contains(_))

          // more checks
          val domains = configuration.underlying.getStringList("domains").asScala
          if (domains.isEmpty) {
            missingKeys += (
              configurationKey -> (
                missingKeys.get(configurationKey).map(list => list ++ Seq("domains")).getOrElse(Seq("domains"))
              )
            )
          }

          if (!missing.isEmpty) {
            missingKeys += (configurationKey -> missing)
            None
          } else {
            Some(
              DomainConfiguration(
                name = configurationKey,
                orgId = configuration.getString(ORG_ID).get,
                domains = configuration.underlying.getStringList("domains").asScala.toList,
                mongoDatabase = configuration.getString(MONGO_DATABASE).get,
                baseXConfiguration = BaseXConfiguration(
                  host = getString(configuration, BASEX_HOST),
                  port = getInt(configuration, BASEX_PORT),
                  eport = getInt(configuration, BASEX_EPORT),
                  user = getString(configuration, BASEX_USER),
                  password = getString(configuration, BASEX_PASSWORD)
                ),
                solrBaseUrl = getString(configuration, SOLR_BASE_URL),
                commonsService = CommonsServiceConfiguration(
                  commonsHost = getString(configuration, COMMONS_HOST),
                  nodeName = configuration.getString(COMMONS_NODE_NAME).get,
                  apiToken = configuration.getString(COMMONS_API_TOKEN).get
                ),
                oaiPmhService = OaiPmhServiceConfiguration(
                  repositoryName = getString(configuration, OAI_REPO_NAME),
                  adminEmail = getString(configuration, OAI_ADMIN_EMAIL),
                  earliestDateStamp = getString(configuration, OAI_EARLIEST_TIMESTAMP),
                  repositoryIdentifier = getString(configuration, OAI_REPO_IDENTIFIER),
                  sampleIdentifier = getString(configuration, OAI_SAMPLE_IDENTIFIER),
                  responseListSize = getInt(configuration, OAI_RESPONSE_LIST_SIZE)
                ),
                objectService = ObjectServiceConfiguration(
                  fileStoreDatabaseName = getString(configuration, FILESTORE_DATABASE),
                  imageCacheDatabaseName = getString(configuration, IMAGE_CACHE_DATABASE),
                  imageCacheEnabled = configuration.getBoolean(IMAGE_CACHE_ENABLED).getOrElse(false),
                  tilesWorkingBaseDir = getString(configuration, TILES_WORKING_DIR),
                  tilesOutputBaseDir = getString(configuration, TILES_OUTPUT_DIR)
                ),
                directoryService = DirectoryServiceConfiguration(
                  providerDirectoryUrl = configuration.getString(PROVIDER_DIRECTORY_URL).getOrElse("")
                ),
                searchService = SearchServiceConfiguration(
                  hiddenQueryFilter = getOptionalString(configuration, SEARCH_HQF).getOrElse(""),
                  facets = getString(configuration, SEARCH_FACETS),
                  sortFields = getString(configuration, SEARCH_SORTFIELDS),
                  apiWsKey = getBoolean(configuration, SEARCH_APIWSKEY),
                  moreLikeThis = {
                    val mlt = configuration.getConfig(SEARCH_MORELIKETHIS)
                    val default = MoreLikeThis()
                    if(mlt.isEmpty) {
                      default
                    } else {
                      MoreLikeThis(
                        fieldList = mlt.get.underlying.getStringList("fieldList").asScala,
                        minTermFrequency = mlt.get.getInt("minimumTermFrequency").getOrElse(default.minTermFrequency),
                        minDocumentFrequency = mlt.get.getInt("minimumDocumentFrequency").getOrElse(default.minDocumentFrequency),
                        minWordLength = mlt.get.getInt("minWordLength").getOrElse(default.minWordLength),
                        maxWordLength = mlt.get.getInt("maxWordLength").getOrElse(default.maxWordLength),
                        maxQueryTerms = mlt.get.getInt("maxQueryTerms").getOrElse(default.maxQueryTerms),
                        maxNumToken = mlt.get.getInt("maxNumToken").getOrElse(default.maxNumToken),
                        boost = mlt.get.getBoolean("boost").getOrElse(default.boost),
                        queryFields = mlt.get.underlying.getStringList("queryFields").asScala
                      )
                    }
                  },
                  searchIn = {
                    configuration.getConfig(SEARCH_SEARCHIN).map { searchIn =>
                      searchIn.keys.map { field =>
                        (field -> searchIn.getString(field).getOrElse(""))
                      }.toMap
                    }.getOrElse {
                      Map(
                        "dc_title" -> "metadata.dc.title",
                        "dc_creator" -> "metadata.dc.creator",
                        "dc_subject" -> "metadata.dc.subject"
                      )
                    }
                  }
                ),
                plugins = configuration.underlying.getStringList(PLUGINS).asScala.toSeq,
                schemas = configuration.underlying.getStringList(SCHEMAS).asScala.toList,
                crossWalks = configuration.underlying.getStringList(CROSSWALKS).asScala.toList,
                ui = UserInterfaceConfiguration(
                  themeDir = configuration.getString("ui.themeDir").getOrElse("default"),
                  defaultLanguage = configuration.getString("ui.defaultLanguage").getOrElse("en"),
                  siteName = configuration.getString("ui.siteName"),
                  siteSlogan = configuration.getString("ui.siteSlogan").orElse(Some("Delving CultureHub"))
                ),
                emailTarget = {
                  EmailTarget(
                    adminTo = getString(configuration, EMAIL_ADMINTO),
                    exceptionTo = getString(configuration, EMAIL_EXCEPTIONTO),
                    feedbackTo = getString(configuration, EMAIL_FEEDBACKTO),
                    registerTo = getString(configuration, EMAIL_REGISTERTO),
                    systemFrom = getString(configuration, EMAIL_SYSTEMFROM),
                    feedbackFrom = getString(configuration, EMAIL_FEEDBACKFROM)
                  )
                },
                roles = configuration.getConfig("roles").map {
                  roles => roles.keys.map {
                    key => {
                      val roleKey = key.split("\\.").head
                      // TODO parse all kind of languages
                      val roleDescriptions: Map[String, String] = roles.keys.filter(_.startsWith(roleKey + ".description.")).map {
                        descriptionKey => (descriptionKey.split("\\.").reverse.head -> roles.getString(descriptionKey).getOrElse(""))
                      }.toMap
                      Role(roleKey, roleDescriptions)
                    }
                  }.toSeq
                }.getOrElse(Seq.empty)
              )
            )
          }
        }
      }.toList

    // if there's anything wrong, we promptly refuse to start
    if (!missingKeys.isEmpty) {
      log.error(
        """Invalid configuration(s), hence we won't start:
          |%s
        """.stripMargin.format(
          missingKeys.map { config =>
            """
              |== %s:
              |Missing keys: %s
            """.stripMargin.format(
              config._1,
              config._2.mkString(", ")
            )
          }.mkString("\n")
        )
      )
      throw new RuntimeException("Invalid configuration. ¿Satan, is this you?")
    }

    val duplicateOrgIds = configurations.groupBy(_.orgId).filter(_._2.size > 1)
    if (!duplicateOrgIds.isEmpty) {
      log.error(
        "Found domain configurations that use the same orgId: " +
              duplicateOrgIds.map(t => t._1 + ": " + t._2.map(_.name).mkString(", ")).mkString(", "))
      throw new RuntimeException("Invalid configuration. Come back tomorrow.")
    }

    if(configurations.isEmpty) {
      log.error("No domain configuration found. You need to have at least one configured in order to start.")
      throw new RuntimeException("Invalid configuration. No can do.")
    }

    if (Play.isTest) {
      configurations.map { c =>
        c.copy(
          mongoDatabase = c.mongoDatabase + "-TEST",
          solrBaseUrl = "http://localhost:8983/solr/test",
          objectService = c.objectService.copy(
            fileStoreDatabaseName = c.objectService.fileStoreDatabaseName + "-TEST",
            imageCacheDatabaseName = c.objectService.imageCacheDatabaseName + "-TEST"
          )
        )
      }
    } else {
      configurations
    }
  }


  private def getString(configuration: Configuration, key: String): String =
    configuration.getString(key).getOrElse(Play.configuration.getString(key).get)

  private def getOptionalString(configuration: Configuration, key: String): Option[String] =
    configuration.getString(key).orElse(Play.configuration.getString(key))

  private def getInt(configuration: Configuration, key: String): Int =
    configuration.getInt(key).getOrElse(Play.configuration.getInt(key).get)

  private def getBoolean(configuration: Configuration, key: String): Boolean =
    configuration.getBoolean(key).getOrElse(Play.configuration.getBoolean(key).get)

}

}