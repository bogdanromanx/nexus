package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioDocker._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioSpec._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}
import software.amazon.awssdk.regions.Region

import java.util.UUID

@DoNotDiscover
class S3StorageLinkFileSpec
    extends TestKit(ActorSystem("S3StorageSaveAndFetchFileSpec"))
    with AnyWordSpecLike
    with AkkaSourceHelpers
    with Matchers
    with IOValues
    with BeforeAndAfterAll {

  implicit private val sc: Scheduler = Scheduler.global

  private val storageValue = S3StorageValue(
    default = false,
    algorithm = DigestAlgorithm.default,
    bucket = "bucket3",
    endpoint = Some(s"http://$VirtualHost:$MinioServicePort"),
    accessKey = Some(Secret(AccessKey)),
    secretKey = Some(Secret(SecretKey)),
    region = Some(Region.EU_CENTRAL_1),
    readPermission = read,
    writePermission = write,
    maxFileSize = 20
  )

  override protected def beforeAll(): Unit =
    createBucket(storageValue).hideErrors.accepted

  override protected def afterAll(): Unit =
    deleteBucket(storageValue).hideErrors.accepted

  "S3Storage linking operations" should {
    val iri = iri"http://localhost/s3"

    val uuid     = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val project  = ProjectRef.unsafe("org", "project")
    val filename = "myfile.txt"

    val storage = S3Storage(iri, project, storageValue, Map.empty, Secret(Json.obj()))
    val content = "file content"
    val source  = Source(content.map(c => ByteString(c.toString)))

    val digest     =
      ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")
    val attributes = FileAttributes(
      uuid,
      s"http://bucket3.$VirtualHost:$MinioServicePort/org/project/8/0/4/9/b/a/9/0/myfile.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
      "myfile.txt",
      `text/plain(UTF-8)`,
      12,
      digest,
      Client
    )

    val linkAttributes = attributes.copy(origin = FileAttributesOrigin.Storage)
    val description    = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))

    "succeed" in {
      storage.saveFile.apply(description, source).accepted shouldEqual attributes

      storage.linkFile.apply(attributes.path, description).accepted shouldEqual linkAttributes
    }

    "fail linking a file that does not exist" in {
      storage.linkFile.apply(Uri.Path("my/file-40.txt"), description).rejectedWith[FileNotFound]
    }

  }
}
