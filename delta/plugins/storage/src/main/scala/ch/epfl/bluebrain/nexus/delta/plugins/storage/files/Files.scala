package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.{ContentType, HttpEntity, Uri}
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => fileSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageIsDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{FetchAttributes, FetchFile, LinkFile, SaveFile}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.RunResult
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.{StorageFileAttributes, StorageReference}
import ch.epfl.bluebrain.nexus.migration.{FilesMigration, MigrationRejection}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import retry.syntax.all._

import java.util.UUID

/**
  * Operations for handling files
  */
final class Files(
    formDataExtractor: FormDataExtractor,
    aggregate: FilesAggregate,
    eventLog: EventLog[Envelope[FileEvent]],
    acls: Acls,
    orgs: Organizations,
    projects: Projects,
    storages: Storages
)(implicit client: HttpClient, uuidF: UUIDF, system: ClassicActorSystem)
    extends FilesMigration {

  // format: off
  private val testStorageRef = ResourceRef.Revision(iri"http://localhost/test", 1)
  private val testStorageType = StorageType.DiskStorage
  private val testAttributes = FileAttributes(UUID.randomUUID(), "http://localhost", Uri.Path.Empty, "", `application/octet-stream`, 0, ComputedDigest(DigestAlgorithm.default, "value"), Client)
  // format: on

  /**
    * Create a new file where the id is  self generated
    *
    * @param storageId  the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef the project where the file will belong
    * @param entity     the http FormData entity
    */
  def create(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      entity: HttpEntity
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- generateId(project)
      _                     <- test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, project)
      attributes            <- extractFileAttributes(iri, entity, storage)
      res                   <- eval(CreateFile(iri, projectRef, storageRef, storage.tpe, attributes, caller.subject), project)
    } yield res
  }.named("createFile", moduleType)

  /**
    * Create a new file with the provided id
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param storageId  the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef the project where the file will belong
    * @param entity     the http FormData entity
    */
  def create(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      entity: HttpEntity
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      _                     <- test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, project)
      attributes            <- extractFileAttributes(iri, entity, storage)
      res                   <- eval(CreateFile(iri, projectRef, storageRef, storage.tpe, attributes, caller.subject), project)
    } yield res
  }.named("createFile", moduleType)

  /**
    * Create a new file linking where the id is self generated
    *
    * @param storageId  the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef the project where the file will belong
    * @param filename   the optional filename to use
    * @param mediaType  the optional media type to use
    * @param path       the path where the file is located inside the storage
    */
  def createLink(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- generateId(project)
      res     <- createLink(iri, project, storageId, filename, mediaType, path)
    } yield res
  }.named("createLink", moduleType)

  /**
    * Create a new file linking it from an existing file in a storage
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param storageId  the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef the project where the file will belong
    * @param filename   the optional filename to use
    * @param mediaType  the optional media type to use
    * @param path       the path where the file is located inside the storage
    */
  def createLink(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- createLink(iri, project, storageId, filename, mediaType, path)
    } yield res
  }.named("createLink", moduleType)

  /**
    * Update an existing file
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param storageId  the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef the project where the file will belong
    * @param rev        the current revision of the file
    * @param entity     the http FormData entity
    */
  def update(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      rev: Long,
      entity: HttpEntity
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      _                     <- test(UpdateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, rev, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, project)
      attributes            <- extractFileAttributes(iri, entity, storage)
      res                   <- eval(UpdateFile(iri, projectRef, storageRef, storage.tpe, attributes, rev, caller.subject), project)
    } yield res
  }.named("updateFile", moduleType)

  /**
    * Update a new file linking it from an existing file in a storage
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param storageId  the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef the project where the file will belong
    * @param rev        the current revision of the file
    * @param filename   the optional filename to use
    * @param mediaType  the optional media type to use
    * @param path       the path where the file is located inside the storage
    */
  def updateLink(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path,
      rev: Long
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      _                     <- test(UpdateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, rev, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, project)
      resolvedFilename      <- IO.fromOption(filename.orElse(path.lastSegment), InvalidFileLink(iri))
      description           <- FileDescription(resolvedFilename, mediaType)
      attributes            <- LinkFile(storage).apply(path, description).mapError(LinkRejection(iri, storage.id, _))
      res                   <- eval(UpdateFile(iri, projectRef, storageRef, storage.tpe, attributes, rev, caller.subject), project)
    } yield res
  }.named("updateLink", moduleType)

  /**
    * Update an existing file attributes
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param projectRef the project where the file will belong
    * @param mediaType  the media type of the file
    * @param bytes      the size of the file file in bytes
    * @param digest     the digest information of the file
    * @param rev        the current revision of the file
    */
  def updateAttributes(
      id: IdSegment,
      projectRef: ProjectRef,
      mediaType: ContentType,
      bytes: Long,
      digest: Digest,
      rev: Long
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(UpdateFileAttributes(iri, projectRef, mediaType, bytes, digest, rev, subject), project)
    } yield res
  }.named("updateFileAttributes", moduleType)

  /**
    * Update an existing file attributes
    *
    * @param iri         the file iri identifier
    * @param projectRef the project where the file will belong
    */
  private[files] def updateAttributes(
      iri: Iri,
      projectRef: ProjectRef
  )(implicit subject: Subject): IO[FileRejection, FileResource] =
    for {
      file      <- fetch(iri, projectRef)
      _         <- IO.when(file.value.attributes.digest.computed)(IO.raiseError(DigestAlreadyComputed(file.id)))
      storageRev = file.value.storage
      storage   <- storages.fetchAt(storageRev.iri, projectRef, storageRev.rev).mapError(WrappedStorageRejection)
      attr       = file.value.attributes
      newAttr   <- FetchAttributes(storage.value).apply(attr).mapError(FetchAttributesRejection(iri, storage.id, _))
      res       <- updateAttributes(iri, projectRef, newAttr.mediaType, newAttr.bytes, newAttr.digest, file.rev)
    } yield res

  /**
    * Add a tag to an existing file
    *
    * @param id         the file identifier to expand as the iri of the storage
    * @param projectRef the project where the file belongs
    * @param tag        the tag name
    * @param tagRev     the tag revision
    * @param rev        the current revision of the file
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(TagFile(iri, projectRef, tagRev, tag, rev, subject), project)
    } yield res
  }.named("tagFile", moduleType)

  /**
    * Deprecate an existing file
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param projectRef the project where the file belongs
    * @param rev        the current revision of the file
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(DeprecateFile(iri, projectRef, rev, subject), project)
    } yield res
  }.named("deprecateFile", moduleType)

  /**
    * Fetch the last version of a file content
    *
    * @param id        the file identifier to expand as the iri of the file
    * @param project   the project where the storage belongs
    */
  def fetchContent(
      id: IdSegment,
      project: ProjectRef
  )(implicit caller: Caller): IO[FileRejection, FileResponse] =
    fetchContent(id, project, None).named("fetchFileContent", moduleType)

  /**
    * Fetches the file content at a given revision
    *
    * @param id      the file identifier to expand as the iri of the file
    * @param project the project where the file belongs
    * @param rev     the current revision of the file
    */
  def fetchContentAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  )(implicit caller: Caller): IO[FileRejection, FileResponse] =
    fetchContent(id, project, Some(rev)).named("fetchFileContentAt", moduleType)

  /**
    * Fetches a file content by tag.
    *
    * @param id      the file identifier to expand as the iri of the file
    * @param project the project where the file belongs
    * @param tag     the tag revision
    */
  def fetchContentBy(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel
  )(implicit caller: Caller): IO[FileRejection, FileResponse] =
    fetch(id, project, None)
      .flatMap { resource =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchContentAt(id, project, rev).mapError(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      }
      .named("fetchFileContentByTag", moduleType)

  /**
    * Fetch the last version of a file
    *
    * @param id         the file identifier to expand as the iri of the file
    * @param project the project where the storage belongs
    */
  def fetch(id: IdSegment, project: ProjectRef): IO[FileRejection, FileResource] =
    fetch(id, project, None).named("fetchFile", moduleType)

  /**
    * Fetches the file at a given revision
    *
    * @param id      the file identifier to expand as the iri of the file
    * @param project the project where the file belongs
    * @param rev     the current revision of the file
    */
  def fetchAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  ): IO[FileRejection, FileResource] =
    fetch(id, project, Some(rev)).named("fetchFileAt", moduleType)

  /**
    * Fetches a file by tag.
    *
    * @param id        the file identifier to expand as the iri of the file
    * @param project   the project where the file belongs
    * @param tag       the tag revision
    */
  def fetchBy(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel
  ): IO[FileRejection, FileResource] =
    fetch(id, project, None)
      .flatMap { resource =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, project, rev).mapError(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      }
      .named("fetchFileByTag", moduleType)

  /**
    * A non terminating stream of events for files. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param projectRef the project reference where the files belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[FileRejection, Stream[Task, Envelope[FileEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(eventLog.eventsByTag(Projects.projectTag(moduleType, projectRef), offset))

  /**
    * A non terminating stream of events for storages. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param organization the organization label reference where the file belongs
    * @param offset       the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[FileEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(eventLog.eventsByTag(Organizations.orgTag(moduleType, organization), offset))

  /**
    * A non terminating stream of events for files. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[FileEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def createLink(
      iri: Iri,
      project: Project,
      storageId: Option[IdSegment],
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path
  )(implicit caller: Caller): IO[FileRejection, FileResource] =
    for {
      _                     <- test(CreateFile(iri, project.ref, testStorageRef, testStorageType, testAttributes, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, project)
      resolvedFilename      <- IO.fromOption(filename.orElse(path.lastSegment), InvalidFileLink(iri))
      description           <- FileDescription(resolvedFilename, mediaType)
      attributes            <- LinkFile(storage).apply(path, description).mapError(LinkRejection(iri, storage.id, _))
      res                   <- eval(CreateFile(iri, project.ref, storageRef, storage.tpe, attributes, caller.subject), project)
    } yield res

  private def fetch(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Option[Long]
  ): IO[FileRejection, FileResource] =
    for {
      project <- projects.fetchProject(projectRef)
      iri     <- expandIri(id, project)
      state   <- rev.fold(currentState(projectRef, iri))(stateAt(projectRef, iri, _))
      res     <- IO.fromOption(state.toResource(project.apiMappings, project.base), FileNotFound(iri, projectRef))
    } yield res

  private def fetchContent(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Option[Long]
  )(implicit caller: Caller): IO[FileRejection, FileResponse] =
    for {
      file      <- fetch(id, projectRef, rev)
      attributes = file.value.attributes
      storage   <- storages.fetch(file.value.storage, projectRef)
      _         <- authorizeFor(projectRef, storage.value.storageValue.readPermission)
      source    <- FetchFile(storage.value).apply(file.value.attributes).mapError(FetchRejection(file.id, storage.id, _))
    } yield FileResponse(attributes.filename, attributes.mediaType, attributes.bytes, source)

  private def eval(cmd: FileCommand, project: Project): IO[FileRejection, FileResource] =
    for {
      evaluationResult <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      resourceOpt       = evaluationResult.state.toResource(project.apiMappings, project.base)
      res              <- IO.fromOption(resourceOpt, UnexpectedInitialState(cmd.id, project.ref))
    } yield res

  private def test(cmd: FileCommand) =
    aggregate.dryRun(identifier(cmd.project, cmd.id), cmd).mapError(_.value)

  private def currentState(project: ProjectRef, iri: Iri): IO[FileRejection, FileState] =
    aggregate.state(identifier(project, iri))

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) =
    EventLogUtils
      .fetchStateAt(eventLog, persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .mapError(RevisionNotFound(rev, _))

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"

  private def fetchActiveStorage(
      storageIdOpt: Option[IdSegment],
      project: Project
  )(implicit caller: Caller): IO[FileRejection, (ResourceRef.Revision, Storage)] =
    storageIdOpt match {
      case Some(storageId) =>
        for {
          iri     <- expandStorageIri(storageId, project)
          storage <- storages.fetch(ResourceRef(iri), project.ref)
          _       <- IO.when(storage.deprecated)(IO.raiseError(WrappedStorageRejection(StorageIsDeprecated(iri))))
          _       <- authorizeFor(project.ref, storage.value.storageValue.writePermission)
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
      case None            =>
        for {
          storage <- storages.fetchDefault(project.ref).mapError(WrappedStorageRejection)
          _       <- authorizeFor(project.ref, storage.value.storageValue.writePermission)
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
    }

  private def extractFileAttributes(iri: Iri, entity: HttpEntity, storage: Storage): IO[FileRejection, FileAttributes] =
    for {
      (description, source) <- formDataExtractor(iri, entity, storage.storageValue.maxFileSize)
      attributes            <- SaveFile(storage).apply(description, source).mapError(SaveRejection(iri, storage.id, _))
    } yield attributes

  private def expandStorageIri(segment: IdSegment, project: Project): IO[WrappedStorageRejection, Iri] =
    Storages.expandIri(segment, project).mapError(WrappedStorageRejection)

  private def generateId(project: Project)(implicit uuidF: UUIDF): UIO[Iri] =
    uuidF().map(uuid => project.base.iri / uuid.toString)

  def authorizeFor(
      projectRef: ProjectRef,
      permission: Permission
  )(implicit caller: Caller): IO[AuthorizationFailed, Unit] = {
    val address = AclAddress.Project(projectRef)
    acls.authorizeFor(address, permission).flatMap { hasAccess =>
      IO.unless(hasAccess)(IO.raiseError(AuthorizationFailed(address, permission)))
    }
  }

  // Ignore errors that may happen when an event gets replayed twice after a migration restart
  private def errorRecover: PartialFunction[FileRejection, RunResult] = {
    case _: FileAlreadyExists                                    => RunResult.Success
    case IncorrectRev(provided, expected) if provided < expected => RunResult.Success
    case _: FileIsDeprecated                                     => RunResult.Success
  }

  override def migrate(
      id: Iri,
      projectRef: ProjectRef,
      rev: Option[Long],
      storage: StorageReference,
      attributes: kg.FileAttributes
  )(implicit caller: Subject): IO[MigrationRejection, RunResult] =
    for {
      project                   <- projects.fetchActiveProject(projectRef).mapError(MigrationRejection(_))
      (storageRef, storageType) <-
        storages
          .fetch(Revision(storage.id, storage.rev), project.ref)
          .map(Revision(storage.id, storage.rev) -> _.value.tpe)
          .onErrorFallbackTo(IO.pure(Revision(storage.id, storage.rev) -> StorageType.DiskStorage))
      digest                     = digestV15(attributes.digest)
      origin                     = digest match {
                                     case NotComputedDigest => FileAttributesOrigin.Storage
                                     case _                 => FileAttributesOrigin.Client
                                   }
      attributes15               = FileAttributes(
                                     attributes.uuid,
                                     attributes.location,
                                     attributes.path,
                                     attributes.filename,
                                     attributes.mediaType,
                                     attributes.bytes,
                                     digest,
                                     origin
                                   )
      _                         <- eval(
                                     MigrateFile(id, projectRef, storageRef, storageType, attributes15, rev.getOrElse(0L), caller),
                                     project
                                   ).as(RunResult.Success).onErrorRecover(errorRecover).mapError(MigrationRejection(_))
    } yield RunResult.Success

  override def fileAttributesUpdated(id: Iri, projectRef: ProjectRef, rev: Long, attributes: StorageFileAttributes)(
      implicit subject: Subject
  ): IO[MigrationRejection, RunResult] = {
    updateAttributes(
      id,
      projectRef,
      attributes.mediaType,
      attributes.bytes,
      digestV15(attributes.digest),
      rev
    )
  }.as(RunResult.Success).onErrorRecover(errorRecover).mapError(MigrationRejection(_))

  override def fileDigestUpdated(id: Iri, projectRef: ProjectRef, rev: Long, digest: kg.Digest)(implicit
      subject: Subject
  ): IO[MigrationRejection, RunResult] = {
    val segment = id
    fetch(segment, projectRef).flatMap { res =>
      updateAttributes(
        id,
        projectRef,
        res.value.attributes.mediaType,
        res.value.attributes.bytes,
        digestV15(digest),
        rev
      )
    }
  }.as(RunResult.Success).onErrorRecover(errorRecover).mapError(MigrationRejection(_))

  private def digestV15(digestV14: kg.Digest) =
    if (digestV14 == kg.Digest.empty) NotComputedDigest
    else ComputedDigest(DigestAlgorithm(digestV14.algorithm).getOrElse(DigestAlgorithm.default), digestV14.value)

  override def migrateTag(id: IdSegment, projectRef: ProjectRef, tagLabel: TagLabel, tagRev: Long, rev: Long)(implicit
      subject: Subject
  ): IO[MigrationRejection, RunResult] =
    tag(id, projectRef, tagLabel, tagRev, rev)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def migrateDeprecate(id: IdSegment, projectRef: ProjectRef, rev: Long)(implicit
      subject: Subject
  ): IO[MigrationRejection, RunResult] =
    deprecate(id, projectRef, rev).as(RunResult.Success).onErrorRecover(errorRecover).mapError(MigrationRejection(_))
}

@SuppressWarnings(Array("MaxParameters"))
object Files {

  /**
    * The files module type.
    */
  final val moduleType: String = "file"

  val expandIri: ExpandIri[InvalidFileId] = new ExpandIri(InvalidFileId.apply)

  val context: ContextValue = ContextValue(contexts.files)

  /**
    * The default File API mappings
    */
  val mappings: ApiMappings = ApiMappings("file" -> fileSchema)

  private[files] type FilesAggregate =
    Aggregate[String, FileState, FileCommand, FileEvent, FileRejection]

  private val logger: Logger = Logger[Files]

  /**
    * Constructs a Files instance
    *
    * @param config   the files configuration
    * @param eventLog the event log for FileEvent
    * @param acls     the acls operations bundle
    * @param orgs     the organizations operations bundle
    * @param projects the projects operations bundle
    * @param storages the storages operations bundle
    */
  final def apply(
      config: FilesConfig,
      eventLog: EventLog[Envelope[FileEvent]],
      acls: Acls,
      orgs: Organizations,
      projects: Projects,
      storages: Storages
  )(implicit
      client: HttpClient,
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Task[Files] = {
    implicit val classicAs: ClassicActorSystem = as.classicSystem
    for {
      agg  <- aggregate(config.aggregate)
      files = apply(agg, eventLog, acls, orgs, projects, storages)
      _    <- startDigestComputation(config.cacheIndexing, eventLog, files)
    } yield files
  }

  private def apply(
      agg: FilesAggregate,
      eventLog: EventLog[Envelope[FileEvent]],
      acls: Acls,
      orgs: Organizations,
      projects: Projects,
      storages: Storages
  )(implicit client: HttpClient, system: ClassicActorSystem, sc: Scheduler, uuidF: UUIDF) =
    new Files(FormDataExtractor.apply, agg, eventLog, acls, orgs, projects, storages)

  private def aggregate(config: AggregateConfig)(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ) = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate,
      tagger = EventTags.forProjectScopedEvent(moduleType),
      snapshotStrategy = config.snapshotStrategy.combinedStrategy(
        SnapshotStrategy.SnapshotPredicate((state: FileState, _: FileEvent, _: Long) => state.deprecated)
      ),
      stopStrategy = config.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.processor
      // TODO: configure the number of shards
    )
  }

  private def startDigestComputation(
      indexing: CacheIndexingConfig,
      eventLog: EventLog[Envelope[FileEvent]],
      files: Files
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) = {
    val retryFileAttributes = RetryStrategy[FileRejection](
      indexing.retry,
      {
        case FetchRejection(_, _, FetchFileRejection.UnexpectedFetchError(_, _)) => true
        case DigestNotComputed(_)                                                => true
        case _                                                                   => false
      },
      RetryStrategy.logError(logger, "file attributes update")
    )
    StreamSupervisor(
      "FileAttributesUpdate",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(indexing.concurrency) { envelope =>
            files
              .updateAttributes(envelope.event.id, envelope.event.project)(envelope.event.subject)
              .redeemWith(
                {
                  case DigestAlreadyComputed(_) => IO.unit
                  case err                      => IO.raiseError(err)
                },
                {
                  case res if !res.value.attributes.digest.computed => IO.raiseError(DigestNotComputed(res.id))
                  case _                                            => IO.unit
                }
              )
              .retryingOnSomeErrors(
                retryFileAttributes.retryWhen,
                retryFileAttributes.policy,
                retryFileAttributes.onError
              )
              .attempt >> IO.unit
          }
      ),
      retryStrategy = RetryStrategy(
        indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "file attributes eventlog reply")
      )
    )
  }

  private[files] def next(
      state: FileState,
      event: FileEvent
  ): FileState = {
    // format: off
    def created(e: FileCreated): FileState = state match {
      case Initial     => Current(e.id, e.project, e.storage, e.storageType, e.attributes, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: FileUpdated): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, storage = e.storage, storageType = e.storageType, attributes = e.attributes, updatedAt = e.instant, updatedBy = e.subject)
    }

    def updatedAttributes(e: FileAttributesUpdated): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, attributes = s.attributes.copy( mediaType = e.mediaType, bytes = e.bytes, digest = e.digest), updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: FileTagAdded): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: FileDeprecated): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: FileCreated           => created(e)
      case e: FileUpdated           => updated(e)
      case e: FileAttributesUpdated => updatedAttributes(e)
      case e: FileTagAdded          => tagAdded(e)
      case e: FileDeprecated        => deprecated(e)
    }
  }

  private[files] def evaluate(
      state: FileState,
      cmd: FileCommand
  )(implicit clock: Clock[UIO]): IO[FileRejection, FileEvent] = {

    def create(c: CreateFile) = state match {
      case Initial =>
        IOUtils.instant.map(FileCreated(c.id, c.project, c.storage, c.storageType, c.attributes, 1L, _, c.subject))
      case _       =>
        IO.raiseError(FileAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateFile) = state match {
      case Initial                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current if s.attributes.digest == NotComputedDigest => IO.raiseError(DigestNotComputed(c.id))
      case s: Current                                             =>
        IOUtils.instant
          .map(FileUpdated(c.id, c.project, c.storage, c.storageType, c.attributes, s.rev + 1L, _, c.subject))
    }

    def updateAttributes(c: UpdateFileAttributes) = state match {
      case Initial                      => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current                   =>
        // format: off
        IOUtils.instant
          .map(FileAttributesUpdated(c.id, c.project, c.mediaType, c.bytes, c.digest, s.rev + 1L, _, c.subject))
      // format: on
    }

    def tag(c: TagFile) = state match {
      case Initial                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(FileTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1L, _, c.subject))
    }

    def deprecate(c: DeprecateFile) = state match {
      case Initial                      => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current                   => IOUtils.instant.map(FileDeprecated(c.id, c.project, s.rev + 1L, _, c.subject))
    }

    def migrate(c: MigrateFile) = state match {
      case Initial if c.rev == 0L                                 =>
        IOUtils.instant.map(FileCreated(c.id, c.project, c.storage, c.storageType, c.attributes, 1L, _, c.subject))
      case Initial                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current if s.attributes.digest == NotComputedDigest => IO.raiseError(DigestNotComputed(c.id))
      case s: Current                                             =>
        IOUtils.instant
          .map(FileUpdated(c.id, c.project, c.storage, c.storageType, c.attributes, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateFile           => create(c)
      case c: UpdateFile           => update(c)
      case c: UpdateFileAttributes => updateAttributes(c)
      case c: TagFile              => tag(c)
      case c: DeprecateFile        => deprecate(c)
      case c: MigrateFile          => migrate(c)
    }
  }
}
