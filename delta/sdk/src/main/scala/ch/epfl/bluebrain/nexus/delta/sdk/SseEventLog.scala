package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.Offset
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import fs2.Stream
import monix.bio.{IO, Task}

/**
  * An event log that reads events from a [[Stream]] and transforms each event to JSON-LD in preparation for consumption by SSE routes
  */
trait SseEventLog {

  /**
    * Get stream of all events as ''T''.
    *
    * @param offset the offset to start from
    */
  def stream(offset: Offset): Stream[Task, Envelope[JsonLdValue]]

  /**
    * Get stream of events inside an organization as ''T'', transforming the error from ''OrganizationRejection'' to ''R''.
    *
    * @param org    the organization label
    * @param offset the offset to start from
    */
  def stream[R](
      org: Label,
      offset: Offset
  )(implicit mapper: Mapper[OrganizationRejection, R]): IO[R, Stream[Task, Envelope[JsonLdValue]]]

  /**
    * Get stream of events inside a project as ''T'', transforming the error from ''ProjectRejection'' to ''R''.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    */
  def stream[R](
      project: ProjectRef,
      offset: Offset
  )(implicit mapper: Mapper[ProjectRejection, R]): IO[R, Stream[Task, Envelope[JsonLdValue]]]
}

object SseEventLog {

  /**
    * An event log that reads events from a [[Stream]] and transforms each event to JSON-LD that is available through ''exchanges''.
    * The JSON-LD events are then used for consumption by SSE routes
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      orgs: Organizations,
      projects: Projects,
      exchanges: Set[EventExchange]
  ): SseEventLog = new SseEventLog {

    private lazy val exchangesList = exchanges.toList

    def stream(offset: Offset): Stream[Task, Envelope[JsonLdValue]] =
      exchange(eventLog.eventsByTag(Event.eventTag, offset))

    def stream[R](
        org: Label,
        offset: Offset
    )(implicit mapper: Mapper[OrganizationRejection, R]): IO[R, Stream[Task, Envelope[JsonLdValue]]] =
      orgs.fetch(org).as(exchange(eventLog.eventsByTag(Organizations.orgTag(org), offset))).mapError(mapper.to)

    def stream[R](
        project: ProjectRef,
        offset: Offset
    )(implicit mapper: Mapper[ProjectRejection, R]): IO[R, Stream[Task, Envelope[JsonLdValue]]] =
      projects
        .fetch(project)
        .as(exchange(eventLog.eventsByTag(Projects.projectTag(project), offset)))
        .mapError(mapper.to)

    private def exchange(stream: Stream[Task, Envelope[Event]]): Stream[Task, Envelope[JsonLdValue]] =
      stream
        .map { envelope =>
          exchangesList.tailRecM[Option, Envelope[JsonLdValue]] {
            case Nil              => None
            case exchange :: rest =>
              exchange.toJsonLdEvent(envelope.event) match {
                case Some(jsonld) => Some(Right(envelope.as(jsonld)))
                case None         => Some(Left(rest))
              }
          }
        }
        .collect { case Some(envelope) => envelope }
  }
}
