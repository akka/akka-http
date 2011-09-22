
.. _multi-jvm-testing:

###################
 Multi-JVM Testing
###################

Support for running applications (objects with main methods) and
ScalaTest tests in multiple JVMs.

.. contents:: :local:


Setup
=====

The multi-JVM testing is an sbt plugin that you can find here:

http://github.com/typesafehub/sbt-multi-jvm

You can add it as a plugin by adding the following to your plugins/build.sbt::

   resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"

   libraryDependencies += "com.typesafe.sbt-multi-jvm" %% "sbt-multi-jvm" % "0.1.3"

You can then add multi-JVM testing to a project by including the ``MultiJvm``
settings and config. For example, here is how the akka-cluster project adds
multi-JVM testing::

   import MultiJvmPlugin.{ MultiJvm, extraOptions }

   lazy val cluster = Project(
     id = "akka-cluster",
     base = file("akka-cluster"),
     settings = defaultSettings ++ MultiJvmPlugin.settings ++ Seq(
       extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
         (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
       },
       test in Test <<= (test in Test) dependsOn (test in MultiJvm)
     )
   ) configs (MultiJvm)

You can specify JVM options for the forked JVMs::

    jvmOptions in MultiJvm := Seq("-Xmx256M")


Running tests
=============

The multi-jvm tasks are similar to the normal tasks: ``test``, ``test-only``,
and ``run``, but are under the ``multi-jvm`` configuration.

So in Akka, to run all the multi-JVM tests in the akka-cluster project use (at
the sbt prompt):

.. code-block:: none

   akka-cluster/multi-jvm:test

Or one can change to the ``akka-cluster`` project first, and then run the
tests:

.. code-block:: none

   project akka-cluster
   multi-jvm:test

To run individual tests use ``test-only``:

.. code-block:: none

   multi-jvm:test-only akka.cluster.deployment.Deployment

More than one test name can be listed to run multiple specific
tests. Tab-completion in sbt makes it easy to complete the test names.

It's also possible to specify JVM options with ``test-only`` by including those
options after the test names and ``--``. For example:

.. code-block:: none

    multi-jvm:test-only akka.cluster.deployment.Deployment -- -Dsome.option=something


Creating application tests
==========================

The tests are discovered, and combined, through a naming convention. A test is
named with the following pattern:

.. code-block:: none

    {TestName}MultiJvm{NodeName}

That is, each test has ``MultiJvm`` in the middle of its name. The part before
it groups together tests/applications under a single ``TestName`` that will run
together. The part after, the ``NodeName``, is a distinguishing name for each
forked JVM.

So to create a 3-node test called ``Sample``, you can create three applications
like the following::

    package sample

    object SampleMultiJvmNode1 {
      def main(args: Array[String]) {
        println("Hello from node 1")
      }
    }

    object SampleMultiJvmNode2 {
      def main(args: Array[String]) {
        println("Hello from node 2")
      }
    }

    object SampleMultiJvmNode3 {
      def main(args: Array[String]) {
        println("Hello from node 3")
      }
    }

When you call ``multi-jvm:run sample.Sample`` at the sbt prompt, three JVMs will be
spawned, one for each node. It will look like this:

.. code-block:: none

    > multi-jvm:run sample.Sample
    ...
    [info] Starting JVM-Node1 for sample.SampleMultiJvmNode1
    [info] Starting JVM-Node2 for sample.SampleMultiJvmNode2
    [info] Starting JVM-Node3 for sample.SampleMultiJvmNode3
    [JVM-Node1] Hello from node 1
    [JVM-Node2] Hello from node 2
    [JVM-Node3] Hello from node 3
    [success] Total time: ...


Naming
======

You can change what the ``MultiJvm`` identifier is. For example, to change it to
``ClusterTest`` use the ``multiJvmMarker`` setting::

   multiJvmMarker in MultiJvm := "ClusterTest"

Your tests should now be named ``{TestName}ClusterTest{NodeName}``.


Configuration of the JVM instances
==================================

Setting JVM options
-------------------

You can define specific JVM options for each of the spawned JVMs. You do that by creating
a file named after the node in the test with suffix ``.opts`` and put them in the same
directory as the test.

For example, to feed the JVM options ``-Dakka.cluster.nodename=node1`` and
``-Dakka.remote.port=9991`` to the ``SampleMultiJvmNode1`` let's create three ``*.opts`` files
and add the options to them.

``SampleMultiJvmNode1.opts``::

    -Dakka.cluster.nodename=node1 -Dakka.remote.port=9991

``SampleMultiJvmNode2.opts``::

    -Dakka.cluster.nodename=node2 -Dakka.remote.port=9992

``SampleMultiJvmNode3.opts``::

    -Dakka.cluster.nodename=node3 -Dakka.remote.port=9993


Overriding akka.conf options
----------------------------

You can also override the options in the ``akka.conf`` file with different options for each
spawned JVM. You do that by creating a file named after the node in the test with suffix
``.conf`` and put them in the same  directory as the test .

For example, to override the configuration option ``akka.cluster.name`` let's create three
``*.conf`` files and add the option to them.

``SampleMultiJvmNode1.conf``::

    akka.cluster.name = "test-cluster"

``SampleMultiJvmNode2.conf``::

    akka.cluster.name = "test-cluster"

``SampleMultiJvmNode3.conf``::

    akka.cluster.name = "test-cluster"


ScalaTest
=========

There is also support for creating ScalaTest tests rather than applications. To
do this use the same naming convention as above, but create ScalaTest suites
rather than objects with main methods. You need to have ScalaTest on the
classpath. Here is a similar example to the one above but using ScalaTest::

    package sample

    import org.scalatest.WordSpec
    import org.scalatest.matchers.MustMatchers

    class SpecMultiJvmNode1 extends WordSpec with MustMatchers {
      "A node" should {
        "be able to say hello" in {
          val message = "Hello from node 1"
          message must be("Hello from node 1")
        }
      }
    }

    class SpecMultiJvmNode2 extends WordSpec with MustMatchers {
      "A node" should {
        "be able to say hello" in {
          val message = "Hello from node 2"
          message must be("Hello from node 2")
        }
      }
    }

To run just these tests you would call ``multi-jvm:test-only sample.Spec`` at
the sbt prompt.


ZookeeperBarrier
================

When running multi-JVM tests it's common to need to coordinate timing across
nodes. To do this there is a ZooKeeper-based double-barrier (there is both an
entry barrier and an exit barrier). ClusterNodes also have support for creating
barriers easily. To wait at the entry use the ``enter`` method. To wait at the
exit use the ``leave`` method. It's also possible t pass a block of code which
will be run between the barriers.

When creating a barrier you pass it a name and the number of nodes that are
expected to arrive at the barrier. You can also pass a timeout. The default
timeout is 60 seconds.

Here is an example of coordinating the starting of two nodes and then running
something in coordination::

    package sample

    import org.scalatest.WordSpec
    import org.scalatest.matchers.MustMatchers
    import org.scalatest.BeforeAndAfterAll

    import akka.cluster._

    object SampleMultiJvmSpec {
      val NrOfNodes = 2
    }

    class SampleMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
      import SampleMultiJvmSpec._

      override def beforeAll() = {
        Cluster.startLocalCluster()
      }

      override def afterAll() = {
        Cluster.shutdownLocalCluster()
      }

      "A cluster" must {

        "have jvm options" in {
          System.getProperty("akka.cluster.nodename", "") must be("node1")
          System.getProperty("akka.remote.port", "") must be("9991")
          akka.config.Config.config.getString("test.name", "") must be("node1")
        }

        "be able to start all nodes" in {
          LocalCluster.barrier("start", NrOfNodes) {
            Cluster.node.start()
          }
          Cluster.node.isRunning must be(true)
          Cluster.node.shutdown()
        }
      }
    }

    class SampleMultiJvmNode2 extends WordSpec with MustMatchers {
      import SampleMultiJvmSpec._

      "A cluster" must {

        "have jvm options" in {
          System.getProperty("akka.cluster.nodename", "") must be("node2")
          System.getProperty("akka.remote.port", "") must be("9992")
          akka.config.Config.config.getString("test.name", "") must be("node2")
        }

        "be able to start all nodes" in {
          LocalCluster.barrier("start", NrOfNodes) {
            Cluster.node.start()
          }
          Cluster.node.isRunning must be(true)
          Cluster.node.shutdown()
        }
      }
    }

An example output from this would be:

.. code-block:: none

    > multi-jvm:test-only sample.Sample
    ...
    [info] Starting JVM-Node1 for example.SampleMultiJvmNode1
    [info] Starting JVM-Node2 for example.SampleMultiJvmNode2
    [JVM-Node1] Loading config [akka.conf] from the application classpath.
    [JVM-Node2] Loading config [akka.conf] from the application classpath.
    ...
    [JVM-Node2] Hello from node 2
    [JVM-Node1] Hello from node 1
    [success]


NetworkFailureTest
==================

You can use the ``NetworkFailureTest`` trait to test network failure. See the
``RemoteErrorHandlingNetworkTest`` test. Your tests needs to end with
``NetworkTest``. They are disabled by default. To run them you need to enable a
flag.

Example::

   project akka-remote
   set akka.test.network true
   test-only akka.actor.remote.RemoteErrorHandlingNetworkTest

It uses ``ipfw`` for network management. Mac OSX comes with it installed but if
you are on another platform you might need to install it yourself. Here is a
port:

http://info.iet.unipi.it/~luigi/dummynet
