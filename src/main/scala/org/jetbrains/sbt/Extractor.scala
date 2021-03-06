package org.jetbrains.sbt

import org.jetbrains.sbt.Android._
import org.jetbrains.sbt.Utilities._
import sbt.Keys._
import sbt.Load.BuildStructure
import sbt._

//import scala.language.reflectiveCalls

object Extractor extends ExtractorBase {
  private val ExportableConfigurations = Seq(Compile, Test, IntegrationTest)
  private val DependencyConfigurations = Seq(Compile, Test, Runtime, Provided, Optional)

  def extractStructure(state: State, download: Boolean, resolveClassifiers: Boolean, resolveSbtClassifiers: Boolean): StructureData = {
    val structure = Project.extract(state).structure

    val sbtVersion = Keys.sbtVersion.get(structure.data).get

    val scalaData = extractScala(state)

    val allProjectRefs = structure.allProjectRefs

    val projectsData = allProjectRefs.map(extractProject(state, structure, _, download && resolveSbtClassifiers))

    val repositoryData = download.option {
      val rawModulesData = allProjectRefs.flatMap(extractModules(state, structure, _, resolveClassifiers))
      val modulesData = rawModulesData.foldLeft(Seq.empty[ModuleData]) { (acc, data) =>
        acc.find(_.id == data.id) match {
          case Some(module) =>
            val newModule = ModuleData(module.id, module.binaries ++ data.binaries,
              module.docs ++ data.docs,
              module.sources ++ data.sources)
            acc.filterNot(_ == module) :+ newModule
          case None => acc :+ data
        }
      }
      RepositoryData(modulesData)
    }

    val localCachePath = Option(System.getProperty("sbt.ivy.home", System.getProperty("ivy.home")))

    StructureData(sbtVersion, scalaData, projectsData, repositoryData, localCachePath)
  }

  def extractScala(state: State): ScalaData = {
    val provider = state.configuration.provider.scalaProvider
    val libraryJar = provider.libraryJar
    val compilerJar = provider.compilerJar
    val extraJars = provider.jars.filter(_.getName.contains("reflect")).toSet - libraryJar - compilerJar
    ScalaData(provider.version, libraryJar, provider.compilerJar, extraJars.toSeq, Seq.empty)
  }

  def extractProject(state: State, structure: BuildStructure, projectRef: ProjectRef, download: Boolean): ProjectData = {
    val id = if (projectRef.project.startsWith("default-")) projectRef.build.getPath.replaceFirst(".*/([^/?]+).*", "$1")
    else projectRef.project

    val name = Keys.name.in(projectRef, Compile).get(structure.data).get

    val organization = Keys.organization.in(projectRef, Compile).get(structure.data).get

    val version = Keys.version.in(projectRef, Compile).get(structure.data).get

    val base = Keys.baseDirectory.in(projectRef, Compile).get(structure.data).get

    val target = Keys.target.in(projectRef, Compile).get(structure.data).get

    val configurations = ExportableConfigurations.flatMap(extractConfiguration(state, structure, projectRef, _))

    val java = {
      val home = Keys.javaHome.in(projectRef, Compile).get(structure.data).get

      val options: Seq[String] = Project.runTask(javacOptions.in(projectRef, Compile), state) match {
        case Some((_, Value(it))) => it
        case _ => Seq.empty
      }

      if (home.isDefined || options.nonEmpty) Some(JavaData(home, options)) else None
    }

    val scala: Option[ScalaData] = {
      val options: Seq[String] = Project.runTask(scalacOptions.in(projectRef, Compile), state) match {
        case Some((_, Value(it))) => it
        case _ => Seq.empty
      }

      Project.runTask(scalaInstance.in(projectRef, Compile), state) collect {
        case (_, Value(instance)) =>
          val extraJars = instance.extraJars.filter(_.getName.contains("reflect"))
          ScalaData(instance.version, instance.libraryJar, instance.compilerJar, extraJars, options)
      }
    }

    val build = {
      val unit = structure.units(projectRef.build)
      val (docs, sources) = if (download) extractSbtClassifiers(state, projectRef) else (Seq.empty, Seq.empty)
      BuildData(unit.imports, unit.classpath, docs, sources)
    }

    val dependencies = extractDependencies(state, structure, projectRef)
    val resolvers = extractResolvers(state, projectRef)

    val android = extractAndroid(structure, projectRef, state)

    val play2 = new Play2Extractor(structure, projectRef, state).extract()

    ProjectData(id, name, organization, version, base, target, build, configurations, java, scala, android,
      dependencies, resolvers, play2)
  }

  def extractConfiguration(state: State, structure: BuildStructure, projectRef: ProjectRef, configuration: Configuration): Option[ConfigurationData] = {
    Keys.classDirectory.in(projectRef, configuration).get(structure.data).map { output =>
      val sources = {
        val managed = Keys.managedSourceDirectories.in(projectRef, configuration).get(structure.data).get
        val unmanaged = Keys.unmanagedSourceDirectories.in(projectRef, configuration).get(structure.data).get
        val sourcesInBase = configuration == Compile && Keys.sourcesInBase.in(projectRef, configuration).get(structure.data).get
        val base = Keys.baseDirectory.in(projectRef, configuration).get(structure.data).get
        managed.map(DirectoryData(_, managed = true)) ++
          unmanaged.map(DirectoryData(_, managed = false)) ++
          (if (sourcesInBase) Seq(DirectoryData(base, managed = false)) else Seq.empty)
      }

      val resources = {
        val managed = Keys.managedResourceDirectories.in(projectRef, configuration).get(structure.data).get
        val unmanaged = Keys.unmanagedResourceDirectories.in(projectRef, configuration).get(structure.data).get
        managed.map(DirectoryData(_, managed = true)) ++ unmanaged.map(DirectoryData(_, managed = false))
      }

      ConfigurationData(configuration.name, sources, resources, output)
    }
  }

  def extractDependencies(state: State, structure: BuildStructure, projectRef: ProjectRef): DependencyData = {
    val projectDependencies =
      Keys.buildDependencies.in(projectRef).get(structure.data).map { dep =>
        dep.classpath.getOrElse(projectRef, Seq.empty).map(it => ProjectDependencyData(it.project.project, it.configuration))
      }.getOrElse(Seq.empty)

    val moduleDependencies = moduleDependenciesIn(state, projectRef)

    val jarDependencies = jarDependenciesIn(state, projectRef)

    DependencyData(projectDependencies, moduleDependencies, jarDependencies)
  }

  def moduleDependenciesIn(state: State, projectRef: ProjectRef): Seq[ModuleDependencyData] = {
    def modulesIn(configuration: Configuration): Seq[ModuleID] = {
      Project.runTask(externalDependencyClasspath.in(projectRef, configuration), state) match {
        case Some((_, Value(attrs))) =>
          for {
            attr <- attrs
            module <- attr.get(Keys.moduleID.key)
            artifact <- attr.get(Keys.artifact.key)
          } yield module.artifacts(artifact)
        case _ => Seq.empty
      }
    }

    val moduleToConfigurations = DependencyConfigurations
      .flatMap(configuration => modulesIn(configuration).map(module => (module, configuration)))
      .groupBy(_._1)
      .mapValues(_.unzip._2)
      .toSeq

    moduleToConfigurations.map { case (moduleId, configurations) =>
      ModuleDependencyData(
        createModuleIdentifier(moduleId, moduleId.explicitArtifacts),
        mapConfigurations(configurations))
    }.foldLeft(Seq.empty[ModuleDependencyData]) { (acc, moduleData) =>
      acc.find(_.id == moduleData.id) match {
        case Some(foundModuleData) =>
          val newModuleData = ModuleDependencyData(moduleData.id,
            mapConfigurations(moduleData.configurations ++ foundModuleData.configurations))
          acc.filterNot(_ == foundModuleData) :+ newModuleData
        case None => acc :+ moduleData
      }
    }
  }

  private def createModuleIdentifier(moduleId: ModuleID, artifacts: Seq[Artifact]): ModuleIdentifier = {
    val fusingClassifiers = Seq("", Artifact.DocClassifier, Artifact.SourceClassifier)
    def fuseClassifier(artifact: Artifact): String = artifact.classifier match {
      case Some(c) if fusingClassifiers.contains(c) => fusingClassifiers.head
      case Some(c) => c
      case None => fusingClassifiers.head
    }

    val classifier = artifacts.map(fuseClassifier).find(!_.isEmpty).getOrElse(fusingClassifiers.head)
    ModuleIdentifier(moduleId.organization, moduleId.name, moduleId.revision, Artifact.DefaultType, classifier)
  }

  def jarDependenciesIn(state: State, projectRef: ProjectRef): Seq[JarDependencyData] = {
    def jarsIn(configuration: Configuration): Seq[File] = {
      val classpath: Option[Classpath] = Project.runTask(unmanagedJars.in(projectRef, configuration), state) collect {
        case (_, Value(it)) => it
      }
      classpath.map(_.map(_.data)).getOrElse(Seq.empty)
    }

    val jarToConfigurations = DependencyConfigurations
      .flatMap(configuration => jarsIn(configuration).map(file => (file, configuration)))
      .groupBy(_._1)
      .mapValues(_.unzip._2)
      .toSeq

    jarToConfigurations.map { case (file, configurations) =>
      JarDependencyData(file, mapConfigurations(configurations))
    }
  }

  // We have to perform this configurations mapping because we're using externalDependencyClasspath
  // rather than libraryDependencies (to acquire transitive dependencies),  so we detect
  // module presence (in external classpath) instead of explicitly declared configurations.
  def mapConfigurations(configurations: Seq[Configuration]): Seq[Configuration] = {
    val cs = configurations.toSet

    if (cs == Set(Compile, Test, Runtime)) {
      Seq.empty
    } else if (cs == Set(Compile, Test)) {
      Seq(Provided)
    } else {
      configurations
    }
  }

  private class MyModuleReport(val module: ModuleID, val artifacts: Seq[(Artifact, File)]) {
    def this(report: ModuleReport) {
      this(report.module, report.artifacts)
    }
  }

  def extractModules(state: State, structure: BuildStructure, projectRef: ProjectRef, resolveClassifiers: Boolean): Seq[ModuleData] = {
    def run[T](task: ScopedKey[Task[T]]): T = {
      Project.runTask(task, state) collect {
        case (_, Value(it)) => it
      } getOrElse sys.error("Couldn't run: " + task)
    }

    def getModuleReports(task: TaskKey[UpdateReport]): Seq[MyModuleReport] = {
      val updateReport: UpdateReport = run(task in projectRef)
      val configurationReports = {
        val relevantConfigurationNames = DependencyConfigurations.map(_.name).toSet
        updateReport.configurations.filter(report => relevantConfigurationNames.contains(report.configuration))
      }

      configurationReports.flatMap{ r => r.modules.map(new MyModuleReport(_)) }.filter(_.artifacts.nonEmpty)
    }

    val binaryReports = getModuleReports(update)
    lazy val reportsWithDocs = {
      def onlySourcesAndDocs(artifacts: Seq[(Artifact, File)]): Seq[(Artifact, File)] =
        artifacts.collect { case (a, f) if a.`type` == Artifact.DocType || a.`type` == Artifact.SourceType => (a, f) }

      val docAndSrcReports = getModuleReports(updateClassifiers)
      binaryReports.map { report =>
        val matchingDocs = docAndSrcReports.filter(_.module == report.module)
        val docsArtifacts = matchingDocs.flatMap { r => onlySourcesAndDocs(r.artifacts) }
        new MyModuleReport(report.module, report.artifacts ++ docsArtifacts)
      }
    }

    val classpathTypes = Keys.classpathTypes.in(projectRef).get(structure.data).get
    merge(if (resolveClassifiers) reportsWithDocs else binaryReports,
          classpathTypes, Set(Artifact.DocType), Set(Artifact.SourceType))
  }

  private def merge(moduleReports: Seq[MyModuleReport], classpathTypes: Set[String], docTypes: Set[String], srcTypes: Set[String]): Seq[ModuleData] = {
    val moduleReportsGrouped = moduleReports.groupBy{ rep => rep.module.artifacts(rep.artifacts.map(_._1):_*) }.toSeq
    moduleReportsGrouped.map { case (module, reports) =>
      val allArtifacts = reports.flatMap(_.artifacts)
      def artifacts(kinds: Set[String]) = allArtifacts.collect { case (a, f) if kinds contains a.`type` => f }.toSet

      val id = createModuleIdentifier(module, allArtifacts.map(_._1))
      ModuleData(id, artifacts(classpathTypes), artifacts(docTypes), artifacts(srcTypes))
    }
  }

  def extractSbtClassifiers(state: State, projectRef: ProjectRef): (Seq[File], Seq[File]) = {
    val updateReport: UpdateReport = Project.runTask(updateSbtClassifiers.in(projectRef), state) collect {
      case (_, Value(it)) => it
    } getOrElse {
      throw new RuntimeException()
    }

    val allArtifacts = updateReport.configurations.flatMap(_.modules.flatMap(_.artifacts))

    def artifacts(kind: String) = allArtifacts.filter(_._1.`type` == kind).map(_._2).distinct

    (artifacts(Artifact.DocType), artifacts(Artifact.SourceType))
  }

  def extractResolvers(state: State, projectRef: ProjectRef): Set[ResolverData] =
    Project.runTask(fullResolvers.in(projectRef, configuration), state) match {
      case Some((_, Value(resolvers))) => resolvers.map({
        case MavenRepository(name, root) => Some(ResolverData(name, root))
        case _ => None
      }).flatten.toSet
      case _ => Set.empty
    }
}
