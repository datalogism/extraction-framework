package org.dbpedia.extraction.dump.extract

import org.dbpedia.extraction.config.provenance.{DBpediaDatasets, Dataset}
import org.dbpedia.extraction.config.{Config2, ConfigUtils}
import org.dbpedia.extraction.destinations._
import org.dbpedia.extraction.mappings._
import org.dbpedia.extraction.ontology.Ontology
import org.dbpedia.extraction.ontology.io.OntologyReader
import org.dbpedia.extraction.sources.{Source2, Source, WikiSource, XMLSource,XMLSource2}
import org.dbpedia.extraction.util.RichFile.wrapFile
import org.dbpedia.extraction.util._
import org.dbpedia.extraction.wikiparser._

import java.io._
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import scala.collection.convert.decorateAsScala._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.reflect._

/**
 * Loads the dump extraction configuration.
 *
 * TODO: clean up. The relations between the objects, classes and methods have become a bit chaotic.
 * There is no clean separation of concerns.
 *
 * TODO: get rid of all config file parsers, use Spring
 */
class ConfigLoader2(config: Config2)
{
  //private val logger = Logger.getLogger(classOf[ConfigLoader2].getName)

  private val extractionJobs = new ConcurrentHashMap[Language, ExtractionJob2]().asScala

  private val sparkExtractionJobs = new ConcurrentHashMap[Language, SparkExtractionJob2]().asScala

  private val extractionRecorder = new mutable.HashMap[ClassTag[_], mutable.HashMap[Language, ExtractionRecorder2[_]]]()

  //these lists are used when the ImageExtractor is amongst the selected Extractors
  private val nonFreeImages = new ConcurrentHashMap[Language, Seq[String]]().asScala
  private val freeImages = new ConcurrentHashMap[Language, Seq[String]]().asScala

  private val extractionMonitor = new ExtractionMonitor2()

  def getExtractionRecorder[T: ClassTag](lang: Language, dataset : Dataset = null): org.dbpedia.extraction.util.ExtractionRecorder2[T] = {
    extractionRecorder.get(classTag[T]) match{
      case Some(s) => s.get(lang) match {
        case None =>
          val datasetsParam = if (dataset == null) ListBuffer[Dataset]() else ListBuffer(dataset)
          s(lang) = config.getDefaultExtractionRecorder[T](lang, 2000, null, null, datasetsParam, extractionMonitor)
          s(lang).asInstanceOf[ExtractionRecorder2[T]]
        case Some(er) =>
          if(dataset != null) if(!er.datasets.contains(dataset)) er.datasets += dataset
          er.asInstanceOf[ExtractionRecorder2[T]]
      }
      case None =>
        extractionRecorder(classTag[T]) = new mutable.HashMap[Language, ExtractionRecorder2[_]]()
        getExtractionRecorder[T](lang, dataset)
    }
  }

  /**
   * Creates ab extraction job for a specific language.
   */
  val extractionJobWorker: Workers[(Language, Seq[Class[_ <: Extractor[_]]])] = SimpleWorkers(config.parallelProcesses, config.parallelProcesses) { input: (Language,  Seq[Class[_ <: Extractor[_]]]) =>

    val finder = new Finder[File](config.dumpDir, input._1, config.wikiName)

    val date = latestDate(finder)

    //Extraction Context
    val context = new DumpExtractionContext2
    {
      def ontology: Ontology = _ontology

      def commonsSource: Source2 = _commonsSource

      def language: Language = input._1

      def recorder[T: ClassTag]: ExtractionRecorder2[T] = getExtractionRecorder[T](input._1)




      def articlesSource: Source2 = getArticlesSource(language, finder)

      private val _redirects =
      {
        finder.file(date, "template-redirects.obj") match{
          case Some(cache) => Redirects2.load(articlesSource, cache, language)
          case None => new Redirects2(Map())
        }

      }

      def redirects : Redirects2 = _redirects

      private val _disambiguations =
      {
        try {
          Disambiguations.load(reader(finder.file(date, config.disambiguations).get), finder.file(date, "disambiguations-ids.obj").get, language)
        } catch {
          case ex: Exception =>
          //  logger.info("Could not load disambiguations - error: " + ex.getMessage)
            null
        }
      }

      def disambiguations : Disambiguations =
        if (_disambiguations != null)
          _disambiguations
        else
          new Disambiguations(Set[Long]())

      def configFile: Config2 = config

      def freeImages : Seq[String] = ConfigLoader2.this.freeImages.get(language) match{
        case Some(s) => s
        case None => Seq()
      }

      def nonFreeImages : Seq[String] = ConfigLoader2.this.nonFreeImages.get(language) match{
        case Some(s) => s ++ ConfigLoader2.this.nonFreeImages(Language.Commons)                //always add commons to the list of non free images
        case None => Seq()
      }
    }

    //Extractors
    val extractor = CompositeParseExtractor2.load(input._2, context)

    System.out.println(">>  EXTRACTOR : " + extractor);
    val datasets = extractor.datasets

    val formatDestinations = new ArrayBuffer[Destination]()

    for ((suffix, format) <- config.formats) {
      val datasetDestinations = new mutable.HashMap[Dataset, Destination]()
      for (dataset <- datasets) {
        finder.file(date, dataset.encoded.replace('_', '-')+'.'+suffix) match{
          case Some(file)=> datasetDestinations(dataset) = new DeduplicatingDestination(new WriterDestination2(writer(file), format, getExtractionRecorder(context.language, dataset), dataset))
          case None =>
        }
      }
      formatDestinations += new DatasetDestination(datasetDestinations)
    }

    val destination = new MarkerDestination(
      new CompositeDestination(formatDestinations: _*),
      finder.file(date, Extraction.Complete).get,
      false
    )

    val extractionJobNS = if(context.language == Language.Commons)
      ExtractorUtils.commonsNamespacesContainingMetadata
    else config.namespaces

    val extractionJob = new ExtractionJob2(
      extractor,
      context.articlesSource,
      extractionJobNS,
      destination,
      context.language,
      config.retryFailedPages,
      getExtractionRecorder(context.language)
    )

    extractionJobs.put(context.language, extractionJob)
  }

  /**
   * Creates ab extraction job for a specific language.
   */
  def buildSparkExtractionJob(lang: Language, extractors : Seq[Class[_ <: Extractor[_]]]) : Option[SparkExtractionJob2] = {
    val finder = new Finder[File](config.dumpDir, lang, config.wikiName)

    val date = latestDate(finder)

    def articlesSource: Source2 = getArticlesSource(lang, finder)

    //Extraction Context
    val context = new SparkExtractionContext2 {

      def ontology: Ontology = _ontology
      def language: Language = lang

      private val _redirects =
      {
        finder.file(date, "template-redirects.obj") match{
          case Some(cache) => Redirects2.load(articlesSource, cache, language)
          case None => new Redirects2(Map())
        }

      }

      def redirects : Redirects2 = _redirects

      def recorder[T: ClassTag]: ExtractionRecorder2[T] = getExtractionRecorder[T](lang)

      private lazy val _mappingPageSource =
      {
        val namespace = Namespace.mappings(language)

        if (config.mappingsDir != null && config.mappingsDir.isDirectory)
        {
          val file = new File(config.mappingsDir, namespace.name(Language.Mappings).replace(' ','_')+".xml")
          XMLSource2.fromFile(file, Language.Mappings)
        }
        else
        {
          val namespaces = Set(namespace)
          val url = new URL(Language.Mappings.apiUri)
          WikiSource.fromNamespaces(namespaces,url,Language.Mappings)
        }
      }



      private val _disambiguations =
      {
        try {
          Disambiguations.load(reader(finder.file(date, config.disambiguations).get), finder.file(date, "disambiguations-ids.obj").get, language)
        } catch {
          case ex: Exception =>
            //logger.info("Could not load disambiguations - error: " + ex.getMessage)
            null
        }
      }

      def disambiguations : Disambiguations =
        if (_disambiguations != null)
          _disambiguations
        else
          new Disambiguations(Set[Long]())

    }

    // TODO: Find a better way to do this (?)
    val datasets = CompositeParseExtractor.load(extractors, context).datasets

    val formatDestinations = new ArrayBuffer[Destination]()

    for ((suffix, format) <- config.formats) {
      val datasetDestinations = new mutable.HashMap[Dataset, Destination]()
      for (dataset <- datasets) {
        finder.file(date, dataset.encoded.replace('_', '-')+'.'+suffix) match{
          case Some(file)=> datasetDestinations(dataset) = new DeduplicatingDestination(new WriterDestination2(writer(file), format, getExtractionRecorder(lang, dataset), dataset))
          case None =>
        }
      }
      formatDestinations += new DatasetDestination(datasetDestinations)
    }

    val destination = new MarkerDestination(
      new CompositeDestination(formatDestinations: _*),
      finder.file(date, Extraction.Complete).get,
      false
    )

    val extractionJobNS = if(lang == Language.Commons)
      ExtractorUtils.commonsNamespacesContainingMetadata
    else config.namespaces

    val sparkExtractionJob = new SparkExtractionJob2(
      extractors,
      context,
      extractionJobNS,
      destination,
      lang,
      config.retryFailedPages,
      getExtractionRecorder(lang)
    )

    sparkExtractionJobs.put(lang, sparkExtractionJob)
  }



  /**
   * Loads the configuration and creates extraction jobs for all configured languages.
   *
   * @return Non-strict Traversable over all configured extraction jobs, i.e., an extraction job will not be created until it is explicitly requested.
   */
  def getExtractionJobs: Traversable[ExtractionJob2] =
  {


    // Create a non-strict view of the extraction jobs
    // non-strict because we want to create the extraction job when it is needed, not earlier
    Workers.work[(Language, Seq[Class[_ <: Extractor[_]]])](extractionJobWorker, config.extractorClasses.toList)
    extractionJobs.values
  }

  def getSparkExtractionJobs: Traversable[SparkExtractionJob2] = {


    config.extractorClasses.toList.foreach(input => buildSparkExtractionJob(input._1, input._2))
    sparkExtractionJobs.values
  }
  private def writer(file: File): () => Writer = {
    () => IOUtils.writer(file)
  }

  private def reader(file: File): () => Reader = {
    () => IOUtils.reader(file)
  }

  private def readers(source: String, finder: Finder[File], date: String): List[() => Reader] = {
    files(source, finder, date).map(reader)
  }

  private def files(source: String, finder: Finder[File], date: String): List[File] = {

    val files = if (source.startsWith("@")) { // the articles source is a regex - we want to match multiple files
      finder.matchFiles(date, source.substring(1))
    } else List(finder.file(date, source)).collect{case Some(x) => x}

 //   logger.info(s"Source is $source - ${files.size} file(s) matched")

    files
  }

  //language-independent val
  private lazy val _ontology =
  {
    val ontologySource = if (config.ontologyFile != null && config.ontologyFile.isFile)
    {
      XMLSource.fromFile(config.ontologyFile, Language.Mappings)
    }
    else
    {
      val namespaces = Set(Namespace.OntologyClass, Namespace.OntologyProperty)
      val url = new URL(Language.Mappings.apiUri)
      val language = Language.Mappings
      WikiSource.fromNamespaces(namespaces, url, language)
    }
    new OntologyReader().read(ontologySource)
  }

  //language-independent val
  private lazy val _commonsSource =
  {
    val finder = new Finder[File](config.dumpDir, Language("commons"), config.wikiName)
    val date = latestDate(finder)
    XMLSource2.fromReaders(config.source.flatMap(x => readers(x, finder, date)), Language.Commons, _.namespace == Namespace.File)
  }

  private def getArticlesSource(language: Language, finder: Finder[File]) =
  {
    val articlesReaders = config.source.flatMap(x => readers(x, finder, latestDate(finder)))

    XMLSource2.fromReaders(articlesReaders, language,
      title => title.namespace == Namespace.Main || title.namespace == Namespace.File ||
        title.namespace == Namespace.Category || title.namespace == Namespace.Template ||
        title.namespace == Namespace.WikidataProperty || title.namespace == Namespace.WikidataLexeme || ExtractorUtils.titleContainsCommonsMetadata(title))
  }

  private def latestDate(finder: Finder[_]): String = {
    val isSourceRegex = config.source.startsWith("@")
    val source = if (isSourceRegex) config.source.head.substring(1) else config.source.head
    val fileName = if (config.requireComplete) Config2.Complete else source
    finder.dates(fileName, isSuffixRegex = isSourceRegex).last
  }
}
