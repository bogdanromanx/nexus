package ch.epfl.bluebrain.nexus.delta.rdf.instances

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import org.apache.jena.rdf.model.{Property, RDFNode, Resource}

import java.time.Instant

trait TripleInstances {
  // $COVERAGE-OFF$
  implicit def createSubjectFromIriOrBNode(value: IriOrBNode): Resource = subject(value)
  implicit def createPredicateFromIri(value: Iri): Property             = predicate(value)
  implicit def createObjectFromString(value: String): RDFNode           = obj(value)
  implicit def createObjectFromInt(value: Int): RDFNode                 = obj(value)
  implicit def createObjectFromLong(value: Long): RDFNode               = obj(value)
  implicit def createObjectFromBoolean(value: Boolean): RDFNode         = obj(value)
  implicit def createObjectFromDouble(value: Double): RDFNode           = obj(value)
  implicit def createObjectFromDouble(value: Float): RDFNode            = obj(value)
  implicit def createObjectFromIri(value: IriOrBNode): RDFNode          = obj(value)
  implicit def createObjectFromUri(value: Uri): Resource                = obj(value)
  implicit def createObjectFromInstant(value: Instant): RDFNode         = obj(value)

  // $COVERAGE-ON$
}
