package org.dbpedia.extraction.nif

import org.dbpedia.extraction.config.provenance.DBpediaDatasets
import org.dbpedia.extraction.mappings.{RecordEntry, RecordSeverity}
import org.dbpedia.extraction.ontology.{Ontology, RdfNamespace}
import org.dbpedia.extraction.transform.{Quad, QuadBuilder}
import org.dbpedia.extraction.util.{Config, Language}
import org.dbpedia.extraction.wikiparser.{Namespace, WikiPage}
import org.dbpedia.extraction.wikiparser.impl.wikipedia.Namespaces
import org.jsoup.nodes.{Document, Element, Node}
import org.jsoup.select.Elements

import scala.collection.convert.decorateAsScala._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.reflectiveCalls

/**
  * Created by Chile on 1/19/2017.
  */
class WikipediaNifExtractor(
     context : {
       def ontology : Ontology
       def language : Language
       def configFile : Config
     },
     wikiPage: WikiPage
   ) extends HtmlNifExtractor(wikiPage.uri + "?dbpv=" + context.configFile.dbPediaVersion + "&nif=context", context.language.isoCode, context.configFile) {


  /**
    * DBpedia relevant properties
    */
  // lazy so testing does not need ontology
  protected lazy val shortProperty = context.ontology.properties(context.configFile.abstractParameters.shortAbstractsProperty)

  // lazy so testing does not need ontology
  protected lazy val longProperty = context.ontology.properties(context.configFile.abstractParameters.longAbstractsProperty)

  protected val dbpediaVersion = context.configFile.dbPediaVersion
  protected lazy val longQuad = QuadBuilder(context.language, DBpediaDatasets.LongAbstracts, longProperty, null) _
  protected lazy val shortQuad = QuadBuilder(context.language, DBpediaDatasets.ShortAbstracts, shortProperty, null) _
  protected val recordAbstracts = !context.configFile.nifParameters.isTestRun  //not! will create dbpedia short and long abstracts
  protected val shortAbstractLength = context.configFile.abstractParameters.shortAbstractMinLength
  override protected val templateString = Namespaces.names(context.language).get(Namespace.Template.code) match {
    case Some(x) => x
    case None => "Template"
  }

  def extractNif(html: String)(exceptionHandle: RecordEntry[WikiPage] => Unit): Seq[Quad] = {
    super.extractNif(wikiPage.sourceIri, wikiPage.uri, html){ (_1:String, _2:RecordSeverity.Value, _3:Throwable) =>
      new RecordEntry[WikiPage](wikiPage, _2, context.language, _1, _3)
    }
  }

  /**
    * Each extracted section can be further enriched by this function, by providing additional quads
    *
    * @param extractionResults - the extraction results for a particular section
    * @return
    */
  override def extendSectionTriples(extractionResults: ExtractedSection, graphIri: String, subjectIri: String): Seq[Quad] = {
    //this is only dbpedia relevant: for singling out long and short abstracts
    if (extractionResults.section.id == "abstract" && recordAbstracts) {
      //not!
      List(longQuad(subjectIri, this.calculateText(extractionResults.paragraphs)._1, graphIri), shortQuad(subjectIri, getShortAbstract(extractionResults.paragraphs), graphIri))
    }
    else
      List()
  }

  /**
    * For each page additional triples can be added with this function
    *
    * @param quads - the current collection of quads
    * @return
    */
  override def extendContextTriples(quads: Seq[Quad], graphIri: String, subjectIri: String): Seq[Quad] = {
    List(nifContext(wikiPage.uri + "?dbpv=" + context.configFile.dbPediaVersion + "&nif=context", RdfNamespace.NIF.append("predLang"), "http://lexvo.org/id/iso639-3/" + this.context.language.iso639_3, graphIri, null))
  }

  private def getShortAbstract(paragraphs: List[Paragraph]): String = {
    var shortAbstract = ""
    for (p <- paragraphs) {
      if (shortAbstract.length <= shortAbstractLength || shortAbstract.length + p.getText.length < shortAbstractLength * 3) //create short Abstract between [shortAbstractLength, shortAbstractLength*3]
        shortAbstract += p.getText
    }
    if (shortAbstract.length > shortAbstractLength * 4) //only cut abstract if the first paragraph is exceedingly long
      shortAbstract = shortAbstract.substring(0, shortAbstractLength * 4)
    shortAbstract
  }


  /**
    * subtracts the relevant text
    * @param html
    * @return
    */
  override def getRelevantParagraphs (html: String): mutable.ListBuffer[PageSection] = {

    val tocMap = new mutable.ListBuffer[PageSection]()

    val doc: Document = getJsoupDoc(html)
    var nodes = doc.select("body").first.childNodes.asScala
    val currentSection = new ListBuffer[Int]()                  //keeps track of section number
    currentSection.append(0)                                    //initialize on abstract section

    def getParagraphText : Option[PageSection] ={
      //look for the next <h> tag
      nodes = nodes.dropWhile(node => !node.nodeName().matches("h\\d"))
      val title = nodes.headOption
      nodes = nodes.drop(1)
      title match{
        case Some(t) if isWikiNextTitle(t) && !isWikiPageEnd(t)  => {
          //calculate the section number by looking at the <h2> to <h4> tags
          val depth = Integer.parseInt(t.asInstanceOf[org.jsoup.nodes.Element].tagName().substring(1))-1
          if(currentSection.size < depth) //first subsection
            currentSection.append(1)
          else {
            //delete last entries depending on the depth difference to the last section
            val del = currentSection.size - depth +1
            val zw = currentSection(currentSection.size - del)
            currentSection.remove(currentSection.size - del, del)
            //if its just another section of the same level -> add one
            if(currentSection.size == depth-1)
              currentSection.append(zw+1)
          }

          val section = new PageSection(
            //previous section (if on same depth level
            prev = currentSection.last match{
              case x:Int if x > 1 => tocMap.lastOption
              case _ => None
            },
            //super section
            top = tocMap.find(x => currentSection.size > 1 && x.ref == currentSection.slice(0, currentSection.size-1).map(n => "." + n.toString).foldRight("")(_+_).substring(1)),
            next = None,
            sub = None,
            id = t.childNode(0).attr("id"),
            title = t.childNode(0).asInstanceOf[Element].text(),
            //merge section numbers separated by a dot
            ref = currentSection.map(n => "." + n.toString).foldRight("")(_+_).substring(1),
            tableCount = 0,
            equationCount = 0,
            //take all following tags until you hit another title or end of content
            content = Seq(t) ++ nodes.takeWhile(node => !isWikiNextTitle(node) && !isWikiPageEnd(node))
          )
          section.top match{
            case Some(s) => s.sub = Option(section)
            case None => None
          }
          section.prev match{
            case Some(s) => s.next = Option(section)
            case None => None
          }
          Some(section)
        }
        case None => None
        case _ => None
      }
    }

    nodes = nodes.dropWhile(node => node.nodeName() != "p")    //move cursor to infobox / abstract
    val ab = nodes.takeWhile(node => !isWikiNextTitle(node) && !isWikiToc(node) && !isWikiPageEnd(node))

    tocMap.append(new PageSection(                     //save abstract (abstract = section 0)
      prev = None,
      top = None,
      next = None,
      sub = None,
      id = "abstract",
      title = "abstract",
      ref = currentSection.map(n => "." + n.toString).foldRight("")(_+_).substring(1),
      tableCount=0,
      equationCount = 0,
      content = ab
    ))

    var res: Option[PageSection] = getParagraphText
    while(res.nonEmpty){
      res match{
        case Some(ps) => tocMap.append(ps)
        case None =>
      }
      res = getParagraphText
    }
    tocMap
  }

  private def isWikiPageEnd(node: Node): Boolean ={
    cssSelectorTest(node, cssSelectorConfigMap.findPageEnd)
  }

  private def isWikiToc(node: Node): Boolean ={
    cssSelectorTest(node, cssSelectorConfigMap.findToc)
  }

  private def isWikiNextTitle(node: Node): Boolean ={
    cssSelectorTest(node, cssSelectorConfigMap.nextTitle)
  }

  private def cssSelectorTest(node: Node, queries: Seq[String]): Boolean ={
    val doc = Document.createShell("").appendChild(node)
    val test: Elements = new Elements()
    for( query: String <- queries )
      test.addAll(doc.select(query))
    test.size() > 0
  }
}
