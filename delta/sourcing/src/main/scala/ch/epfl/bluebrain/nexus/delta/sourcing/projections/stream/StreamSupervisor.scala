package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisorBehavior._
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

/**
  * A [[StreamSupervisor]] that does not keep track of a state.
  */
class StreamSupervisor private[projections] (ref: ActorRef[SupervisorCommand]) {

  /**
    * Stops the stream managed inside the current supervisor
    */
  def stop(): Task[Unit] =
    Task.delay(ref ! Stop())

}

// $COVERAGE-OFF$
object StreamSupervisor {

  /**
    * Runs a stream supervisor as a [[ClusterSingleton]] and ignores its state.
    *
    * @param name            the unique name for the singleton
    * @param streamTask      the embedded stream
    * @param retryStrategy   the strategy when the stream fails
    * @param onStreamFinalize     Additional action when we stop/restart the stream
    */
  def apply[A](
      name: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onStreamFinalize: Option[UIO[Unit]] = None
  )(implicit as: ActorSystem[Nothing], scheduler: Scheduler): Task[StreamSupervisor] =
    Ref.of[Task, Boolean](false).map { ref =>
      val singletonManager = ClusterSingleton(as)
      val behavior         =
        StreamSupervisorBehavior(name, streamTask, retryStrategy, onStreamFinalize.getOrElse(UIO.unit), ref)
      val actorRef         = singletonManager.init {
        SingletonActor(Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart), name)
          .withStopMessage(Stop())
      }
      new StreamSupervisor(actorRef)
    }
}
// $COVERAGE-ON$
