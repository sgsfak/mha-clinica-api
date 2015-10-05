import com.ning.http.client.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Created by ssfak on 28/9/15.
 */
public class SPARQLClient {

    private static final String server = "http://139.91.210.41:8890/sparql/";
    private static final String default_graph_uri ="http://localhost:8890/MHA";

    private AsyncHttpClient asyncHttpClient;

    SPARQLClient()
    {
        asyncHttpClient = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setAllowPoolingConnections(true)
                .setExecutorService(Executors.newCachedThreadPool())
                .setMaxConnectionsPerHost(100)
                .build());
    }
    CompletableFuture<String> send_query(final String query)
    {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        Map<String, List<String>> params = new HashMap<>();
        params.put("query", Arrays.asList(query));
        params.put("default-graph-uri", Arrays.asList(default_graph_uri));

        Request r = new RequestBuilder().setUrl(server)
                .setMethod("POST")
                .setFormParams(params)
                .setHeader("Accept", "text/csv")
                .build();
        asyncHttpClient.prepareRequest(r).execute(new AsyncCompletionHandler<Void>() {
            @Override
            public Void onCompleted(Response response) throws Exception {
                System.out.println("Response returned " + response.getStatusCode() + " at thread " + Thread.currentThread().getId() + "\n");
                if (response.getStatusCode() / 100 == 2) // i.e. 2xx
                    fut.complete(response.getResponseBody());
                else
                    fut.completeExceptionally(new Throwable(response.getResponseBody()));
                return null;
            }

            @Override
            public void onThrowable(Throwable t) {
                System.out.println("Response error " + t.getMessage());
                fut.completeExceptionally(t);
            }
        });
        return fut;
    }
}
