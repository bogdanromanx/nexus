package ch.epfl.bluebrain.nexus.delta.plugins.archive.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{MediaRange, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.archive.routes.ArchiveRoutes.metadataMediaRanges
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.mediaTypes
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, AkkaSource, Identities, Projects}
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The Archive routes.
  *
  * @param archives   the archive module
  * @param identities the identities module
  * @param acls       the acls module
  * @param projects   the projects module
  */
class ArchiveRoutes(
    archives: Archives,
    identities: Identities,
    acls: Acls,
    projects: Projects
)(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jko: JsonKeyOrdering, sc: Scheduler)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with DeltaDirectives {

  private val prefix = baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("archives") {
          projectRef(projects).apply { implicit ref =>
            concat(
              // create an archive without an id
              (post & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                operationName(s"$prefix/archives/{org}/{project}") {
                  authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                    emit(Created, archives.create(ref, json).mapValue(_.metadata))
                  }
                }
              },
              (idSegment & pathEndOrSingleSlash) { id =>
                operationName(s"$prefix/archives/{org}/{project}/{id}") {
                  concat(
                    // create an archive with an id
                    (put & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        emit(Created, archives.create(id, ref, json).mapValue(_.metadata))
                      }
                    },
                    // fetch or download an archive
                    (get & pathEndOrSingleSlash) {
                      authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                        headerValueByType(Accept) { accept =>
                          if (accept.mediaRanges.exists(metadataMediaRanges.contains)) emit(archives.fetch(id, ref))
                          else
                            parameter("ignoreNotFound".as[Boolean] ? false) { ignoreNotFound =>
                              emit(archives.download(id, ref, ignoreNotFound).map(sourceToFileResponse))
                            }
                        }
                      }
                    }
                  )
                }
              }
            )
          }
        }
      }
    }

  private def sourceToFileResponse(source: AkkaSource): FileResponse =
    FileResponse("archive.tar", MediaTypes.`application/x-tar`, 0L, source)
}

object ArchiveRoutes {

  // If accept header media range exactly match one of these, we return file metadata,
  // otherwise we return the file content
  val metadataMediaRanges: Set[MediaRange] = mediaTypes.map(_.toContentType.mediaType: MediaRange).toSet
}
