package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.{CompositeViewProjectionId, SourceProjectionId, ViewProjectionId}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.instances._

import java.time.Instant
import scala.math.Ordering.Implicits._

/**
  * Progression progress for a given view
  *
  * @param offset    the offset which has been reached
  * @param timestamp the time when the value A was created
  * @param processed the number of processed messages
  * @param discarded the number of discarded messages
  * @param warnings  the number of warning messages
  * @param failed    the number of failed messages
  */
final case class ProjectionProgress[A](
    offset: Offset,
    timestamp: Instant,
    processed: Long,
    discarded: Long,
    warnings: Long,
    failed: Long,
    value: A
) {

  /**
    * Takes a new message in account for the progress
    */
  def +(message: Message[A]): ProjectionProgress[A] =
    message match {
      case m: DiscardedMessage  =>
        copy(offset = m.offset, timestamp = m.timestamp, processed = processed + 1, discarded = discarded + 1)
      case m: ErrorMessage      =>
        copy(offset = m.offset, timestamp = timestampOrCurrent(m), processed = processed + 1, failed = failed + 1)
      case s: SuccessMessage[A] =>
        copy(
          timestamp = timestamp.max(s.timestamp),
          offset = s.offset.max(offset),
          warnings = warnings + s.warnings.size,
          processed = processed + 1,
          value = s.value
        )
    }

  private def timestampOrCurrent(message: ErrorMessage): Instant =
    message match {
      case m: FailureMessage[_] => m.timestamp
      case _: CastFailedMessage => timestamp
    }
}

/**
  * Projection for a composite view
  * @param id id
  * @param sourceProgress progress for the different sources
  * @param viewProgress progress for the different views
  */
final case class CompositeProjectionProgress[A](
    id: ViewProjectionId,
    sourceProgress: Map[SourceProjectionId, ProjectionProgress[A]],
    viewProgress: Map[CompositeViewProjectionId, ProjectionProgress[A]]
)

object ProjectionProgress {

  /**
    * When no progress has been done yet for a type ''A''
    */
  def NoProgress[A](empty: => A): ProjectionProgress[A] =
    ProjectionProgress(NoOffset, Instant.EPOCH, 0L, 0L, 0L, 0L, empty)

  /**
    * When no progress has been done yet for a type Unit
    */
  val NoProgress: ProjectionProgress[Unit] = NoProgress(())

  /**
    * Creates a [[ProjectionProgress]] of Unit
    */
  final def apply(
      offset: Offset,
      timestamp: Instant,
      processed: Long,
      discarded: Long,
      warnings: Long,
      failed: Long
  ): ProjectionProgress[Unit] =
    ProjectionProgress(offset, timestamp, processed, discarded, warnings, failed, ())

}
