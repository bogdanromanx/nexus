package ch.epfl.bluebrain.nexus.migration.v1_4.events.iam

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent
import io.circe.Json

import java.time.Instant

/**
  * Enumeration of Realm event types.
  */
sealed trait RealmEvent extends ToMigrateEvent {

  /**
    * @return the label of the realm for which this event was emitted
    */
  def id: Label

  /**
    * @return the revision this event generated
    */
  def rev: Long

  /**
    * @return the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject
}

object RealmEvent {

  /**
    * A witness to a realm creation.
    *
    * @param id                    the label of the realm
    * @param rev                   the revision this event generated
    * @param name                  the name of the realm
    * @param openIdConfig          the address of the openid configuration
    * @param issuer                the issuer identifier
    * @param keys                  the collection of keys
    * @param grantTypes            the types of OAuth2 grants supported
    * @param logo                  an optional address for a logo
    * @param authorizationEndpoint the authorization endpoint
    * @param tokenEndpoint         the token endpoint
    * @param userInfoEndpoint      the user info endpoint
    * @param revocationEndpoint    an optional revocation endpoint
    * @param endSessionEndpoint    an optional end session endpoint
    * @param instant               the instant when the event was emitted
    * @param subject               the subject that performed the action that resulted in emitting this event
    */
  final case class RealmCreated(
      id: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      issuer: String,
      keys: Set[Json],
      grantTypes: Set[GrantType],
      logo: Option[Uri],
      authorizationEndpoint: Uri,
      tokenEndpoint: Uri,
      userInfoEndpoint: Uri,
      revocationEndpoint: Option[Uri],
      endSessionEndpoint: Option[Uri],
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  /**
    * A witness to a realm update.
    *
    * @param id                    the label of the realm
    * @param rev                   the revision this event generated
    * @param name                  the name of the realm
    * @param openIdConfig          the address of the openid configuration
    * @param issuer                the issuer identifier
    * @param keys                  the collection of keys
    * @param grantTypes            the types of OAuth2 grants supported
    * @param logo                  an optional address for a logo
    * @param authorizationEndpoint the authorization endpoint
    * @param tokenEndpoint         the token endpoint
    * @param userInfoEndpoint      the user info endpoint
    * @param revocationEndpoint    an optional revocation endpoint
    * @param endSessionEndpoint    an optional end session endpoint
    * @param instant               the instant when the event was emitted
    * @param subject               the subject that performed the action that resulted in emitting this event
    */
  final case class RealmUpdated(
      id: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      issuer: String,
      keys: Set[Json],
      grantTypes: Set[GrantType],
      logo: Option[Uri],
      authorizationEndpoint: Uri,
      tokenEndpoint: Uri,
      userInfoEndpoint: Uri,
      revocationEndpoint: Option[Uri],
      endSessionEndpoint: Option[Uri],
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  /**
    * A witness to a realm deprecation.
    *
    * @param id      the label of the realm
    * @param rev     the revision this event generated
    * @param instant the instant when the event was emitted
    * @param subject the subject that performed the action that resulted in emitting this event
    */
  final case class RealmDeprecated(
      id: Label,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends RealmEvent
}
