package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, projects => projectsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.AnyOrganizationAnyProject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectFields, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProjectResource, Projects}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The project routes
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  */
final class ProjectsRoutes(identities: Identities, acls: Acls, projects: Projects)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  private def projectsSearchParams(implicit caller: Caller): Directive1[ProjectSearchParams] =
    parameter("label".as[Label].?).flatMap { organization =>
      searchParams.tflatMap { case (deprecated, rev, createdBy, updatedBy) =>
        onSuccess(acls.listSelf(AnyOrganizationAnyProject(true)).runToFuture).map { aclsCol =>
          ProjectSearchParams(
            organization,
            deprecated,
            rev,
            createdBy,
            updatedBy,
            proj => aclsCol.exists(caller.identities, projectsPermissions.read, AclAddress.Project(proj.ref))
          )
        }
      }
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("projects") {
          concat(
            // List projects
            (get & pathEndOrSingleSlash & extractUri & fromPaginated & projectsSearchParams & sort[Project]) {
              (uri, pagination, params, order) =>
                operationName(s"$prefixSegment/projects") {
                  implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                    searchResultsJsonLdEncoder(Project.context, pagination, uri)

                  emit(projects.list(pagination, params, order).widen[SearchResults[ProjectResource]])
                }
            },
            // SSE projects
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/projects/events") {
                authorizeFor(AclAddress.Root, events.read).apply {
                  lastEventId { offset =>
                    emit(projects.events(offset))
                  }
                }
              }
            },
            (projectRef(projects) & pathEndOrSingleSlash) { ref =>
              operationName(s"$prefixSegment/projects/{org}/{project}") {
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case None      =>
                        // Create project
                        authorizeFor(AclAddress.Project(ref), projectsPermissions.create).apply {
                          entity(as[ProjectFields]) { fields =>
                            emit(StatusCodes.Created, projects.create(ref, fields).mapValue(_.metadata))
                          }
                        }
                      case Some(rev) =>
                        // Update project
                        authorizeFor(AclAddress.Project(ref), projectsPermissions.write).apply {
                          entity(as[ProjectFields]) { fields =>
                            emit(projects.update(ref, rev, fields).mapValue(_.metadata))
                          }
                        }
                    }
                  },
                  get {
                    authorizeFor(AclAddress.Project(ref), projectsPermissions.read).apply {
                      parameter("rev".as[Long].?) {
                        case Some(rev) => // Fetch project at specific revision
                          emit(projects.fetchAt(ref, rev).leftWiden[ProjectRejection])
                        case None      => // Fetch project
                          emit(projects.fetch(ref).leftWiden[ProjectRejection])
                      }
                    }
                  },
                  // Deprecate project
                  delete {
                    authorizeFor(AclAddress.Project(ref), projectsPermissions.write).apply {
                      parameter("rev".as[Long]) { rev => emit(projects.deprecate(ref, rev).mapValue(_.metadata)) }
                    }
                  }
                )
              }
            },
            // list projects for an organization
            (get & label & pathEndOrSingleSlash & extractUri & fromPaginated & projectsSearchParams & sort[Project]) {
              (organization, uri, pagination, params, order) =>
                implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                  searchResultsJsonLdEncoder(Project.context, pagination, uri)

                val filter = params.copy(organization = Some(organization))
                emit(projects.list(pagination, filter, order).widen[SearchResults[ProjectResource]])
            }
          )
        }
      }
    }
}

object ProjectsRoutes {

  /**
    * @return the [[Route]] for projects
    */
  def apply(identities: Identities, acls: Acls, projects: Projects)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ProjectsRoutes(identities, acls, projects).routes

}
