package ch.epfl.bluebrain.nexus.migration

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acl
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.ImportRealm
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.PriorityAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.migration.instances._
import ch.epfl.bluebrain.nexus.migration.Migration._
import ch.epfl.bluebrain.nexus.migration.replay.{ReplayMessageEvents, ReplaySettings}
import ch.epfl.bluebrain.nexus.migration.v1_4.SourceSanitizer
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent.EmptyEvent
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.{OrganizationEvent, ProjectEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.PermissionsEvent.{PermissionsAppended, PermissionsDeleted, PermissionsReplaced, PermissionsSubtracted}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.{AclEvent, PermissionsEvent, RealmEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.Event
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.Event.{Created, Deprecated, FileAttributesUpdated, FileCreated, FileDigestUpdated, FileUpdated, TagAdded, Updated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.{EventDeserializationFailed, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.sourcing.config.{CassandraConfig, PersistProgressConfig}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Projection, RunResult}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import io.circe.optics.JsonPath.root
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import scala.concurrent.duration._

import java.util.UUID
import scala.util.Try

/**
  * Migration module from v1.4 to v1.5
  */
final class Migration(
    replayMessageEvents: ReplayMessageEvents,
    projection: Projection[ToMigrateEvent],
    persistProgressConfig: PersistProgressConfig,
    clock: MutableClock,
    uuidF: MutableUUIDF,
    permissions: Permissions,
    acls: Acls,
    realms: Realms,
    projects: Projects,
    organizations: Organizations,
    resources: Resources,
    schemas: Schemas,
    resolvers: Resolvers,
    storageMigration: StoragesMigration,
    fileMigration: FilesMigration
)(implicit scheduler: Scheduler) {

  implicit val projectionId: ViewProjectionId = ViewProjectionId("migration-v1.5")

  // Project cache to avoid to call the project cache each time
  private val cache = collection.mutable.Map[UUID, ProjectRef]()

  /**
    * Start the migration from the stored offset
    */
  def start: Task[fs2.Stream[Task, ToMigrateEvent]] =
    projection.progress(projectionId).map { progress =>
      logger.info(s"Starting migration at offset ${progress.offset}")
      replayMessageEvents
        .run(progress.offset)
        .runAsync(process)
        .persistProgress(
          progress,
          projection,
          persistProgressConfig
        )
    }

  private def process(event: ToMigrateEvent): Task[RunResult] =
    event match {
      case p: PermissionsEvent           => processPermission(p)
      case a: AclEvent                   => processAcl(a)
      case r: RealmEvent                 => processRealm(r)
      case o: OrganizationEvent          => processOrganization(o)
      case p: ProjectEvent               => processProject(p)
      case e: Event                      => processResource(e)
      case e: EventDeserializationFailed => Task.raiseError(MigrationRejection(e))
    }

  private def processPermission(permissionEvent: PermissionsEvent): Task[RunResult] = {
    clock.setInstant(permissionEvent.instant)
    val cRev                = permissionEvent.rev - 1
    implicit val s: Subject = permissionEvent.subject
    permissionEvent match {
      case PermissionsAppended(_, permissionsSet, _, _)  =>
        permissions.append(permissionsSet, cRev)
      case _: PermissionsDeleted                         =>
        permissions.delete(cRev)
      case PermissionsReplaced(_, permissionsSet, _, _)  =>
        permissions.replace(permissionsSet, cRev)
      case PermissionsSubtracted(_, permissionSet, _, _) =>
        permissions.subtract(permissionSet, cRev)
    }
  }.toTask.as(RunResult.Success)

  private def processAcl(aclEvent: AclEvent): Task[RunResult] = {
    clock.setInstant(aclEvent.instant)
    implicit val s: Subject = aclEvent.subject
    val cRev                = aclEvent.rev - 1
    aclEvent match {
      case AclAppended(path, acl, _, _, _)   =>
        acls.append(Acl(path, acl.value), cRev)
      case AclDeleted(path, _, _, _)         =>
        acls.delete(path, cRev)
      case AclReplaced(path, acl, _, _, _)   =>
        acls.replace(Acl(path, acl.value), cRev)
      case AclSubtracted(path, acl, _, _, _) =>
        acls.subtract(Acl(path, acl.value), cRev)
    }
  }.toTask.as(RunResult.Success)

  private def processRealm(realmEvent: RealmEvent): Task[RunResult] = {
    clock.setInstant(realmEvent.instant)
    implicit val s: Subject = realmEvent.subject
    realmEvent match {
      case RealmCreated(
            id,
            rev,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          ) =>
        realms.importRealm(
          ImportRealm(
            id,
            rev - 1,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          )
        )
      case RealmUpdated(
            id,
            rev,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          ) =>
        realms.importRealm(
          ImportRealm(
            id,
            rev - 1,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          )
        )
      case RealmDeprecated(id, rev, _, _) =>
        realms.deprecate(id, rev - 1)
    }
  }.toTask.as(RunResult.Success)

  private def fetchOrganizationLabel(orgUuid: UUID): IO[OrganizationRejection.OrganizationNotFound, Label] =
    organizations.fetch(orgUuid).map(_.value.label)

  private def fetchProjectRef(projectUuid: UUID): IO[ProjectNotFound, ProjectRef] =
    IO.fromOption(cache.get(projectUuid), ProjectNotFound(projectUuid))
      .onErrorFallbackTo(
        projects
          .fetch(projectUuid)
          .map(_.value.ref)
          .tapEval(p => UIO.delay(cache.put(projectUuid, p)))
          // We retry as projects cache may not be ready after a restart and all projects must be migrated
          .tapError(e =>
            UIO.delay(logger.error(s"Project $projectUuid can't be found, we will backoff and retry", e)) >>
              UIO.sleep(5.seconds)
          )
          .onErrorRestartIf(_ => true)
      )

  private[migration] def processOrganization(organizationEvent: OrganizationEvent): Task[RunResult] = {
    clock.setInstant(organizationEvent.instant)
    implicit val s: Subject = organizationEvent.subject
    val cRev                = organizationEvent.rev - 1
    organizationEvent match {
      case OrganizationCreated(id, label, description, _, _)   =>
        uuidF.setUUID(id)
        organizations.create(label, description)
      case OrganizationUpdated(_, _, label, description, _, _) =>
        organizations.update(label, description, cRev)
      case OrganizationDeprecated(id, _, _, _)                 =>
        fetchOrganizationLabel(id).flatMap(organizations.deprecate(_, cRev))
    }
  }.toTask.as(RunResult.Success)

  private[migration] def processProject(projectEvent: ProjectEvent): Task[RunResult] = {
    clock.setInstant(projectEvent.instant)
    implicit val s: Subject = projectEvent.subject
    val cRev                = projectEvent.rev - 1
    projectEvent match {
      case ProjectCreated(id, label, _, organizationLabel, description, apiMappings, base, vocab, _, _) =>
        uuidF.setUUID(id)
        val projectFields = ProjectFields(
          description,
          ApiMappings(apiMappings),
          Some(base),
          Some(vocab)
        )
        val projectRef    = ProjectRef(organizationLabel, label)
        projects.create(ProjectRef(organizationLabel, label), projectFields) <* UIO.delay(cache.put(id, projectRef))
      case ProjectUpdated(id, _, description, apiMappings, base, vocab, _, _, _)                        =>
        val projectFields = ProjectFields(
          description,
          ApiMappings(apiMappings),
          Some(base),
          Some(vocab)
        )
        fetchProjectRef(id).flatMap(projects.update(_, cRev, projectFields))
      case ProjectDeprecated(id, _, _, _)                                                               =>
        fetchProjectRef(id).flatMap(projects.deprecate(_, cRev))
    }
  }.toTask.as(RunResult.Success)

  // Replace id by the expanded one previous computed
  private def fixId(source: Json, id: Iri): Json =
    root.`@id`.string.modify(_ => id.toString)(source)

  private def fixSource(id: Iri, source: Json): Json = SourceSanitizer.sanitize(id)(source)

  // Replace project uuids in cross-project resolvers by project refs
  private val replaceProjectUuids: Json => Task[Json] = root.projects.arr.modifyF { uuids =>
    uuids.traverse { json =>
      json.asString match {
        case Some(str) =>
          IO.fromTry(Try(UUID.fromString(str)))
            .flatMap { uuid =>
              fetchProjectRef(uuid).map(_.asJson).leftWiden[ProjectRejection].toTask
            }
            .onErrorFallbackTo(Task.pure(json))
        case None      => IO.raiseError(ProjectNotFound(json)).leftWiden[ProjectRejection].toTask
      }
    }
  }

  private val incrementPriority: Json => Json = root.priority.int.modify(p => Math.min(p + 1, 1000))

  private def getIdentities(source: Json): Set[Identity] =
    root.identities.arr.getOption(source).fold(Set.empty[Identity])(_.flatMap(_.as[Identity].toOption).toSet)

  private def fixResolverSource(id: Iri, source: Json): Task[Json] = {
    // Resolver id can't be expanded anymore so we give the one already computed in previous version
    val s =
      root.`@id`.string.modify(idPayload => if (idPayload == "nxv:defaultInProject") id.toString else idPayload)(source)
    replaceProjectUuids(SourceSanitizer.sanitize(id)(s))
  }

  private def fixStorageSource(id: Iri, source: Json): Json = {
    // Resolver id can't be expanded anymore so we give the one already computed in previous version
    val s =
      root.`@id`.string.modify(idPayload => if (idPayload == "nxv:diskStorageDefault") id.toString else idPayload)(
        source
      )
    SourceSanitizer.sanitize(id)(s)
  }

  private def processResource(event: Event): Task[RunResult] = {
    clock.setInstant(event.instant)
    implicit val caller: Caller = Caller(event.subject, Set.empty)
    val cRev                    = event.rev - 1

    fetchProjectRef(event.project)
      .leftWiden[ProjectRejection]
      .toTask
      .flatMap { projectRef =>
        {
          event match {
            // Schemas
            case Created(id, _, _, _, types, source, _, _) if types.contains(nxv.Schema)          =>
              val fixedSource           = fixSource(id, source)
              def createSchema(s: Json) = schemas.create(IriSegment(id), projectRef, s)
              createSchema(fixedSource)
                .as(RunResult.Success)
                .onErrorRecoverWith {
                  case SchemaRejection.UnexpectedSchemaId(_, idPayload)      =>
                    logger.warn(s"Fixing id when creating schema $id in $projectRef")
                    createSchema(fixId(fixedSource, id)).as(
                      Warnings.unexpectedId("schema", id, projectRef, idPayload)
                    )
                  case SchemaRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                    logger.warn(s"Fixing id when creating schema $id in $projectRef")
                    createSchema(fixId(fixedSource, id)).as(
                      Warnings.invalidId("schema", id, projectRef, i.getMessage)
                    )
                }
                .toTask
            case Updated(id, _, _, _, types, source, _, _) if types.contains(nxv.Schema)          =>
              val fixedSource           = fixSource(id, source)
              def updateSchema(s: Json) = schemas.update(IriSegment(id), projectRef, cRev, s)
              updateSchema(fixedSource)
                .as(RunResult.Success)
                .onErrorRecoverWith {
                  case SchemaRejection.UnexpectedSchemaId(_, idPayload)      =>
                    logger.warn(s"Fixing id when updating schema $id in $projectRef")
                    updateSchema(fixId(fixedSource, id)).as(
                      Warnings.unexpectedId("schema", id, projectRef, idPayload)
                    )
                  case SchemaRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                    logger.warn(s"Fixing id when updating schema $id in $projectRef")
                    updateSchema(fixId(fixedSource, id)).as(
                      Warnings.invalidId("schema", id, projectRef, i.getMessage)
                    )
                }
                .toTask
            case Deprecated(id, _, _, _, types, _, _) if types.contains(nxv.Schema)               =>
              schemas.deprecate(IriSegment(id), projectRef, cRev).toTask.as(RunResult.Success)
            // Resolvers
            case Created(id, _, _, _, types, source, _, _) if types.contains(nxv.Resolver)        =>
              val resolverCaller               = caller.copy(identities = getIdentities(source))
              // We can have SEVERAL resolvers with the same priority in the same project :'(
              def createResolver(source: Json) = IO
                .tailRecM(successResult -> source) { case (result, json) =>
                  resolvers.create(IriSegment(id), projectRef, json)(resolverCaller).attempt.flatMap {
                    case Left(_: PriorityAlreadyExists) =>
                      logger.warn(s"Incrementing priority when creating resolver $id in $projectRef")
                      IO.pure(Left(Warnings.priority(id, projectRef) -> incrementPriority(json)))
                    case Left(e)                        => IO.raiseError(e)
                    case Right(r)                       => IO.pure(Right(result -> r))
                  }
                }
                .map(_._1)

              fixResolverSource(id, source).flatMap { s =>
                createResolver(s).onErrorRecoverWith {
                  case ResolverRejection.UnexpectedResolverId(_, payloadId)    =>
                    logger.warn(s"Fixing id when creating resolver $id in $projectRef")
                    createResolver(fixId(s, id))
                      .as(Warnings.unexpectedId("resolver", id, projectRef, payloadId))
                  case ResolverRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                    logger.warn(s"Fixing id when creating resolver $id in $projectRef")
                    createResolver(fixId(s, id)).as(
                      Warnings.invalidId("schema", id, projectRef, i.getMessage)
                    )
                }.toTask
              }
            case Updated(id, _, _, _, types, source, _, _) if types.contains(nxv.Resolver)        =>
              val resolverCaller               = caller.copy(identities = getIdentities(source))
              // We can have SEVERAL resolvers with the same priority in the same project :'(
              def updateResolver(source: Json) = IO
                .tailRecM(successResult -> source) { case (result, json) =>
                  resolvers.update(IriSegment(id), projectRef, cRev, source)(resolverCaller).attempt.flatMap {
                    case Left(_: PriorityAlreadyExists) =>
                      logger.warn(s"Incrementing priority when updating resolver $id in $projectRef")
                      IO.pure(Left(Warnings.priority(id, projectRef) -> incrementPriority(json)))
                    case Left(e)                        => IO.raiseError(e)
                    case Right(r)                       => IO.pure(Right(result -> r))
                  }
                }
                .map(_._1)
              fixResolverSource(id, source).flatMap { s =>
                updateResolver(s)
                  .as(RunResult.Success)
                  .onErrorRecoverWith {
                    case ResolverRejection.UnexpectedResolverId(_, payloadId)    =>
                      logger.warn(s"Fixing id when updating resolver $id in $projectRef")
                      updateResolver(fixId(s, id)).as(Warnings.unexpectedId("resolver", id, projectRef, payloadId))
                    case ResolverRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                      logger.warn(s"Fixing id when updating resolver $id in $projectRef")
                      updateResolver(fixId(s, id)).as(
                        Warnings.invalidId("schema", id, projectRef, i.getMessage)
                      )
                  }
                  .toTask
              }
            case Deprecated(id, _, _, rev, types, _, _) if types.contains(nxv.Resolver)           =>
              resolvers.deprecate(IriSegment(id), projectRef, rev - 1).toTask.as(RunResult.Success)
            //TODO Views
            case Created(_, _, _, _, types, _, _, _) if types.exists(viewTypes.contains)          =>
              IO.pure(RunResult.Success)
            case Updated(_, _, _, _, types, _, _, _) if types.exists(viewTypes.contains)          =>
              IO.pure(RunResult.Success)
            case Deprecated(_, _, _, _, types, _, _) if types.exists(viewTypes.contains)          =>
              IO.pure(RunResult.Success)
            case Created(id, _, _, _, types, source, _, _) if types.exists(storageTypes.contains) =>
              val fixedSource = fixStorageSource(id, source)
              storageMigration.migrate(id, projectRef, None, fixedSource).as(RunResult.Success)
            case Updated(id, _, _, _, types, source, _, _) if types.exists(storageTypes.contains) =>
              val fixedSource = fixStorageSource(id, source)
              storageMigration.migrate(id, projectRef, Some(cRev), fixedSource).as(RunResult.Success)
            case Deprecated(id, _, _, _, types, _, _) if types.exists(storageTypes.contains)      =>
              storageMigration.migrateDeprecate(IriSegment(id), projectRef, cRev).as(RunResult.Success)
            // Data resources
            case Created(id, _, _, schema, _, source, _, _)                                       =>
              val schemaSegment                                                =
                if (schema.original == unsconstrained) IriSegment(Vocabulary.schemas.resources)
                else IriSegment(schema.original)
              val fixedSource                                                  = fixSource(id, source)
              def createResource(s: Json): IO[ResourceRejection, DataResource] =
                resources.create(IriSegment(id), projectRef, schemaSegment, s)
              createResource(fixedSource)
                .as(RunResult.Success)
                .onErrorRecoverWith {
                  case ResourceRejection.UnexpectedResourceId(_, payloadId)    =>
                    logger.warn(s"Fixing id when creating resource $id in $projectRef")
                    createResource(fixId(fixedSource, id)).as(
                      Warnings.unexpectedId("resource", id, projectRef, payloadId)
                    )
                  case ResourceRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                    logger.warn(s"Fixing id when creating resource $id in $projectRef")
                    createResource(fixId(fixedSource, id)).as(
                      Warnings.invalidId("schema", id, projectRef, i.getMessage)
                    )

                }
                .toTask
            case Updated(id, _, _, _, _, source, _, _)                                            =>
              val fixedSource             = fixSource(id, source)
              def updateResource(s: Json) = resources.update(IriSegment(id), projectRef, None, cRev, s)
              updateResource(fixedSource)
                .as(RunResult.Success)
                .onErrorRecoverWith {
                  case ResourceRejection.UnexpectedResourceId(_, payloadId) =>
                    logger.warn(s"Fixing id when updating resource $id in $projectRef")
                    updateResource(fixId(fixedSource, id)).as(
                      Warnings.unexpectedId("resource", id, projectRef, payloadId)
                    )

                  case ResourceRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                    logger.warn(s"Fixing id when updating resource $id in $projectRef")
                    updateResource(fixId(fixedSource, id)).as(
                      Warnings.invalidId("schema", id, projectRef, i.getMessage)
                    )
                }
                .toTask
            case Deprecated(id, _, _, _, _, _, _)                                                 =>
              resources.deprecate(IriSegment(id), projectRef, None, cRev).toTask.as(RunResult.Success)
            // Tagging
            case TagAdded(id, _, _, _, targetRev, tag, _, _)                                      =>
              // No information on resource type in tag event, so we try for the different types :'(
              val operations = List(
                resources.tag(IriSegment(id), projectRef, None, tag, targetRev, cRev).toTask,
                schemas.tag(IriSegment(id), projectRef, tag, targetRev, cRev).toTask,
                fileMigration.migrateTag(IriSegment(id), projectRef, tag, targetRev, cRev),
                resolvers.tag(IriSegment(id), projectRef, tag, targetRev, cRev).toTask,
                storageMigration.migrateTag(IriSegment(id), projectRef, tag, targetRev, cRev)
              )

              val tagRejection = MigrationRejection(
                Json
                  .obj("reason" -> Json.fromString(s"Resource/Schema/Resolvers/Storage/File $id could not be tagged."))
              )

              Task.tailRecM(operations) {
                case Nil          => Task.raiseError(tagRejection)
                case head :: Nil  => head.map(_ => Right(successResult))
                case head :: tail => head.map(_ => Right(successResult)).onErrorFallbackTo(IO.pure(Left(tail)))
              }
            case FileCreated(id, _, _, storage, attributes, _, _)                                 =>
              fileMigration.migrate(id, projectRef, None, storage, attributes).as(RunResult.Success)
            case FileUpdated(id, _, _, storage, _, attributes, _, _)                              =>
              fileMigration.migrate(id, projectRef, Some(cRev), storage, attributes).as(RunResult.Success)
            case FileDigestUpdated(id, _, _, _, _, digest, _, _)                                  =>
              fileMigration.fileDigestUpdated(id, projectRef, cRev, digest).as(RunResult.Success)
            case FileAttributesUpdated(id, _, _, _, _, attributes, _, _)                          =>
              fileMigration.fileAttributesUpdated(id, projectRef, cRev, attributes).as(RunResult.Success)
          }
        }
      }
  }

}

object Migration {

  private val logger: Logger = Logger[Migration]

  private val unsconstrained = schemas + "unconstrained.json"

  private val viewTypes    = Set(
    nxv + "View",
    nxv + "AggregateSparqlView",
    nxv + "AggregateElasticSearchView",
    nxv + "ElasticSearchView",
    nxv + "SparqlView",
    nxv + "CompositeView"
  )
  private val storageTypes = Set(nxv + "Storage", nxv + "RemoteDiskStorage", nxv + "DiskStorage", nxv + "S3Storage")

  private val successResult: RunResult = RunResult.Success

  implicit class IOToTask[R: Encoder, A](io: IO[R, A]) {
    def toTask: Task[A] = io.absorbWith(MigrationRejection.apply(_))
  }

  private def replayEvents(config: Config): Task[ReplayMessageEvents] = {
    implicit val as: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "migrationAs", config)
    ReplayMessageEvents(ReplaySettings.from(config), as)(Clock[UIO])
  }

  private def startMigration(migration: Migration, config: Config)(implicit as: ActorSystem[Nothing], sc: Scheduler) = {
    val retryStrategyConfig =
      ConfigSource.fromConfig(config).at("migration.retry-strategy").loadOrThrow[RetryStrategyConfig]
    StreamSupervisor(
      "MigrationStream",
      streamTask = migration.start,
      retryStrategy = RetryStrategy(
        retryStrategyConfig,
        _ => true,
        RetryStrategy.logError(logger, "data migrating")
      ),
      onStreamFinalize = Some(UIO.delay(println("MigrationStream just died :'(")))
    )
  }

  def apply(
      clock: MutableClock,
      uuidF: MutableUUIDF,
      permissions: Permissions,
      acls: Acls,
      realms: Realms,
      projects: Projects,
      organizations: Organizations,
      resources: Resources,
      schemas: Schemas,
      resolvers: Resolvers,
      storageMigration: StoragesMigration,
      fileMigration: FilesMigration,
      cassandraConfig: CassandraConfig
  )(implicit as: ActorSystem[Nothing], s: Scheduler): Task[Migration] = {

    implicit val toMigrateEventDecoder: Decoder[ToMigrateEvent] = Decoder.instance { _ =>
      // We don't care about this value, we just want to be able to restart after a crash
      Right(EmptyEvent)
    }

    val config                    = ConfigFactory.load("migration.conf")
    val persistenceProgressConfig =
      ConfigSource.fromConfig(config).at("migration.projection").loadOrThrow[PersistProgressConfig]

    def throwableToString(t: Throwable): String = t match {
      case MigrationRejection(json) => json.noSpaces                    // Module rejections
      case _                        => Projection.stackTraceAsString(t) // Other errors where the stacktrace may be useful
    }
    for {
      replay     <- replayEvents(config)
      projection <- Projection.cassandra(cassandraConfig, ToMigrateEvent.empty, throwableToString)
      migration   = new Migration(
                      replay,
                      projection,
                      persistenceProgressConfig,
                      clock,
                      uuidF,
                      permissions,
                      acls,
                      realms,
                      projects,
                      organizations,
                      resources,
                      schemas,
                      resolvers,
                      storageMigration,
                      fileMigration
                    )
      _          <- startMigration(migration, config).hideErrors
    } yield migration
  }

}