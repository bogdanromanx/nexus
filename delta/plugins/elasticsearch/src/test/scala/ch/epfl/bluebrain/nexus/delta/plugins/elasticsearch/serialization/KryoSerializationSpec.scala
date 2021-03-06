package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.serialization

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.serialization.SerializationExtension
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import com.typesafe.config.ConfigFactory
import io.altoo.akka.serialization.kryo.KryoSerializer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, TryValues}

class KryoSerializationSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load("akka-test.conf"))
    with AnyWordSpecLike
    with Matchers
    with TryValues
    with TestHelpers
    with IOValues
    with EitherValuable
    with CirceLiteral
    with Inspectors {

  private val serialization = SerializationExtension(system)

  private val project = ProjectRef.unsafe("org", "proj")

  private val indexingValue = IndexingElasticSearchViewValue(
    resourceTypes = Set(nxv + "Type1", nxv + "Type2"),
    mapping = json"""{"properties": {"@type": {"type": "keyword"}, "@id": {"type": "keyword"} } }"""
  )
  private val aggValue      = AggregateElasticSearchViewValue(
    NonEmptySet.of(ViewRef(project, nxv + "id1"), ViewRef(project, nxv + "id2"))
  )

  "A ElasticSearchViewValue Kryo serialization" should {
    "succeed" in {
      forAll(List(indexingValue, aggValue)) { view =>
        // Find the Serializer for it
        val serializer = serialization.findSerializerFor(view)
        serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

        // Check serialization/deserialization
        val serialized = serialization.serialize(view)
        serialized.isSuccess shouldEqual true

        val deserialized = serialization.deserialize(serialized.get, view.getClass)
        deserialized.isSuccess shouldEqual true
        deserialized.success.value shouldEqual view
      }
    }
  }
}
