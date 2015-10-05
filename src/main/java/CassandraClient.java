import com.datastax.driver.core.*;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;


/**
 * Created by ssfak on 30/9/15.
 */
public enum CassandraClient {

    DB;

    private Session session;
    private Cluster cluster;
    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraClient.class);

    /**
     * Connect to the cassandra database based on the connection configuration provided.
     * Multiple call to this method will have no effects if a connection is already established
     */
    public void connect(String keyspace, String username, String password, String seed) {
        if (cluster == null && session == null) {
            cluster = Cluster.builder().withCredentials(username, password).addContactPoint(seed).build();
            session = cluster.connect(keyspace);
        }
        Metadata metadata = cluster.getMetadata();
        LOGGER.info("Connected to cluster: " + metadata.getClusterName() + " with partitioner: " + metadata.getPartitioner());
        metadata.getAllHosts().stream().forEach((host) -> {
            LOGGER.info("Cassandra datacenter: " + host.getDatacenter() + " | address: " + host.getAddress() + " | rack: " + host.getRack());
        });
    }

    /**
     * Invalidate and close the session and connection to the cassandra database
     */
    public void shutdown() {
        LOGGER.info("Shutting down the whole cassandra cluster");
        if (null != session) {
            session.close();
        }
        if (null != cluster) {
            cluster.close();
        }
    }

    public Session getSession() {
        if (session == null) {
            throw new IllegalStateException("No connection initialized");
        }
        return session;
    }

    public CompletableFuture<ResultSet> executeAsync(final String query, Object... params)
    {
        // System.out.println("==> [" + Thread.currentThread().getName() +"] " + query );
        Session session = getSession();
        ResultSetFuture resultSetFuture = session.executeAsync(query, params);
        CompletableFuture<ResultSet> fut = new CompletableFuture<>();
        resultSetFuture.addListener(
                () -> fut.complete(resultSetFuture.getUninterruptibly()),
                MoreExecutors.sameThreadExecutor()
        );
        return fut;
    }
}
