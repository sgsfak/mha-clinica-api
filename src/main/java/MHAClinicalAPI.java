/**
 * Created by ssfak on 25/9/15.
 */

import com.datastax.driver.core.Row;
import com.opencsv.CSVReader;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.SameThreadExecutor;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static java.util.stream.Collectors.*;

import static io.undertow.Handlers.routing;

public class MHAClinicalAPI {
    public static String readQueryFromFile(String file_name) {
        try (InputStream ins = ClassLoader.getSystemResourceAsStream("Queries/" + file_name)) {
            BufferedReader br = new BufferedReader(new InputStreamReader(ins));
            return br.lines().collect(joining("\n"));
        } catch (IOException ex) {
            ex.printStackTrace();
            System.out.println();
            return "";
        }
    }

    public static void ask_cassandra(final String user, HttpServerExchange exchange) {

        Map<String, Deque<String>> params = exchange.getQueryParameters();
        final LocalDate from = params.containsKey("from")
                ? LocalDate.parse(params.get("from").getFirst(), DateTimeFormatter.ISO_LOCAL_DATE)
                : LocalDate.MIN;
        final LocalDate to = params.containsKey("to")
                ? LocalDate.parse(params.get("to").getFirst(), DateTimeFormatter.ISO_LOCAL_DATE)
                : LocalDate.MAX;

        System.out.printf("[%s] GET pat summary of %s for period [%s, %s]\n",
                ZonedDateTime.now().toString(), user, from, to);

        CompletableFuture<UUID> pat_sum_uuid = CassandraClient.DB.executeAsync("select inserted from patient_summary where mha_uid=? limit 1", user)
                .thenApplyAsync(rows -> {
                    //System.out.println("uuid " + Thread.currentThread().getName());
                    final Row one = rows.one();
                    if (one == null) {
                        exchange.setStatusCode(404);
                        // exchange.endExchange();
                        throw new RuntimeException("Given patient ssn was not found");
                    }
                    return one.getUUID("inserted");
                });

        CompletableFuture<JSONArray> problems = pat_sum_uuid
                .thenCompose(uuid -> CassandraClient.DB.executeAsync("select * from problems where mha_uid=? and inserted=?", user, uuid))
                .thenApply(rows -> {
                    //System.out.println("problems " + Thread.currentThread().getName());
                    List<JSONObject> list = rows.all().stream().map(row -> {
                        JSONObject problem = new JSONObject();
                        problem.put("code", row.getString("code"));
                        problem.put("code_system", row.getString("code_system"));
                        problem.put("title", row.getString("title"));
                        return problem;
                    }).collect(toList());
                    JSONArray probs = new JSONArray();
                    probs.addAll(list);
                    return probs;
                });

        CompletableFuture<JSONArray> drugs = pat_sum_uuid
                .thenCompose(uuid -> CassandraClient.DB.executeAsync("select * from drugs where mha_uid=? and inserted=?", user, uuid))
                .thenApply(rows -> {
                    //System.out.println("drugs " + Thread.currentThread().getName());
                    List<JSONObject> list = rows.all().stream()
                            .filter(row -> {
                                LocalDate start = row.getTimestamp("start_date") == null ? LocalDate.MIN : row.getTimestamp("start_date").toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                LocalDate end = row.getTimestamp("end_date") == null ? LocalDate.MAX : row.getTimestamp("end_date").toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                boolean no_overlap = end.isBefore(from) || start.isAfter(to);
                                return !no_overlap;
                            })
                            .map(row -> {
                                JSONObject drug = new JSONObject();
                                drug.put("atc_code", row.getString("atc_code"));
                                drug.put("title", row.getString("title"));

                                JSONObject dose = new JSONObject();
                                dose.put("value", row.getFloat("dose"));
                                dose.put("unit", row.getString("dose_unit"));
                                drug.put("dose", dose);

                                //Optional.of(row.getTimestamp("start_date")).map(d -> d.toInstant().toString()).orElse(null);
                                drug.put("start_date", row.getTimestamp("start_date") != null ? row.getTimestamp("start_date").toInstant().toString().substring(0, 10) : null);
                                drug.put("end_date", row.getTimestamp("end_date") != null ? row.getTimestamp("end_date").toInstant().toString().substring(0, 10) : null);
                                return drug;
                            }).collect(toList());
                    JSONArray probs = new JSONArray();
                    probs.addAll(list);
                    return probs;
                });
        CompletableFuture<JSONArray> vital_signs = pat_sum_uuid
                .thenCompose(uuid -> CassandraClient.DB.executeAsync("select * from vital_signs where mha_uid=? and inserted=?", user, uuid))
                .thenApply(rows -> {
                    //System.out.println("signs " + Thread.currentThread().getName());
                    List<JSONObject> list = rows.all().stream()
                            .filter(row -> {
                                LocalDate when = row.getTimestamp("when").toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                boolean no_contains = when.isBefore(from) || when.isAfter(to);
                                return !no_contains;
                            })
                            .map(row -> {
                                JSONObject sign = new JSONObject();
                                sign.put("loinc_code", row.getString("loinc_code"));
                                sign.put("title", row.getString("title"));
                                sign.put("units", row.getString("units"));
                                sign.put("value", row.getFloat("value"));
                                String s = row.getTimestamp("when").toInstant().toString();
                                sign.put("when", row.getTimestamp("when").toInstant().toString().substring(0, 10));

                                return sign;
                            }).collect(toList());
                    JSONArray signs = new JSONArray();
                    signs.addAll(list);
                    return signs;
                });

        CompletableFuture.allOf(problems, drugs, vital_signs)
                .whenComplete((v, ex) -> {
                    // System.out.println("all " + Thread.currentThread().getName());
                    if (ex != null) {
                        ex.printStackTrace();
                        if (exchange.getStatusCode() == 200)
                            exchange.setStatusCode(500);
                        exchange.getResponseSender().send(ex.getMessage());
                        exchange.endExchange();
                        return;
                    }
                    JSONArray probs = problems.join();
                    JSONArray drgs = drugs.join();
                    JSONArray signs = vital_signs.join();
                    JSONObject summary = new JSONObject();
                    summary.put("id", pat_sum_uuid.join().toString());

                    summary.put("active_problems", probs);
                    summary.put("drugs", drgs);
                    summary.put("vital_signs", signs);
                    JSONObject resObj = new JSONObject();
                    resObj.put("summary", summary);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(resObj.toJSONString());
                    exchange.endExchange();
                    // System.out.println("Returned...");
                });


    }

    public static void ask_triplestore(SPARQLClient sc, final String user, final String query, HttpServerExchange exchange) {
        String q = query.replace("{USER}", user);
        CompletableFuture<String> f = sc.send_query(q);
        f.thenAcceptAsync(csv -> {
            // System.out.println("Success returned (IO thread? " + exchange.isInIoThread() + ") at thread " + Thread.currentThread().getId() + "\n");
            CSVReader reader = new CSVReader(new StringReader(csv));
            try {
                String[] colNames = reader.readNext();
                List<JSONObject> results = reader.readAll().stream().map(cols -> {
                    JSONObject obj = new JSONObject();
                    for (int i = 0; i < cols.length; i++) {
                        obj.put(colNames[i], cols[i]);
                    }
                    return obj;
                }).collect(toList());
                JSONObject resOnj = new JSONObject();
                resOnj.put("results", results);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(resOnj.toJSONString());
            } catch (IOException ex) {
                exchange.setStatusCode(500);

            } finally {
                exchange.endExchange();
            }
        }).exceptionally(ex -> {
            System.out.println("exceptionally returned (IO thread? " + exchange.isInIoThread() + ") at thread " + Thread.currentThread().getId() + "\n");
            exchange.setStatusCode(500);
            exchange.getResponseSender().send(ex.getMessage());
            exchange.endExchange();
            return null;
        });
    }

    public static void main(String... args) {
        CassandraClient.DB.connect("mha_clinical", "forth", "forth2015", "139.91.210.47");
        final SPARQLClient sc = new SPARQLClient();
        // String activities_query = readQueryFromFile("Activities.sparql");
        // String diary_query = readQueryFromFile("Diary.sparql");
        // System.out.println(activities_query);

        Undertow server = Undertow.builder()
                .addHttpListener(9090, "0.0.0.0")
                .setHandler(routing()
                        .get("/patsum", exchange -> {
                            Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                            if (!queryParameters.containsKey("u")) {
                                exchange.setStatusCode(404);
                                exchange.getResponseSender().send("You must specify the 'user' in the 'u' query parameter");
                                return;
                            }
                            String user = queryParameters.get("u").getFirst();
                            // System.out.println("GET patient summary of user '" + user + "'");
                            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(),
                                    // () -> ask_triplestore(sc, user, diary_query, exchange)
                                    () -> ask_cassandra(user, exchange)
                            );
                        })
                        .get("/ps", exchange -> {
                            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(),
                                    () -> ask_cassandra("23068400115", exchange)
                            );
                        }))
                .build();
        server.start();
    }
}
