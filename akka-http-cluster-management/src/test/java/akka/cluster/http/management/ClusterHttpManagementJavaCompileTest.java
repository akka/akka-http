package akka.cluster.http.management;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;

public class ClusterHttpManagementJavaCompileTest {

    public void test() {
        ActorSystem actorSystem = ActorSystem.create("test");
        Cluster cluster = Cluster.get(actorSystem);
        ClusterHttpManagement x = ClusterHttpManagement.create(cluster);
        x.start();
    }
}
