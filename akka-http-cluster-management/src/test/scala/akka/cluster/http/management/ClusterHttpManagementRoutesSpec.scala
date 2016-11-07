/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.http.management

import akka.actor.Address
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster._
import akka.http.scaladsl.model.{ FormData, StatusCodes }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{ Matchers, WordSpecLike }

import scala.collection.immutable._

class ClusterHttpManagementRoutesSpec extends WordSpecLike with Matchers with ScalatestRouteTest with ClusterHttpManagementJsonProtocol {

  "Http Cluster Management Routes" should {
    "return list of members" when {
      "calling GET /members" in {
        val address1 = Address("akka", "Main", "hostname.com", 3311)
        val address2 = Address("akka", "Main", "hostname2.com", 3311)
        val address3 = Address("akka", "Main", "hostname3.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)
        val uniqueAddress2 = UniqueAddress(address2, 2L)

        val clusterMember1 = Member(uniqueAddress1, Set())
        val clusterMember2 = Member(uniqueAddress2, Set())
        val currentClusterState = CurrentClusterState(SortedSet(clusterMember1, clusterMember2))

        val unreachable = Map(
          UniqueAddress(address3, 2L) → Set(uniqueAddress1, uniqueAddress2)
        )

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        val mockedReachability = mock(classOf[Reachability])

        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.state).thenReturn(currentClusterState)
        when(mockedClusterReadView.selfAddress).thenReturn(address1)
        when(mockedClusterReadView.reachability).thenReturn(mockedReachability)
        when(mockedReachability.observersGroupedByUnreachable).thenReturn(unreachable)

        Get("/members") ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          val clusterUnreachableMember = ClusterUnreachableMember("akka://Main@hostname3.com:3311", Seq("akka://Main@hostname.com:3311", "akka://Main@hostname2.com:3311"))
          val clusterMembers = Set(ClusterMember("akka://Main@hostname.com:3311", "1", "Joining", Set()), ClusterMember("akka://Main@hostname2.com:3311", "2", "Joining", Set()))
          responseAs[ClusterMembers] shouldEqual ClusterMembers(s"$address1", clusterMembers, Seq(clusterUnreachableMember))
          status == StatusCodes.OK
        }
      }
    }

    "join a member" when {
      "calling POST /members with form field 'memberAddress'" in {
        val address = "akka.tcp://Main@hostname.com:3311"
        val urlEncodedForm = FormData(Map("address" → address))

        val mockedCluster = mock(classOf[Cluster])
        doNothing().when(mockedCluster).join(any[Address])

        Post("/members/", urlEncodedForm) ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage(s"Joining $address")
          status == StatusCodes.OK
        }
      }
    }

    "return information of a member" when {
      "calling GET /members/akka://Main@hostname.com:3311" in {
        val address = "akka://Main@hostname.com:3311"

        val address1 = Address("akka", "Main", "hostname.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)

        val clusterMember1 = Member(uniqueAddress1, Set())

        val members = SortedSet(clusterMember1)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).leave(any[Address])

        Get(s"/members/$address") ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterMember] shouldEqual ClusterMember("akka://Main@hostname.com:3311", "1", "Joining", Set())
          status == StatusCodes.OK
        }
      }
    }

    "execute leave on a member" when {
      "calling DELETE /members/akka://Main@hostname.com:3311" in {
        val address = "akka://Main@hostname.com:3311"

        val address1 = Address("akka", "Main", "hostname.com", 3311)
        val address2 = Address("akka", "Main", "hostname2.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)
        val uniqueAddress2 = UniqueAddress(address2, 2L)

        val clusterMember1 = Member(uniqueAddress1, Set())
        val clusterMember2 = Member(uniqueAddress2, Set())

        val members = SortedSet(clusterMember1, clusterMember2)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).leave(any[Address])

        Delete(s"/members/$address") ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage(s"Leaving $address")
          status == StatusCodes.OK
        }
      }

      "calling PUT /members/akka://Main@hostname.com:3311 with form field operation LEAVE" in {
        val address = "akka://Main@hostname.com:3311"
        val urlEncodedForm = FormData(Map("operation" → "leave"))

        val address1 = Address("akka", "Main", "hostname.com", 3311)
        val address2 = Address("akka", "Main", "hostname2.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)
        val uniqueAddress2 = UniqueAddress(address2, 2L)

        val clusterMember1 = Member(uniqueAddress1, Set())
        val clusterMember2 = Member(uniqueAddress2, Set())

        val members = SortedSet(clusterMember1, clusterMember2)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).leave(any[Address])

        Put(s"/members/$address", urlEncodedForm) ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage(s"Leaving $address")
          status == StatusCodes.OK
        }
      }

      "does not exist and return Not Found" in {
        val address = "akka://Main2@hostname.com:3311"
        val urlEncodedForm = FormData(Map("operation" → "leave"))

        val address1 = Address("akka", "Main", "hostname.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)

        val clusterMember1 = Member(uniqueAddress1, Set())

        val members = SortedSet(clusterMember1)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).down(any[Address])

        Put(s"/members/$address", urlEncodedForm) ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage(s"Member [$address] not found")
          status == StatusCodes.NotFound
        }
      }
    }

    "execute down on a member" when {
      "calling PUT /members/akka://Main@hostname.com:3311 with form field operation DOWN" in {
        val address = "akka://Main@hostname.com:3311"
        val urlEncodedForm = FormData(Map("operation" → "down"))

        val address1 = Address("akka", "Main", "hostname.com", 3311)
        val address2 = Address("akka", "Main", "hostname2.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)
        val uniqueAddress2 = UniqueAddress(address2, 2L)

        val clusterMember1 = Member(uniqueAddress1, Set())
        val clusterMember2 = Member(uniqueAddress2, Set())

        val members = SortedSet(clusterMember1, clusterMember2)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).down(any[Address])

        Put(s"/members/$address", urlEncodedForm) ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage(s"Downing $address")
          status == StatusCodes.OK
        }
      }

      "does not exist and return Not Found" in {
        val address = "akka://Main2@hostname.com:3311"
        val urlEncodedForm = FormData(Map("operation" → "down"))

        val address1 = Address("akka", "Main", "hostname.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)

        val clusterMember1 = Member(uniqueAddress1, Set())

        val members = SortedSet(clusterMember1)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).down(any[Address])

        Put(s"/members/$address", urlEncodedForm) ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage(s"Member [$address] not found")
          status == StatusCodes.NotFound
        }
      }
    }

    "return not found operation" when {
      "calling PUT /members/akka://Main@hostname.com:3311 with form field operation UNKNOWN" in {
        val address = "akka://Main@hostname.com:3311"
        val urlEncodedForm = FormData(Map("operation" → "unknown"))

        val address1 = Address("akka", "Main", "hostname.com", 3311)

        val uniqueAddress1 = UniqueAddress(address1, 1L)

        val clusterMember1 = Member(uniqueAddress1, Set())

        val members = SortedSet(clusterMember1)

        val mockedCluster = mock(classOf[Cluster])
        val mockedClusterReadView = mock(classOf[ClusterReadView])
        when(mockedCluster.readView).thenReturn(mockedClusterReadView)
        when(mockedClusterReadView.members).thenReturn(members)
        doNothing().when(mockedCluster).down(any[Address])

        Put(s"/members/$address", urlEncodedForm) ~> ClusterHttpManagementRoutes(mockedCluster) ~> check {
          responseAs[ClusterHttpManagementMessage] shouldEqual ClusterHttpManagementMessage("Operation not supported")
          status == StatusCodes.NotFound
        }
      }
    }
  }
}
