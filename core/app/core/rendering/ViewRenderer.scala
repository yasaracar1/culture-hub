/*
 * Copyright 2012 Delving B.V.
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

package core.rendering

import collection.mutable.{HashMap, ArrayBuffer}
import collection.mutable.Stack
import models.GrantType
import org.w3c.dom.{Node => WNode}
import play.libs.XPath
import collection.JavaConverters._
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream
import play.api.i18n.{Messages, Lang}
import org.apache.commons.lang.StringEscapeUtils
import xml.{NodeSeq, Node, XML}
import play.api.{Play, Logger}
import play.api.Play.current
import scala.Predef._

/**
 * View Rendering mechanism. Reads a ViewDefinition from a given record definition, and applies it onto the input data (a node tree).
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

object ViewRenderer {

  def fromDefinition(schema: String, viewName: String) = {
    val definition = getViewDefinition(schema, viewName)
    if(definition.isDefined) {
      Some(new ViewRenderer(schema, viewName))
    } else {
      None
    }
  }

  def getViewDefinition(schema: String, viewName: String): Option[Node] = {
    val definitionResource = Play.application.resource("/%s-view-definition.xml".format(schema))
    if(!definitionResource.isDefined) {
      None
    } else {
      val xml = XML.load(definitionResource.get)
      (xml \ "view").filter(v => (v \ "@name").text == viewName).headOption
    }
  }

}

class ViewRenderer(schema: String, viewName: String) {
  
  val log = Logger("CultureHub")

  val viewDef: Option[Node] = ViewRenderer.getViewDefinition(schema, viewName)

  val dbFactory = DocumentBuilderFactory.newInstance
  dbFactory.setNamespaceAware(true)
  val dBuilder = dbFactory.newDocumentBuilder

  def renderRecord(record: String, userGrantTypes: List[GrantType], namespaces: Map[String, String], lang: Lang, parameters: Map[String, String] = Map.empty): RenderedView = {
    viewDef match {
      case Some(viewDefinition) =>
        renderRecordWithView(schema, viewName, viewDefinition, record, userGrantTypes, namespaces, lang, parameters)
      case None => throw new RuntimeException("Could not find view definition '%s' for schema '%s'".format(viewName, schema))
    }
  }

  def renderRecordWithView(prefix: String, viewName: String, viewDefinition: Node, rawRecord: String, userGrantTypes: List[GrantType], namespaces: Map[String, String], lang: Lang, parameters: Map[String, String]): RenderedView = {

    val record = dBuilder.parse(new ByteArrayInputStream(rawRecord.getBytes("utf-8")))

    val result = RenderNode("root", None, true)
    val treeStack = Stack(result)
    val root = viewDefinition
    walk(root, record)

    implicit def richerNode(n: Node) = new {
        def attr(name: String) = {
          val sel = "@" + name
          (n \ sel).text
        }
      }

    def walk(viewDefinitionNode: Node, dataNode: WNode) {

      viewDefinitionNode.foreach {
        n =>
          log.debug("Node " + n)
          if (n.label != "#PCDATA") {

            // common attributes
            val label = n.attr("label")
            val role = n.attr("role")
            val path = n.attr("path")
            val queryLink = {
              val l = n.attr("queryLink")
              if (l.isEmpty) false else l.toBoolean
            }

            val roleList = role.split(",").map(_.trim).filterNot(_.isEmpty).toList

            n.label match {

              // ~~~ generic elements

              case "view" => enterAndAppendOne(n, dataNode, "root", true)

              case "elem" =>

                if(hasAccess(roleList)) {

                  val name = n.attr("name")
                  val prefix = n.attr("prefix")

                  val elemName = if(prefix.isEmpty) name else prefix + ":" + name

                  val isArray = n.attr("isArray") == "true"

                  val attrs = fetchNestedAttributes(n, dataNode)

                  val elemValue = if(!n.attr("expr").isEmpty) {
                    Some(XPath.selectText(n.attr("expr"), dataNode, namespaces.asJava))
                  } else if(!n.attr("value").isEmpty) {
                    Some(n.attr("value"))
                  } else {
                    None
                  }

                  val r = RenderNode(elemName, elemValue, isArray)
                  r.addAttrs(attrs)

                  if(elemValue.isDefined && n.child.isEmpty) {
                    appendNode(r)
                  } else if(!n.child.isEmpty) {
                    enterAndAppendNode(n, dataNode, r)
                  }

                }

              case "list" =>

                if(hasAccess(roleList)) {

                  val name = n.attr("name")
                  val prefix = n.attr("prefix")

                  // for html lists
                  val listType = n.attr("type")
                  val separator = n.attr("separator")
                  val label = n.attr("label")
                  val hClass = n.attr("class")

                  val listName = if(name.isEmpty && prefix.isEmpty) {
                    "list"
                  } else {
                    if(prefix.isEmpty) {
                      name
                    } else {
                      prefix + ":" + name
                    }
                  }

                  val attrs = fetchNestedAttributes(n, dataNode)

                  val list = RenderNode(listName, None, true)
                  list.addAttrs(attrs)

                  // for html lists
                  if(!label.isEmpty) list.addAttr('label -> label)
                  if(!separator.isEmpty) list.addAttr('separator -> separator)
                  if(!listType.isEmpty) list.addAttr('type -> listType)
                  if(!hClass.isEmpty) list.addAttr('class -> hClass)

                  treeStack.head += list
                  treeStack push list

                  XPath.selectNodes(path, dataNode, namespaces.asJava).asScala.foreach {
                    child =>
                      enterNode(n, child)
                  }
                  treeStack.pop()
                }

              case "attrs" => // this is handled by elem below




              // ~~~ legacy support

              case "auto-field" =>
                val current = XPath.selectNode(".", dataNode, namespaces.asJava)
                val renderNode = RenderNode(current.getNodeName, Some(current.getTextContent))
                appendNode(renderNode)

              case "auto-layout-field" =>
                val current = XPath.selectNode(".", dataNode, namespaces.asJava)
                val internationalizationKey = "metadata." + current.getNodeName.replaceAll(":", ".")

                val renderNode = RenderNode("field", None)
                renderNode += RenderNode("key", Some(Messages(internationalizationKey)))
                renderNode += RenderNode("value", Some(current.getNodeName.replaceAll(":", "_")))
                appendNode(renderNode)




              // ~~~ html helpers

              case "row" => enterAndAppendOne(n, dataNode, "row", true, 'class -> n.attr("class"))
              case "section" => enterAndAppendOne(n, dataNode, "section", true, 'id -> n.attr("id"), 'class -> n.attr("class"), 'type -> n.attr("type"), 'title -> n.attr("title"), 'label -> n.attr("label"))
              case "image" =>
                if (hasAccess(roleList)) {
                  val values = fetchPaths(dataNode, path.split(",").map(_.trim).toList, namespaces)
                  append("image", values.headOption, 'title -> n.attr("title"), 'type -> n.attr("type"), 'class -> n.attr("class")) { renderNode => }
                }
              case "field" =>
                if (hasAccess(roleList)) {
                  val values = fetchPaths(dataNode, path.split(",").map(_.trim).toList, namespaces)
                  append("field", values.headOption, 'label -> label, 'queryLink -> queryLink, 'type -> n.attr("type"), 'class -> n.attr("class")) { renderNode => }
                }
              case "enumeration" =>
                if (hasAccess(roleList)) {

                  appendSimple("enumeration", 'label -> label, 'queryLink -> queryLink, 'type -> n.attr("type"), 'separator -> n.attr("separator")) {
                    list =>

                      if (!n.child.isEmpty) {
                        throw new RuntimeException("An enumeration cannot have child elements!")
                      }

                      val values = fetchPaths(dataNode, path.split(",").map(_.trim).toList, namespaces)
                      values foreach {
                        v => list += RenderNode("_text_", Some(v))
                      }
                  }
                }

              case "link" =>
                val urlExpr = n.attribute("urlExpr").map(e => XPath.selectText(e.text, dataNode, namespaces.asJava))
                val urlValue = n.attr("urlValue")

                val enhancedUrlValue = """\$\{(.*)\}""".r.replaceAllIn(urlValue, m => parameters.get(m.group(1)).getOrElse {
                  log.warn("Could not find value for parameter %s while rendering view %s".format(m.group(1), viewName))
                  ""
                })

                val url = enhancedUrlValue + urlExpr.getOrElse("")

                val text = if(n.attribute("textExpr").isDefined) {
                  XPath.selectText(n.attr("textExpr"), dataNode, namespaces.asJava)
                } else if(n.attribute("textValue").isDefined) {
                  n.attr("textValue")
                } else {
                  ""
                }

                appendSimple("link", 'url -> url, 'text -> text, 'class -> n.attr("class")) { node => }

              case u@_ => throw new RuntimeException("Unknown element '%s'".format(u))


            }
          }

      }
    }

    def fetchNestedAttributes(n: Node, dataNode: WNode): Map[String, String] = {
      val attrDefinitions = n \ "attrs" \ "attr"

      (for (a: Node <- attrDefinitions) yield {
        val name = a.attr("name")
        if(name.isEmpty) {
          throw new RuntimeException("Attribute must have a name")
        }
        val prefix = a.attr("prefix")

        val attrName = if(prefix.isEmpty) name else prefix + ":" + name
        val attrValue = if(!a.attr("expr").isEmpty) {
          XPath.selectText(a.attr("expr"), dataNode, namespaces.asJava)
        } else if(!a.attr("value").isEmpty) {
          a.attr("value")
        } else {
          throw new RuntimeException("Attribute %s without value or expr provided".format(name))
        }

        (attrName -> attrValue)
      }).toMap
    }

    /** appends a new RenderNode to the result tree and walks one level deeper **/
    def enterAndAppendOne(viewDefinitionNode: Node, dataNode: WNode, nodeType: String, isArray: Boolean = false, attr: (Symbol, Any)*) {
      val newRenderNode = RenderNode(nodeType, None, isArray)
      attr foreach {
        newRenderNode addAttr _
      }
      enterAndAppendNode(viewDefinitionNode, dataNode, newRenderNode)
    }

    def enterAndAppendNode(viewDefinitionNode: Node, dataNode: WNode, renderNode: RenderNode) {
      log.debug("Entered " + viewDefinitionNode.label)
      treeStack.head += renderNode
      treeStack.push(renderNode)
      viewDefinitionNode.child foreach {
        n =>
          log.debug("Node " + n)
          walk(n, dataNode)
      }
      treeStack.pop()
    }

    /** enters a view definition node, but without appending a new node on the the current tree **/
    def enterNode(viewDefinitionNode: Node, dataNode: WNode) {
      log.debug("Entered " + viewDefinitionNode.label)
      viewDefinitionNode.child foreach {
        n =>
          log.debug("Node " + n)
          walk(n, dataNode)
      }

    }


    /** appends a new RenderNode without content to the result tree and performs an operation on it **/
    def appendSimple(nodeType: String, attr: (Symbol, Any)*)(block: RenderNode => Unit) {
      append(nodeType, None, attr : _ *)(block)
    }

    /** appends a new RenderNode to the result tree and performs an operation on it **/
    def append(nodeType: String, text: Option[String] = None, attr: (Symbol, Any)*)(block: RenderNode => Unit) {
      val newNode = RenderNode(nodeType, text)
      attr foreach {
        newNode addAttr _
      }
      treeStack.head += newNode
      treeStack.push(newNode)
      block(newNode)
      treeStack.pop()
    }

    /** simply appends a node to the current tree head **/
    def appendNode(node: RenderNode) {
      treeStack.head += node
    }

    def hasAccess(roles: List[String]) = {
      roles.isEmpty || (userGrantTypes.exists(gt => roles.contains(gt.key) && gt.origin == prefix) || userGrantTypes.exists(gt => gt.key == "own" && gt.origin == "System"))
    }

    NodeRenderedView(viewName, prefix, result.content.head)

  }


  def fetchPaths(dataNode: Object, paths: Seq[String], namespaces: Map[String, String]): Seq[String] = {
    (for (path <- paths) yield {
      XPath.selectText(path, dataNode, namespaces.asJava)
    })
  }

}

abstract class RenderedView {
  def toXml: NodeSeq
  def toXmlString: String
  def toJson: String
  def toViewTree: RenderNode
}

case class NodeRenderedView(viewName: String, formatName: String, viewTree: RenderNode) extends RenderedView {

  def toXml = XML.loadString(toXmlString)
  
  def toXmlString = RenderNode.toXMLString(viewTree)

  def toJson = RenderNode.toJson(viewTree)

  def toViewTree = viewTree
}

/**
 * A node used to hold the structure to be rendered
 */
case class RenderNode(nodeType: String, value: Option[String] = None, isArray: Boolean = false, isFlatArray: Boolean = false) {

  private val contentBuffer = new ArrayBuffer[RenderNode]
  private val attributes = new HashMap[String, Any]


  def content: List[RenderNode] = contentBuffer.toList

  def +=(node: RenderNode) {
    if(contentBuffer.exists(r =>
      (r.nodeType == node.nodeType)
        && !isArray
        && node.nodeType != "_text_")
    ) {
      throw new RuntimeException("In node %s: you cannot have child elements with the same name (%s) without explicitely declaring the container element to be an array!".format(nodeType, node.nodeType))
    }
    contentBuffer += node
  }


  def attr(key: String) = if(attributes.contains(key)) attributes(key) else ""

  def addAttr(key: String, value: AnyRef) = {
    if(!value.toString.trim.isEmpty) {
      attributes + (key -> value)
    }
  }

  def addAttr(element: (Symbol, Any)) {
    if(!element._2.toString.trim.isEmpty) {
      attributes += (element._1.name -> element._2)
    }
  }
  
  def addAttrs(attrs: Map[String, String]) {
    attributes ++= attrs.filterNot(_._2.isEmpty)
  }

  def attributesAsXmlString: String = attributes.map(a => a._1 + "=\"" + a._2.toString + "\"").mkString(" ")


  def text: String = value.getOrElse("")

  def isLeaf: Boolean = content.isEmpty

  override def toString = """[%s] - %s - %s""".format(nodeType, value, attributes.toString())
}

case object RenderNode {

  def visit(n: RenderNode) {
    val sb = new StringBuilder()
    visit(n, 0, sb)
    println(sb.toString())
  }

  def visit(n: RenderNode, level: Int = 0, sb: StringBuilder) {
    for(i <- 0 to level) sb.append(" ")
    sb.append(n.toString)
    sb.append("\n")
    for(c <- n.content) {
      visit(c, level + 1, sb)
    }
  }

  def toXMLString(n: RenderNode): String = {
    val sb = new StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
    visitXml(n, 0, "", sb)
    sb.toString()
  }
  
  def visitXml(n: RenderNode, level: Int = 0, indent: String = "", sb: StringBuilder) {
    
    if(n.nodeType == "root") {
      for(c <- n.content) {
        visitXml(c, level, "", sb)
      }
    } else {
      val indentation: String = (for(i <- 0 to level) yield " ").mkString + indent

      sb.append(indentation)

      if(n.isFlatArray) {
        n.content.foreach {
          c =>
            sb.append("<%s%s>".format(n.nodeType, if(n.attributesAsXmlString.isEmpty) "" else " " + n.attributesAsXmlString))
            sb.append(StringEscapeUtils.escapeXml(n.text))
            sb.append("</%s>".format(n.nodeType))
            sb.append("\n")
        }
      } else {
        sb.append("<%s%s>".format(n.nodeType, if(n.attributesAsXmlString.isEmpty) "" else " " + n.attributesAsXmlString))

        if(n.isLeaf) {
          sb.append(StringEscapeUtils.escapeXml(n.text))
          sb.append("</%s>".format(n.nodeType))
        } else {
          for(c <- n.content) {
            sb.append("\n")
            visitXml(c, level + 1, indentation, sb)
          }
          sb.append("\n")
          sb.append(indentation)
          sb.append("</%s>".format(n.nodeType))
        }
      }
    }
  }

  def toJson(n: RenderNode): String = {

    import extensions.JJson._
    
    val jsonTree = visitJson(n, HashMap.empty)
    
    generate(jsonTree)
  }

  def visitJson(n: RenderNode, json: HashMap[String, AnyRef]): HashMap[String, AnyRef] = {
    
    if(n.nodeType == "root") {
      for(c <- n.content) {
        visitJson(c, json)
      }
    } else {
      if(n.isArray) {
        val children = n.content.map {
          child => visitJson(child, HashMap.empty)
        }
        json.put(n.nodeType, children.toList)
      } else if(n.isFlatArray) {
        json.put(n.nodeType, n.content.map(_.nodeType).toList)
      } else if(!n.isLeaf) {
        val map = HashMap.empty[String, AnyRef]
        for(c <- n.content) {
          visitJson(c, map)
        }
        json.put(n.nodeType, map)
      } else {
        json.put(n.nodeType, n.text)
      }
    }
    
    json
    
  }

}