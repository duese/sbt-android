package android

import java.io.File
import java.net.{HttpURLConnection, URL}

import com.android.SdkConstants
import com.android.repository.Revision
import com.android.repository.api._
import com.android.repository.impl.generated.generic.v1.GenericDetailsType
import com.android.repository.impl.generated.v1.{LocalPackage => _,_}
import com.android.repository.io.FileOp
import com.android.sdklib.repositoryv2.AndroidSdkHandler
import com.android.repository.api.{RemotePackage => RepoRemotePackage}
import com.android.sdklib.repositoryv2.generated.addon.v1.{AddonDetailsType, ExtraDetailsType, LibrariesType}
import com.android.sdklib.repositoryv2.generated.common.v1.{IdDisplayType, LibraryType}
import com.android.sdklib.repositoryv2.generated.repository.v1.{LayoutlibType, PlatformDetailsType}
import com.android.sdklib.repositoryv2.generated.sysimg.v1.SysImgDetailsType
import sbt.{IO, Logger, Using}

import collection.JavaConverters._
import concurrent.duration._
import scala.xml.{Elem, Node, XML}

object SdkInstaller extends TaskBase {
  implicit val packageOrder: Ordering[com.android.repository.api.RemotePackage] =
    new Ordering[com.android.repository.api.RemotePackage] {
      override def compare(x: com.android.repository.api.RemotePackage,
                           y: com.android.repository.api.RemotePackage) = y.compareTo(x)
    }
  val platformOrder: Ordering[com.android.repository.api.RemotePackage] =
    new Ordering[com.android.repository.api.RemotePackage] {
      override def compare(x: RepoRemotePackage, y: RepoRemotePackage) = (x.getTypeDetails,y.getTypeDetails) match {
        case (a: PlatformDetailsType, b: PlatformDetailsType) =>
          b.getApiLevel - a.getApiLevel
        case _ => y.compareTo(x)
      }
    }
  def installPackage(sdkHandler: AndroidSdkHandler,
                     prefix: String,
                     pkg: String,
                     name: String,
                     showProgress: Boolean,
                     slog: Logger): RepoRemotePackage =
    install(sdkHandler, name, prefix, showProgress, slog)(_.get(prefix + pkg))

  def autoInstallPackage(sdkHandler: AndroidSdkHandler,
                         prefix: String,
                         pkg: String,
                         name: String,
                         showProgress: Boolean,
                         slog: Logger): Option[RepoRemotePackage] =
    autoInstallPackage(sdkHandler, prefix, pkg, name, showProgress, slog, !_.contains(pkg))
  def autoInstallPackage(sdkHandler: AndroidSdkHandler,
                         prefix: String,
                         pkg: String,
                         name: String,
                         showProgress: Boolean,
                         slog: Logger,
                         pred: Map[String,LocalPackage] => Boolean): Option[RepoRemotePackage] = {
    val ind = SbtAndroidProgressIndicator(slog)
    val pkgs = retryWhileFailed("get local packages", slog)(
      sdkHandler.getSdkManager(ind).getPackages.getLocalPackages.asScala.toMap
    )
    if (pred(pkgs)) {
      slog.warn(s"$name not found, searching for package...")
      Option(installPackage(sdkHandler, prefix, pkg, name, showProgress, slog))
    } else {
      None
    }
  }
  def autoInstall(sdkHandler: AndroidSdkHandler,
                  name: String,
                  prefix: String,
                  showProgress: Boolean,
                  slog: Logger,
                  pred: Map[String,LocalPackage] => Boolean)(pkgfilter: Map[String,RepoRemotePackage] => Option[RepoRemotePackage]): Option[RepoRemotePackage] = {
    val ind = SbtAndroidProgressIndicator(slog)
    val pkgs = retryWhileFailed("get local packages", slog)(
      sdkHandler.getSdkManager(ind).getPackages.getLocalPackages.asScala.toMap
    )
    if (pred(pkgs)) {
      slog.warn(s"$name not found, searching for package...")
      Option(install(sdkHandler, prefix, name, showProgress, slog)(pkgfilter))
    } else {
      None
    }
  }

  def install(sdkHandler: AndroidSdkHandler,
              name: String,
              prefix: String,
              showProgress: Boolean,
              slog: Logger)(pkgfilter: Map[String,RepoRemotePackage] => Option[RepoRemotePackage]): RepoRemotePackage = synchronized {
    val downloader = SbtAndroidDownloader(sdkHandler.getFileOp)
    val repomanager = sdkHandler.getSdkManager(PrintingProgressIndicator(showProgress))
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(showProgress), downloader, null)
    val pkgs = repomanager.getPackages.getRemotePackages.asScala.toMap
    val remotepkg = pkgfilter(pkgs)
    remotepkg match {
      case None =>
        PluginFail(
          s"""No installable package found for $name
              |
              |Available packages:
              |${pkgs.keys.toList.filter(_.startsWith(prefix)).map(pkgs).sorted(platformOrder).map(
                 "  " + _.getPath.substring(prefix.length)).mkString("\n")}
           """.stripMargin)
      case Some(r) =>
        Option(r.getLicense).foreach { l =>
          val id = l.getId
          val license = l.getValue
          println(s"By continuing to use sbt-android, you accept the terms of '$id'")
          println(s"You may review the terms by running 'android-license $id'")
          val f = java.net.URLEncoder.encode(id, "utf-8")
          SdkLayout.sdkLicenses.mkdirs()
          IO.write(new File(SdkLayout.sdkLicenses, f), license)
        }
        val installed = repomanager.getPackages.getLocalPackages.asScala.get(r.getPath)
        if (installed.forall(_.getVersion != r.getVersion)) {
          slog.info(s"Installing package '${r.getDisplayName}' ...")
          val installer = AndroidSdkHandler.findBestInstaller(r)
          val ind = PrintingProgressIndicator(showProgress)
          val succ = installer.install(r, downloader, null, ind, repomanager, sdkHandler.getFileOp)
          if (!succ) PluginFail("SDK installation failed")
          if (ind.getFraction != 1.0)
            ind.setFraction(1.0) // workaround for installer stopping at 99%
          // force RepoManager to clear itself
          sdkHandler.getSdkManager(ind).loadSynchronously(0, ind, null, null)
        } else {
          slog.warn(s"'${r.getDisplayName}' already installed, skipping")
        }
        r
    }
  }

  def retryWhileFailed[A](err: String, log: Logger, delay: Int = 250)(f: => A): A = {
    Iterator.continually(util.Try(f)).dropWhile { t =>
      val failed = t.isFailure
      if (failed) {
        log.error(s"Failed to $err, retrying...")
        Thread.sleep(delay)
      }
      failed
    }.next.get
  }

  def sdkPath(slog: sbt.Logger, props: java.util.Properties): String = {
    val cached = SdkLayout.androidHomeCache
    val path = (Option(System getenv "ANDROID_HOME") orElse
      Option(props getProperty "sdk.dir")) flatMap { p =>
      val f = sbt.file(p + File.separator)
      if (f.exists && f.isDirectory) {
        cached.getParentFile.mkdirs()
        IO.writeLines(cached, p :: Nil)
        Some(p + File.separator)
      } else None
    } orElse SdkLayout.sdkFallback(cached) getOrElse {
      val home = SdkLayout.fallbackAndroidHome
      slog.info("ANDROID_HOME not set, using " + home.getCanonicalPath)
      home.mkdirs()
      home.getCanonicalPath
    }
    sys.props("com.android.tools.lint.bindir") =
      path + File.separator + SdkConstants.FD_TOOLS
    path
  }

  private def sdkpath(state: sbt.State): String =
    sdkPath(state.log, loadProperties(sbt.Project.extract(state).currentProject.base))

  private[this] lazy val sdkMemo = scalaz.Memo.immutableHashMapMemo[File, (Boolean, Logger) => AndroidSdkHandler] { f =>
    AndroidSdkHandler.setRemoteFallback(FallbackSdkLoader)
    val manager = AndroidSdkHandler.getInstance(f)

    (showProgress, slog) => manager.synchronized {
      SdkInstaller.autoInstallPackage(manager, "", "tools", "android sdk tools", showProgress, slog)
      SdkInstaller.autoInstallPackage(manager, "", "platform-tools", "android platform-tools", showProgress, slog)
      manager
    }
  }

  def sdkManager(path: File, showProgress: Boolean, slog: Logger): AndroidSdkHandler = synchronized {
    sdkMemo(path)(showProgress, slog)
  }

  def installSdkAction: (sbt.State, Option[String]) => sbt.State = (state,toInstall) => {
    val log = state.log
    val ind = SbtAndroidProgressIndicator(log)
    val sdkHandler = sdkManager(sbt.file(sdkpath(state)), true, log)
    val repomanager = sdkHandler.getSdkManager(ind)
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(), SbtAndroidDownloader(sdkHandler.getFileOp), null)
    val newpkgs = repomanager.getPackages.getNewPkgs.asScala.filterNot(_.obsolete).toList.sorted(platformOrder)
    toInstall match {
      case Some(p) =>
        install(sdkHandler, p, "", true, log)(_.get(p))
      case None =>
        val packages = newpkgs.map { p =>
            val path = p.getPath
            val name = p.getDisplayName
            s"  $path\n  - $name"
          }
        log.error("Available packages:\n" + packages.mkString("\n"))
        PluginFail("A package to install must be specified")
    }
    state
  }
  //noinspection MutatorLikeMethodIsParameterless
  def updateSdkAction: (sbt.State, Either[Option[String],String]) => sbt.State = (state,toUpdate) => {
    val log = state.log
    val ind = SbtAndroidProgressIndicator(log)
    val sdkHandler = sdkManager(sbt.file(sdkpath(state)), true, log)
    val repomanager = sdkHandler.getSdkManager(ind)
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(), SbtAndroidDownloader(sdkHandler.getFileOp), null)
    val updates = repomanager.getPackages.getUpdatedPkgs.asScala.collect {
      case u if u.hasRemote => u.getRemote }.toList.sorted(platformOrder)
    def updatesHelp(): Unit = {
      val packages = "  all\n  - apply all updates" ::
        updates.map { u =>
          val path = u.getPath
          val name = u.getDisplayName
          s"  $path\n  - $name"
        }
      if (packages.size == 1) {
        log.error("No updates available")
      } else {
        log.error("Available updates:\n" + packages.mkString("\n"))
      }
    }
    toUpdate.left.foreach {
      case Some(_) =>
        updates.foreach { u =>
          install(sdkHandler, u.getDisplayName, "", true, log)(_.get(u.getPath))
        }
      case None =>
        updatesHelp()
        PluginFail("A package or 'all' must be specified")
    }
    toUpdate.right.foreach { p =>
      updates.find(_.getPath == p) match {
        case Some(pkg) =>
          install(sdkHandler, pkg.getDisplayName, "", true, log)(_.get(pkg.getPath))
        case None =>
          updatesHelp()
          PluginFail(s"Update '$p' not found")
      }
    }
    state
  }
  //noinspection MutatorLikeMethodIsParameterless
  def updateCheckSdkTaskDef = sbt.Def.task {
    import concurrent.ExecutionContext.Implicits.global
    concurrent.Future {
      val sdkHandler = Keys.Internal.sdkManager.value
      val log = sbt.Keys.streams.value.log
      val ind = SbtAndroidProgressIndicator(log)
      val repomanager = sdkHandler.getSdkManager(ind)
      repomanager.loadSynchronously(1.day.toMillis, ind, SbtAndroidDownloader(sdkHandler.getFileOp), null)
      val updates = repomanager.getPackages.getUpdatedPkgs.asScala.filter(_.hasRemote)
      if (updates.nonEmpty) {
        log.warn("Android SDK updates available, run 'android-update' to update:")
        updates.foreach { u =>
          val p = u.getRemote
          log.warn("    " + p.getDisplayName)
        }
      }
    }
    ()
  }

}
object FallbackSdkLoader extends FallbackRemoteRepoLoader {
  override def parseLegacyXml(xml: RepositorySource, progress: ProgressIndicator) = {
    val repo = Using.urlReader(IO.utf8)(new URL(xml.getUrl))(XML.load)
    val sdkrepo = repo.child.foldLeft((Map.empty[String,License],List.empty[com.android.repository.api.RemotePackage])) {
      case ((ls,ps),e@Elem(prefix,
      "platform" | "platform-tool" | "build-tool" | "tool" | "system-image" | "add-on" | "extra",
      meta, ns, children @ _*)) =>
        (ls,processPackage(xml, ls, e).fold(ps)(_ :: ps))
      case ((ls,ps),e@Elem(prefix, "license", meta, ns, children @ _*)) =>
        val id = e.attribute("id").flatMap(_.headOption).map(_.text)
        (id.fold(ls)(i => ls + ((i,License(i,e.text)))),ps)
      case ((ls,ps),_) => (ls,ps)
    }
    sdkrepo._2.asJava
  }

  def processPackage(src: RepositorySource, ls: Map[String,License], e: Node): Option[com.android.repository.api.RemotePackage] = {
    val archive = (e \ "archives").headOption.toList.flatMap(a => processArchive(a, e.label == "platform"))
    val obsolete = (e \ "obsolete").nonEmpty
    for {
      d <- (e \ "description").headOption.map(_.text)
      r <- (e \ "revision").headOption
    } yield {
      val rev = processRevision(r)
      val (dep,tpe,path) = e.label match {
        case "platform"      =>
          val api = (e \ "api-level").headOption.fold("???")(_.text)
          val llapi = (e \ "layoutlib" \ "api").headOption.fold(-1)(_.text.toInt)
          val tpe = new PlatformDetailsType
          tpe.setApiLevel(api.toInt)
          val layoutlib = new LayoutlibType
          layoutlib.setApi(llapi)
          tpe.setLayoutlib(layoutlib)
          val d = (e \ "min-tools-rev").headOption.map { min =>
            val dep = new DependencyType
            val r = processRevision(min)
            dep.setPath("tools")
            val rev = new RevisionType
            rev.setMajor(r.getMajor)
            rev.setMinor(r.getMinor)
            rev.setMicro(r.getMicro)
            rev.setPreview(r.getPreview)
            dep.setMinRevision(rev)
            dep
          }
          (d,tpe,s"platforms;android-$api")
        case "platform-tool" =>
          (None,new GenericDetailsType,"platform-tools")
        case "system-image" =>
          val api = (e \ "api-level").headOption.fold(-1)(_.text.toInt)
          val tag = (e \ "tag-id").headOption.fold("???")(_.text)
          val tDi = (e \ "tag-display").headOption.fold("???")(_.text)
          val abi = (e \ "abi").headOption.fold("???")(_.text)
          val tpe = new SysImgDetailsType
          tpe.setAbi(abi)
          tpe.setApiLevel(api)
          val idD = new IdDisplayType
          idD.setId(tag)
          idD.setDisplay(tDi)
          tpe.setTag(idD)
          (None,tpe,s"system-images;android-$api;$tag;$abi")
        case "build-tool"    =>
          (None,new GenericDetailsType,"build-tools;" + rev.toShortString)
        case "tool"          =>
          (None,new GenericDetailsType,"tools;" + rev.toShortString)
        case "extra"         =>
          val path = (e \ "path").headOption.fold("???")(_.text)
          val vId = (e \ "vendor-id").headOption.fold("???")(_.text)
          val vDi = (e \ "vendor-display").headOption.fold("???")(_.text)
          val tpe = new ExtraDetailsType
          val idD = new IdDisplayType
          idD.setId(vId)
          idD.setDisplay(vDi)
          tpe.setVendor(idD)
          (None,tpe,s"extras;$vId;$path")
        case "add-on"        =>
          val api = (e \ "api-level").headOption.fold(-1)(_.text.toInt)
          val vId = (e \ "vendor-id").headOption.fold("???")(_.text)
          val vDi = (e \ "vendor-display").headOption.fold("???")(_.text)
          val nId = (e \ "name-id").headOption.fold("???")(_.text)
          val libNodes = e \ "libs" \ "lib"
          val tpe = new AddonDetailsType
          tpe.setApiLevel(api.toInt)
          val idD = new IdDisplayType
          idD.setId(vId)
          idD.setDisplay(vDi)
          tpe.setVendor(idD)
          val libs = new LibrariesType
          val libTypes = libNodes map { n =>
            val nm   = (n \ "name").headOption.fold("???")(_.text)
            val desc = (n \ "description").headOption.fold("???")(_.text)
            val lt = new LibraryType
            lt.setDescription(desc)
            lt.setName(nm)
            lt
          }
          libs.getLibrary.addAll(libTypes.asJava)
          tpe.setLibraries(libs)
          (None,tpe,s"add-ons;addon-$nId-$vId-$api")
      }
      val licref = (e \ "uses-license").headOption.flatMap(_.attribute("ref")).fold("")(_.text)
      RemotePackage(path, d, rev, ls(licref), obsolete, archive, dep, tpe, src).asV1
    }
  }

  def processRevision(e: Node): Revision = {
    val maj = (e \ "major").headOption.map(_.text.toInt)
    val min = (e \ "minor").headOption.map(_.text.toInt: java.lang.Integer)
    val mic = (e \ "micro").headOption.map(_.text.toInt: java.lang.Integer)
    val pre = (e \ "preview").headOption.map(_.text.toInt: java.lang.Integer)
    maj.fold(new Revision(e.text.trim.toInt)) { m =>
      new Revision(m, min.orNull, mic.orNull, pre.orNull)
    }
  }

  def processArchive(e: Node, ignoreHost: Boolean): List[Archive] = {
    val archives = e.child.collect {
      case c@Elem(prefix, "archive", meta, ns, children @ _*) =>
        val size     = c \ "size"
        val checksum = c \ "checksum"
        val url      = c \ "url"
        (for {
          s  <- size.headOption.map(_.text)
          ck <- checksum.headOption.map(_.text)
          u  <- url.headOption.map(_.text)
        } yield ArchiveFile(ck, s.toLong, u)).map { f =>
          val a = Archive(f)
          val hostOs = (c \ "host-os").headOption.map(_.text)
          val hostBits = (c \ "host-bits").headOption.map(_.text.toInt)
          val a1 = hostOs.fold(a)(o => a.copy(hostOs = Some(o)))
          val a2 = hostBits.fold(a1)(b => a1.copy(hostBits = Some(b)))
          a2
        }
    }.flatten.toList
    if (ignoreHost && archives.forall(!_.asV1.isCompatible)) {
      archives.map(_.copy(hostOs = None, hostBits = None))
    } else archives
  }
}
case class RemotePackage(getPath: String,
                         getDisplayName: String,
                         getVersion: Revision,
                         getLicense: License,
                         obsolete: Boolean,
                         archives: List[Archive],
                         dependency: Option[Dependency],
                         getTypeDetails: TypeDetails,
                         getSource: RepositorySource
                        ) {
  def asV1 = {
    val r = new com.android.repository.impl.generated.v1.RemotePackage
    r.setTypeDetails(getTypeDetails)
    r.setPath(getPath)
    r.setDisplayName(getDisplayName)
    r.setVersion(getVersion)
    r.setLicense(getLicense.asV1)
    r.setObsolete(obsolete)
    if (getVersion.getPreview > 0) {
      val channel = new ChannelType
      val channelRef = new ChannelRefType
      channel.setId("2")
      channel.setValue("Dev")
      channelRef.setRef(channel)
      r.setChannelRef(channelRef)
    }
    val dt = new DependenciesType
    dt.getDependency.addAll(dependency.toList.asJava)
    r.setDependencies(dt)
    val as = new com.android.repository.impl.generated.v1.ArchivesType
    as.getArchive.addAll(archives.map(_.asV1).asJava)
    r.setArchives(as)
    r.setSource(getSource)
    r
  }
}

case class License(getId: String, getValue: String) {
  override def toString = s"License(id=$getId)"

  def asV1 = {
    val l = new com.android.repository.impl.generated.v1.LicenseType
    l.setId(getId)
    l.setValue(getValue)
    l
  }
}

case class Archive(getComplete: ArchiveFile, hostOs: Option[String] = None, hostBits: Option[Integer] = None) {
  def asV1 = {
    val a = new com.android.repository.impl.generated.v1.ArchiveType
    a.setComplete(getComplete.asV1)
    a.setHostOs(hostOs.orNull)
    a.setHostBits(hostBits.orNull)
    a
  }
}

case class ArchiveFile(getChecksum: String, getSize: Long, getUrl: String) {
  def asV1 = {
    val a = new com.android.repository.impl.generated.v1.CompleteType
    a.setChecksum(getChecksum)
    a.setSize(getSize)
    a.setUrl(getUrl)
    a
  }
}

case class SbtAndroidDownloader(fop: FileOp) extends Downloader {
  override def downloadFully(url: URL, settings: SettingsController, indicator: ProgressIndicator) = {
    val result = File
      .createTempFile("LegacyDownloader", System.currentTimeMillis.toString)
    result.deleteOnExit()
    val out = fop.newFileOutputStream(result)
    val uc = url.openConnection().asInstanceOf[HttpURLConnection]
    val responseCode = uc.getResponseCode
    if (responseCode == 200) {
      val length = uc.getContentLength
      val in = uc.getInputStream
      Using.bufferedInputStream(in) { b =>
        Using.bufferedOutputStream(out) { o =>
          val len = 65536
          val buf = Array.ofDim[Byte](len)
          indicator.setIndeterminate(length == -1)
          indicator.setText("Downloading " + url.getFile)
          var read = 0
          Iterator.continually(in.read(buf, 0, len)).takeWhile(_ != -1).foreach { r =>
            read = read + r
            if (length != -1)
              indicator.setFraction(read.toDouble / length)
            o.write(buf, 0, r)
          }
          indicator.setFraction(1.0)
          o.close()
          result
        }
      }
    } else null
  }

  //noinspection JavaAccessorMethodCalledAsEmptyParen
  override def downloadAndStream(url: URL,
                                 settings: SettingsController,
                                 indicator: ProgressIndicator) = url.openConnection().getInputStream()
}
