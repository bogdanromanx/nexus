package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, TaskSyntax}

package object syntax
    extends JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with IterableSyntax
    with UriSyntax
    with PathSyntax
    with ClassTagSyntax
    with TaskSyntax
