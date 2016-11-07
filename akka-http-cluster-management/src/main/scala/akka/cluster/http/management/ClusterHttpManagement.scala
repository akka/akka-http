/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.http.management

import akka.actor.AddressFromURIString
import akka.cluster.{ Cluster, Member }
import akka.http.scaladsl.{ ConnectionContext, Http }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Route, RouteResult }
import akka.stream.ActorMaterializer
import spray.json._

import scala.concurrent.Future

final case class ClusterUnreachableMember(node: String, observedBy: Seq[String])
final case class ClusterMember(node: String, nodeUid: String, status: String, roles: Set[String])
final case class ClusterMembers(selfNode: String, members: Set[ClusterMember], unreachable: Seq[ClusterUnreachableMember])
final case class ClusterHttpManagementMessage(message: String)

sealed trait ClusterHttpManagementOperation
case object Down extends ClusterHttpManagementOperation
case object Leave extends ClusterHttpManagementOperation
case object Join extends ClusterHttpManagementOperation

object ClusterHttpManagementOperation {
  def fromString(value: String): Option[ClusterHttpManagementOperation] = {
    Vector(Down, Leave, Join).find(_.toString.equalsIgnoreCase(value))
  }
}

trait ClusterHttpManagementJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val clusterUnreachableMemberFormat = jsonFormat2(ClusterUnreachableMember)
  implicit val clusterMemberFormat = jsonFormat4(ClusterMember)
  implicit val clusterMembersFormat = jsonFormat3(ClusterMembers)
  implicit val clusterMemberMessageFormat = jsonFormat1(ClusterHttpManagementMessage)
}

trait ClusterHttpManagementHelper extends ClusterHttpManagementJsonProtocol {
  def memberToClusterMember(m: Member): ClusterMember = {
    ClusterMember(s"${m.uniqueAddress.address}", s"${m.uniqueAddress.longUid}", s"${m.status}", m.roles)
  }
}

object ClusterHttpManagementRoutes extends ClusterHttpManagementHelper {

  private def routeGetMembers(cluster: Cluster) = {
    get {
      complete {
        val members = cluster.readView.state.members.map(memberToClusterMember)

        val unreachable = cluster.readView.reachability.observersGroupedByUnreachable.toSeq.sortBy(_._1).map {
          case (subject, observers) ⇒
            ClusterUnreachableMember(s"${subject.address}", observers.toSeq.sorted.map(m ⇒ s"${m.address}"))
        }

        ClusterMembers(s"${cluster.readView.selfAddress}", members, unreachable)
      }
    }
  }

  private def routePostMembers(cluster: Cluster) = {
    post {
      formField('address) { addressString ⇒
        complete {
          val address = AddressFromURIString(addressString)
          cluster.join(address)
          ClusterHttpManagementMessage(s"Joining $address")
        }
      }
    }
  }

  private def routeGetMember(cluster: Cluster, member: Member) =
    get {
      complete {
        memberToClusterMember(member)
      }
    }

  private def routeDeleteMember(cluster: Cluster, member: Member) =
    delete {
      complete {
        cluster.leave(member.uniqueAddress.address)
        ClusterHttpManagementMessage(s"Leaving ${member.uniqueAddress.address}")
      }
    }

  private def routePutMember(cluster: Cluster, member: Member) =
    put {
      formField('operation) { operation ⇒
        ClusterHttpManagementOperation.fromString(operation) match {
          case Some(Down) ⇒
            cluster.down(member.uniqueAddress.address)
            complete(ClusterHttpManagementMessage(s"Downing ${member.uniqueAddress.address}"))
          case Some(Leave) ⇒
            cluster.leave(member.uniqueAddress.address)
            complete(ClusterHttpManagementMessage(s"Leaving ${member.uniqueAddress.address}"))
          case _ ⇒
            complete(StatusCodes.BadRequest → ClusterHttpManagementMessage("Operation not supported"))
        }
      }
    }

  private def routesMember(cluster: Cluster) =
    path(Remaining) { memberAddress ⇒
      cluster.readView.members.find(m ⇒ s"${m.uniqueAddress.address}" == memberAddress) match {
        case Some(member) ⇒
          routeGetMember(cluster, member) ~ routeDeleteMember(cluster, member) ~ routePutMember(cluster, member)
        case None ⇒
          complete(StatusCodes.NotFound → ClusterHttpManagementMessage(s"Member [$memberAddress] not found"))
      }
    }

  def apply(cluster: Cluster): Route = apply(cluster, "members")

  def apply(cluster: Cluster, pathPrefixName: String): Route =
    pathPrefix(pathPrefixName) {
      pathEndOrSingleSlash {
        routeGetMembers(cluster) ~ routePostMembers(cluster)
      } ~
        routesMember(cluster)
    }

  def apply(cluster: Cluster, asyncAuthenticator: AsyncAuthenticator[String]): Route = {
    authenticateBasicAsync[String](realm = "secured", asyncAuthenticator) { _ ⇒
      apply(cluster)
    }
  }

  def apply(cluster: Cluster, pathPrefixName: String, asyncAuthenticator: AsyncAuthenticator[String]): Route = {
    authenticateBasicAsync[String](realm = "secured", asyncAuthenticator) { _ ⇒
      apply(cluster, pathPrefixName)
    }
  }
}

object ClusterHttpManagement {
  def apply(cluster: Cluster): ClusterHttpManagement =
    new ClusterHttpManagement(cluster)

  def apply(cluster: Cluster, pathPrefix: String): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, Some(pathPrefix), None, None)

  def apply(cluster: Cluster, asyncAuthenticator: AsyncAuthenticator[String]): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, None, Some(asyncAuthenticator), None)

  def apply(cluster: Cluster, https: ConnectionContext): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, None, None, Some(https))

  def apply(cluster: Cluster, pathPrefix: String, asyncAuthenticator: AsyncAuthenticator[String]): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, Some(pathPrefix), Some(asyncAuthenticator), None)

  def apply(cluster: Cluster, pathPrefix: String, https: ConnectionContext) =
    new ClusterHttpManagement(cluster, Some(pathPrefix), None, Some(https))

  def apply(cluster: Cluster, asyncAuthenticator: AsyncAuthenticator[String], https: ConnectionContext): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, None, Some(asyncAuthenticator), Some(https))

  def apply(cluster: Cluster, pathPrefix: String, asyncAuthenticator: AsyncAuthenticator[String], https: ConnectionContext): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, Some(pathPrefix), Some(asyncAuthenticator), Some(https))

  def create(cluster: Cluster): ClusterHttpManagement =
    new ClusterHttpManagement(cluster)

  def create(cluster: Cluster, pathPrefix: String): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, Some(pathPrefix), None, None)

  def create(cluster: Cluster, asyncAuthenticator: AsyncAuthenticator[String]): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, None, Some(asyncAuthenticator), None)

  def create(cluster: Cluster, https: ConnectionContext): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, None, None, Some(https))

  def create(cluster: Cluster, pathPrefix: String, asyncAuthenticator: AsyncAuthenticator[String]): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, Some(pathPrefix), Some(asyncAuthenticator), None)

  def create(cluster: Cluster, pathPrefix: String, https: ConnectionContext) =
    new ClusterHttpManagement(cluster, Some(pathPrefix), None, Some(https))

  def create(cluster: Cluster, asyncAuthenticator: AsyncAuthenticator[String], https: ConnectionContext): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, None, Some(asyncAuthenticator), Some(https))

  def create(cluster: Cluster, pathPrefix: String, asyncAuthenticator: AsyncAuthenticator[String], https: ConnectionContext): ClusterHttpManagement =
    new ClusterHttpManagement(cluster, Some(pathPrefix), Some(asyncAuthenticator), Some(https))
}

/**
 * Class to instantiate an [[akka.cluster.http.management.ClusterHttpManagement]] to
 * provide an HTTP management interface for [[akka.cluster.Cluster]].
 */
class ClusterHttpManagement(
  cluster:            Cluster,
  pathPrefix:         Option[String]                     = None,
  asyncAuthenticator: Option[AsyncAuthenticator[String]] = None,
  https:              Option[ConnectionContext]          = None) {

  private val settings = new ClusterHttpManagementSettings(cluster.system.settings.config)
  private implicit val system = cluster.system
  private implicit val materializer = ActorMaterializer()
  import system.dispatcher

  private var bindingFuture: Future[Http.ServerBinding] = _

  def start(): Unit = {
    val clusterHttpManagementRoutes = (pathPrefix, asyncAuthenticator) match {
      case (Some(pp), Some(aa)) ⇒ ClusterHttpManagementRoutes(cluster, pp, aa)
      case (Some(pp), None)     ⇒ ClusterHttpManagementRoutes(cluster, pp)
      case (None, Some(aa))     ⇒ ClusterHttpManagementRoutes(cluster, aa)
      case (None, None)         ⇒ ClusterHttpManagementRoutes(cluster)
    }

    val routes = RouteResult.route2HandlerFlow(clusterHttpManagementRoutes)

    https match {
      case Some(context) ⇒
        bindingFuture = Http().bindAndHandle(
          routes,
          settings.clusterHttpManagementHostname,
          settings.clusterHttpManagementPort,
          connectionContext = context)
      case None ⇒
        bindingFuture = Http().bindAndHandle(
          routes,
          settings.clusterHttpManagementHostname,
          settings.clusterHttpManagementPort)
    }
  }

  def stop() = {
    bindingFuture.flatMap(_.unbind())
  }
}

