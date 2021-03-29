package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.EvaluationError
import io.circe.Json

/**
  * Enumeration of composite view rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class CompositeViewRejection(val reason: String) extends Product with Serializable

object CompositeViewRejection {

  /**
    * Rejection returned when attempting to create a view with an id that already exists.
    *
    * @param id the view id
    */
  final case class ViewAlreadyExists(id: Iri, project: ProjectRef)
      extends CompositeViewRejection(s"Composite view '$id' already exists in project '$project'.")

  /**
    * Rejection returned when a view that doesn't exist.
    *
    * @param id the view id
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends CompositeViewRejection(s"Composite view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id the view id
    */
  final case class ViewIsDeprecated(id: Iri) extends CompositeViewRejection(s"Composite view '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends CompositeViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends CompositeViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when too many sources are specified.
    *
    * @param provided the number of sources specified
    * @param max      the maximum number of sources
    */
  final case class TooManySources(provided: Int, max: Int)
      extends CompositeViewRejection(
        s"$provided exceeds the maximum allowed number of sources($max)."
      )

  /**
    * Rejection returned when too many projections are specified.
    *
    * @param provided the number of projections specified
    * @param max      the maximum number of projections
    */
  final case class TooManyProjections(provided: Int, max: Int)
      extends CompositeViewRejection(
        s"$provided exceeds the maximum allowed number of projections($max)."
      )

  /**
    * Rejection returned when there are duplicate ids in sources or projections.
    *
    * @param ids the ids provided
    */
  final case class DuplicateIds(ids: Seq[Iri])
      extends CompositeViewRejection(
        s"The ids of projection or source contain a duplicate. Ids provided: ${ids.mkString(",")}"
      )

  /**
    * Rejection signalling that a source is invalid.
    */
  sealed abstract class CompositeViewSourceRejection(reason: String) extends CompositeViewRejection(reason)

  /**
    * Rejection returned when the project for a [[CrossProjectSource]] does not exist.
    */
  final case class CrossProjectSourceProjectNotFound(crossProjectSource: CrossProjectSource)
      extends CompositeViewSourceRejection(
        s"Project ${crossProjectSource.project} does not exist for 'CrossProjectSource' ${crossProjectSource.id}"
      )

  /**
    * Rejection returned when the identities for a [[CrossProjectSource]] don't have access to target project.
    */
  final case class CrossProjectSourceForbidden(crossProjectSource: CrossProjectSource)(implicit val baseUri: BaseUri)
      extends CompositeViewSourceRejection(
        s"None of the identities  ${crossProjectSource.identities.map(_.id).mkString(",")} has permissions for project ${crossProjectSource.project}"
      )

  /**
    * Rejection returned when [[RemoteProjectSource]] is invalid.
    */
  final case class InvalidRemoteProjectSource(remoteProjectSource: RemoteProjectSource)
      extends CompositeViewSourceRejection(
        s"RemoteProjectSource ${remoteProjectSource.tpe} is invalid: either provided endpoint ${remoteProjectSource.endpoint} is invalid or there are insufficient permissions to access this endpoint. "
      )

  /**
    * Rejection signalling that a projection is invalid.
    */
  sealed abstract class CompositeViewProjectionRejection(reason: String) extends CompositeViewRejection(reason)

  /**
    * Rejection returned when the provided ElasticSearch mapping for an ElasticSearchProjection is invalid.
    */
  final case class InvalidElasticSearchProjectionPayload(details: Option[Json])
      extends CompositeViewProjectionRejection(
        "The provided ElasticSearch mapping value is invalid."
      )

  /**
    * Signals a rejection caused by an attempt to create or update an composite view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends CompositeViewProjectionRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class CompositeViewEvaluationError(err: EvaluationError)
      extends CompositeViewRejection("Unexpected evaluation error")

  /**
    * Rejection returned when attempting to interact with a composite while providing an id that cannot be
    * resolved to an Iri.
    *
    * @param id the view identifier
    */
  final case class InvalidCompositeViewId(id: String)
      extends CompositeViewRejection(s"Composite view identifier '$id' cannot be expanded to an Iri.")

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends CompositeViewRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends CompositeViewRejection(rejection.reason)

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends CompositeViewRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when the returned state is the initial state after a successful command evaluation.
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a
    * non initial state.
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends CompositeViewRejection(s"Unexpected initial state for composite view '$id' of project '$project'.")

  /**
    * Rejection returned when attempting to create a composite view where the passed id does not match the id on the
    * source json document.
    *
    * @param id       the view identifier
    * @param sourceId the view identifier in the source json document
    */
  final case class UnexpectedCompositeViewId(id: Iri, sourceId: Iri)
      extends CompositeViewRejection(
        s"The provided composite view '$id' does not match the id '$sourceId' in the source document."
      )

  /**
    * Signals an error converting the source Json document to a JsonLD document.
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends CompositeViewRejection(
        s"The provided composite view JSON document ${id.fold("")(id => s"with id '$id'")} cannot be interpreted as a JSON-LD document."
      )

  /**
    * Rejection when attempting to decode an expanded JsonLD as a [[CompositeViewValue]].
    *
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends CompositeViewRejection(error.getMessage)

  implicit final val projectToElasticSearchRejectionMapper: Mapper[ProjectRejection, CompositeViewRejection] =
    WrappedProjectRejection.apply

  implicit val orgToElasticSearchRejectionMapper: Mapper[OrganizationRejection, CompositeViewRejection] =
    WrappedOrganizationRejection.apply

  implicit final val evaluationErrorMapper: Mapper[EvaluationError, CompositeViewRejection] =
    CompositeViewEvaluationError.apply

  implicit val jsonLdRejectionMapper: Mapper[JsonLdRejection, CompositeViewRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedCompositeViewId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }
}