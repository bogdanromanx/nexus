package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives._
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileEventExchange, FileReferenceExchange, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageEventExchange, StorageReferenceExchange, Storages}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, MetadataContextValue, ResourceToSchemaMappings}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.migration.{FilesMigration, StoragesMigration}
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Storages and Files wiring
  */
class StoragePluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader = getClass.getClassLoader

  make[StoragePluginConfig].fromEffect { cfg: Config => StoragePluginConfig.load(cfg) }

  make[StorageTypeConfig].from { cfg: StoragePluginConfig => cfg.storages.storageTypeConfig }

  make[EventLog[Envelope[StorageEvent]]].fromEffect { databaseEventLog[StorageEvent](_, _) }

  make[HttpClient].named("storage").from { (cfg: StoragePluginConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    def defaultHttpClientConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
    HttpClient()(
      cfg.storages.storageTypeConfig.remoteDisk.fold(defaultHttpClientConfig)(_.client),
      as.classicSystem,
      sc
    )
  }

  make[Storages]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          log: EventLog[Envelope[StorageEvent]],
          client: HttpClient @Id("storage"),
          permissions: Permissions,
          orgs: Organizations,
          projects: Projects,
          clock: Clock[UIO],
          uuidF: UUIDF,
          contextResolution: ResolverContextResolution,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        Storages(cfg.storages, log, contextResolution, permissions, orgs, projects)(client, uuidF, clock, scheduler, as)
    }
    .aliased[StoragesMigration]

  make[Crypto].from { (cfg: StoragePluginConfig) =>
    cfg.storages.storageTypeConfig.encryption.crypto
  }

  make[StoragesRoutes].from {
    (
        cfg: StoragePluginConfig,
        crypto: Crypto,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        storages: Storages,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      {
        val paginationConfig: PaginationConfig = cfg.storages.pagination
        new StoragesRoutes(identities, acls, organizations, projects, storages)(
          baseUri,
          crypto,
          paginationConfig,
          s,
          cr,
          ordering
        )
      }
  }

  make[EventLog[Envelope[FileEvent]]].fromEffect { databaseEventLog[FileEvent](_, _) }

  make[Files]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          log: EventLog[Envelope[FileEvent]],
          client: HttpClient @Id("storage"),
          acls: Acls,
          orgs: Organizations,
          projects: Projects,
          storages: Storages,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        Files(cfg.files, log, acls, orgs, projects, storages)(client, uuidF, clock, scheduler, as)
    }
    .aliased[FilesMigration]

  make[FilesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        files: Files,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      val storageConfig = cfg.storages.storageTypeConfig
      new FilesRoutes(identities, acls, organizations, projects, files)(baseUri, storageConfig, s, cr, ordering)
  }

  many[ServiceDependency].addSet {
    (cfg: StorageTypeConfig, client: HttpClient @Id("storage"), as: ActorSystem[Nothing]) =>
      val remoteStorageClient = cfg.remoteDisk.map { r =>
        new RemoteDiskStorageClient(r.defaultEndpoint)(client, as.classicSystem)
      }
      remoteStorageClient.fold(Set.empty[RemoteStorageServiceDependency])(client =>
        Set(new RemoteStorageServiceDependency(client))
      )
  }

  make[StorageScopeInitialization]
  many[ScopeInitialization].ref[StorageScopeInitialization]

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/storages-metadata.json"))

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/files.json"))

  many[RemoteContextResolution].addEffect {
    for {
      storageCtx     <- ContextValue.fromFile("contexts/storages.json")
      storageMetaCtx <- ContextValue.fromFile("contexts/storages-metadata.json")
      fileCtx        <- ContextValue.fromFile("contexts/files.json")
    } yield RemoteContextResolution.fixed(
      storageCtxId     -> storageCtx,
      storageMetaCtxId -> storageMetaCtx,
      fileCtxId        -> fileCtx
    )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("storages") -> storagesSchemaId, Label.unsafe("files") -> filesSchemaId)
  )

  many[ApiMappings].add(Storages.mappings + Files.mappings)

  many[PriorityRoute].add { (storagesRoutes: StoragesRoutes, fileRoutes: FilesRoutes) =>
    PriorityRoute(priority, concat(storagesRoutes.routes, fileRoutes.routes))
  }

  make[StorageReferenceExchange]
  make[FileReferenceExchange]
  many[ReferenceExchange].ref[StorageReferenceExchange].ref[FileReferenceExchange]

  make[StorageEventExchange]
  make[FileEventExchange]
  many[EventExchange].ref[StorageEventExchange].ref[FileEventExchange]
}
