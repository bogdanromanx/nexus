package ch.epfl.bluebrain.nexus.migration.replay

import akka.actor
import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import akka.serialization.{Serialization, SerializationExtension}
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sdk.utils.OffsetUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{CastFailedMessage, Message, SuccessMessage}
import ch.epfl.bluebrain.nexus.migration.replay.ReplayMessageEvents.{formatOffset, logger, State}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Stream}
import monix.bio.{Task, UIO}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

/**
  * Allows to replay all events from a materialized view to run migration
  * @param session          the cassandra session
  * @param settings         the replay settings based on the akka ones
  * @param serialization    the akka serialization
  */
final class ReplayMessageEvents private (
    session: CassandraSession,
    settings: ReplaySettings,
    serialization: Serialization
)(implicit clock: Clock[UIO]) {

  private val selectMessages = s"""
      SELECT timestamp, persistence_id, sequence_nr, event, ser_id, ser_manifest FROM ${settings.keyspace}.ordered_messages WHERE
        timebucket = ? AND
        timestamp > ? AND
        timestamp < ?
        ORDER BY timestamp ASC
        LIMIT ${settings.maxBufferSize}
     """.stripMargin

  /**
    * Returns an infinite stream of messages containing events to migrate starting from the given offset
    * @param offset the starting offset
    */
  def run(offset: Offset): Stream[Task, Message[ToMigrateEvent]] = {
    val firstOffset = offsetToUuid(offset)
    val startBucket = TimeBucket(firstOffset, settings.bucketSize)

    logger.info(s"Replaying events with settings: $settings")
    logger.info(s"Starting from offset: ${formatOffset(firstOffset)} at bucket ${startBucket.key}")

    Stream
      .unfoldChunkEval[Task, State, Message[ToMigrateEvent]](State(firstOffset, startBucket, Set.empty)) {
        case State(from, currentBucket, previousBatch) =>
          for {
            now       <- instant
            // Applying the eventual consistency delay
            to         = Uuids.endOf(now.toEpochMilli - settings.eventualConsistency.toMillis)
            // We fetch events from current bucket
            _         <- UIO.delay(logger.info(s"End offset is ${formatOffset(to)}"))
            events    <-
              Task.deferFuture(session.selectAll(selectMessages, currentBucket.key.toString, from, to)).map { rows =>
                parseRows(rows.filterNot { e =>
                  previousBatch.contains((e.getString("persistence_id"), e.getLong("sequence_nr")))
                })
              }
            inPast    <- currentBucket.inPast
            // Move on to the next bucket if all its events have been consumed
            // and it is past and the consistency delay has been respected
            nextBucket = if (events.isEmpty && inPast && !currentBucket.within(to)) {
                           val nextBucket = currentBucket.next()
                           logger.info(s"Switching to bucket: ${nextBucket.key}")
                           nextBucket
                         } else {
                           logger.info(s"Keeping bucket: ${currentBucket.key}")
                           currentBucket
                         }
            nextOffset = events.lastOption.fold(Uuids.startOf(nextBucket.key)) { e => offsetToUuid(e.offset) }
            _         <- UIO.delay(logger.info(s"Next offset is ${formatOffset(nextOffset)}"))
            // If the current bucket is present and if no events have been fetched, we backoff before trying again
            _         <- Task.when(!inPast && events.isEmpty) {
                           UIO.delay(
                             logger.info(s"No results for current bucket, waiting for ${settings.refreshInterval}")
                           ) >> Task
                             .sleep(settings.refreshInterval)
                         }
          } yield Some(
            Chunk.seq(events) -> State(nextOffset, nextBucket, events.map(e => (e.persistenceId, e.sequenceNr)).toSet)
          )
      }
  }

  private def offsetToUuid(offset: Offset): UUID =
    offset match {
      case TimeBasedUUID(uuid) => Uuids.startOf(Uuids.unixTimestamp(uuid))
      case NoOffset            => settings.firstOffset
      case unsupported         =>
        throw new IllegalArgumentException("Cassandra does not support " + unsupported.getClass.getName + " offsets")
    }

  private def parseRows(rows: Seq[Row]): Seq[Message[ToMigrateEvent]] = rows.map { row =>
    val offset        = TimeBasedUUID(row.getUuid("timestamp"))
    val persistenceId = row.getString("persistence_id")
    val sequenceNr    = row.getLong("sequence_nr")
    val eventValue    = serialization.deserializeByteBuffer(
      row.getByteBuffer("event"),
      row.getInt("ser_id"),
      row.getString("ser_manifest")
    )
    eventValue match {
      case toMigrate: ToMigrateEvent =>
        SuccessMessage(
          offset,
          OffsetUtils.toInstant(offset),
          persistenceId,
          sequenceNr,
          toMigrate,
          Vector.empty
        )
      case v                         =>
        CastFailedMessage(
          offset,
          persistenceId,
          sequenceNr,
          "ToMigrateEvent",
          v.getClass.getName
        )
    }
  }
}

object ReplayMessageEvents {

  private val logger: Logger = Logger[ReplayMessageEvents]

  private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS")

  private def formatOffset(uuid: UUID): String = {
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Uuids.unixTimestamp(uuid)), ZoneOffset.UTC)
    s"$uuid (${timestampFormatter.format(time)})"
  }

  final private case class State(uuid: UUID, timeBucket: TimeBucket, previousBatch: Set[(String, Long)])

  /**
    * Creates a [[ReplayMessageEvents]] instance
    * @param settings         the replay settings based on the akka ones
    * @param system           the actor system
    */
  def apply(settings: ReplaySettings, system: ActorSystem[Nothing])(implicit
      clock: Clock[UIO]
  ): Task[ReplayMessageEvents] = {
    implicit val classicSystem: actor.ActorSystem = system.classicSystem
    Task
      .delay {
        CassandraSessionRegistry
          .get(classicSystem)
          .sessionFor(CassandraSessionSettings("akka.persistence.cassandra"))
      }
      .map(
        new ReplayMessageEvents(_, settings, SerializationExtension.get(classicSystem))
      )
  }
}
