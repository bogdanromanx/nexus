package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.delta.service.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsImpl._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class RealmsImpl private (
    agg: RealmsAggregate,
    eventLog: EventLog[Envelope[RealmEvent]],
    index: RealmsCache
) extends Realms {

  private val component: String = "realms"

  override def create(label: Label, name: Name, openIdConfig: Uri, logo: Option[Uri])(implicit
      caller: Identity.Subject
  ): IO[RealmRejection, RealmResource] = {
    val command = CreateRealm(label, name, openIdConfig, logo, caller)
    eval(command).named("createRealm", component)
  }

  override def update(label: Label, rev: Long, name: Name, openIdConfig: Uri, logo: Option[Uri])(implicit
      caller: Identity.Subject
  ): IO[RealmRejection, RealmResource] = {
    val command = UpdateRealm(label, rev, name, openIdConfig, logo, caller)
    eval(command).named("updateRealm", component)
  }

  override def deprecate(label: Label, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller)).named("deprecateRealm", component)

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.label.value, cmd).mapError(_.value)
      resource         <- IO.fromOption(
                            evaluationResult.state.toResource,
                            UnexpectedInitialState(cmd.label)
                          )
      _                <- index.put(cmd.label, resource)
    } yield resource

  override def fetch(label: Label): UIO[Option[RealmResource]] =
    agg.state(label.value).map(_.toResource).named("fetchRealm", component)

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.RevisionNotFound, Option[RealmResource]] =
    if (rev == 0L) UIO.pure(None).named("fetchRealmAt", component, Map("rev" -> rev))
    else {
      eventLog
        .currentEventsByPersistenceId(s"$entityType-$label", Long.MinValue, Long.MaxValue)
        .takeWhile(_.event.rev <= rev)
        .fold[RealmState](RealmState.Initial) { case (state, event) =>
          Realms.next(state, event.event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource)
          case Some(_)                         =>
            fetch(label).flatMap {
              case Some(res) => IO.raiseError(RevisionNotFound(rev, res.rev))
              case None      => IO.pure(None)
            }
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }
        .named("fetchRealmAt", component, Map("rev" -> rev))
    }

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.RealmSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[RealmResource]] =
    index.values
      .map { resources =>
        val results = resources.filter(params.matches).toVector.sortBy(_.createdAt)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listRealms", component)

  override def events(offset: Offset = NoOffset): Stream[Task, Envelope[RealmEvent]] =
    eventLog.eventsByTag(entityType, offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[RealmEvent]] =
    eventLog.currentEventsByTag(entityType, offset)

}

object RealmsImpl {

  type RealmsAggregate = Aggregate[String, RealmState, RealmCommand, RealmEvent, RealmRejection]

  type RealmsCache = KeyValueStore[Label, RealmResource]

  /**
    * The realms entity type.
    */
  final val entityType: String = "realms"

  /**
    * The realms tag name.
    */
  final val realmTag = "realm"

  private val logger: Logger = Logger[RealmsImpl]

  private def index(realmsConfig: RealmsConfig)(implicit as: ActorSystem[Nothing]): RealmsCache = {
    implicit val cfg: KeyValueStoreConfig    = realmsConfig.keyValueStore
    val clock: (Long, RealmResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed("realms", clock)
  }

  private def startIndexing(
      config: RealmsConfig,
      eventLog: EventLog[Envelope[RealmEvent]],
      index: RealmsCache,
      realms: Realms
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "RealmsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(realmTag, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            realms.fetch(envelope.event.label).flatMap {
              case Some(realm) => index.put(realm.id, realm)
              case None        => UIO.unit
            }
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "realms indexing")
      )
    )

  private def aggregate(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      existingRealms: UIO[Set[RealmResource]],
      realmsConfig: RealmsConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[RealmsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = entityType,
      initialState = RealmState.Initial,
      next = Realms.next,
      evaluate = Realms.evaluate(resolveWellKnown, existingRealms),
      tagger = (_: RealmEvent) => Set(entityType),
      snapshotStrategy = SnapshotStrategy.SnapshotCombined(
        SnapshotStrategy.SnapshotPredicate((state: RealmState, _: RealmEvent, _: Long) => state.deprecated),
        SnapshotStrategy.SnapshotEvery(
          numberOfEvents = 500,
          keepNSnapshots = 1,
          deleteEventsOnSnapshot = false
        )
      )
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = realmsConfig.aggregate,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def apply(
      agg: RealmsAggregate,
      eventLog: EventLog[Envelope[RealmEvent]],
      index: RealmsCache
  ): RealmsImpl =
    new RealmsImpl(agg, eventLog, index)

  /**
    * Constructs a [[Realms]] instance
    *
    * @param realmsConfig     the realm configuration
    * @param resolveWellKnown how to resolve the [[WellKnown]]
    * @param eventLog         the event log for [[RealmEvent]]
    */
  final def apply(
      realmsConfig: RealmsConfig,
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      eventLog: EventLog[Envelope[RealmEvent]]
  )(implicit as: ActorSystem[Nothing], sc: Scheduler, clock: Clock[UIO]): UIO[Realms] = {
    val i = index(realmsConfig)
    for {
      agg   <- aggregate(resolveWellKnown, i.values, realmsConfig)
      realms = apply(agg, eventLog, i)
      _     <- UIO.delay(startIndexing(realmsConfig, eventLog, i, realms))
    } yield realms
  }

}