/**
 * Created by ssfak on 25/9/15.
 */

import com.datastax.driver.core.Row;
import com.google.common.net.HttpHeaders;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.io.DefaultIoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.SimpleProxyClientProvider;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.undertow.Handlers.routing;
import static java.util.stream.Collectors.*;

public class MHAClinicalAPI {
    public static String readQueryFromFile(String file_name) {
        try (InputStream ins = ClassLoader.getSystemResourceAsStream("Queries/" + file_name)) {
            BufferedReader br = new BufferedReader(new InputStreamReader(ins));
            return br.lines().collect(joining("\n"));
        } catch (IOException ex) {
            ex.printStackTrace();
            return "";
        }
    }


    static DICOMClient dicomClient;
    static SPARQLClient sparqlClient;

    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> allDoneFuture =
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
        return allDoneFuture.thenApply(v ->
                futures.stream().
                        map(CompletableFuture::join).
                        collect(Collectors.<T>toList()));
    }


    public static void get_dcm_series(final DICOMClient dcmClient, final String series_id, HttpServerExchange exchange) {

        try {
            final Path tempDirectory = Files.createTempDirectory("mha-series-");
            dcmClient.get_series_async(series_id, tempDirectory.toFile())
                    .thenAcceptAsync(fileNames -> {
                        if (fileNames.isEmpty()) {
                            safelyDeleteFileOrDir(tempDirectory);
                            exchange.setStatusCode(StatusCodes.NOT_FOUND);
                            exchange.getResponseSender().send(String.format("Series <%s> does not contain any instances!\n", series_id));
                            exchange.endExchange();
                            return;
                        }

                        System.out.printf("<%s> Got %d instances\n", Thread.currentThread().getName(), fileNames.size());

                        try {
                            final Path tempFile = Paths.get(System.getProperty("java.io.tmpdir"), "mha-" + UUID.randomUUID().toString() + ".zip");
                            ZipUtils.zipDir(tempDirectory, tempFile);
                            //System.out.println(Thread.currentThread().getName() + " Check zip file " + tempFile);

                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/zip");
                            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, tempFile.toFile().length());
                            exchange.getResponseHeaders().put(HttpString.tryFromString("Content-Disposition"),
                                    String.format("attachment; filename=series-%s.zip", series_id));
                            exchange.getResponseSender().transferFrom(FileChannel.open(tempFile), new DefaultIoCallback() {
                                @Override
                                public void onComplete(HttpServerExchange httpServerExchange, Sender sender) {
                                    safelyDeleteFileOrDir(tempDirectory);
                                    safelyDeleteFileOrDir(tempFile);
                                    super.onComplete(exchange, sender);
                                }

                                @Override
                                public void onException(final HttpServerExchange exchange, final Sender sender, final IOException exception) {
                                    safelyDeleteFileOrDir(tempDirectory);
                                    safelyDeleteFileOrDir(tempFile);
                                    super.onException(exchange, sender, exception);
                                }

                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                            exchange.endExchange();
                        }
                    });

        } catch (IOException e) {
            e.printStackTrace();
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.endExchange();
        }

    }


    private static void safelyDeleteFileOrDir(Path path) {
        try (Stream<Path> pathStream = Files.walk(path)) {
            pathStream.sorted(Comparator.comparing(Path::getNameCount).reversed())
                    .forEach(p -> {
                        try {
                            // System.out.println("Deleting " + p);
                            Files.delete(p);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Deprecated
    public static void ask_cassandra(final String user, final String baseURI, HttpServerExchange exchange) {

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
                        exchange.setStatusCode(StatusCodes.NOT_FOUND);
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
                                LocalDate when = row.getTimestamp("happened").toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                boolean no_contains = when.isBefore(from) || when.isAfter(to);
                                return !no_contains;
                            })
                            .map(row -> {
                                JSONObject sign = new JSONObject();
                                sign.put("loinc_code", row.getString("loinc_code"));
                                sign.put("title", row.getString("title"));
                                sign.put("units", row.getString("units"));
                                sign.put("value", row.getFloat("value"));
                                String when = row.getTimestamp("happened").toInstant().toString();
                                sign.put("when", when.substring(0, 10));

                                return sign;
                            }).collect(toList());
                    JSONArray signs = new JSONArray();
                    signs.addAll(list);
                    return signs;
                });

        CompletableFuture<JSONArray> dcm_images =
                CassandraClient.DB.executeAsync("select when, inserted, series_uid, study_uid, study_description, modality, instance_uids from dcm_images where mha_uid=?", user)
                        .thenApplyAsync(rows -> {
                            List<JSONObject> list = rows.all().stream()
                                    .filter(row -> {
                                        LocalDate when = row.getTimestamp("when").toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                        boolean no_contains = when.isBefore(from) || when.isAfter(to);
                                        return !no_contains;
                                    })
                                    .map(row -> {
                                        JSONObject img = new JSONObject();
                                        img.put("when", row.getTimestamp("when").toInstant().toString().substring(0, 10));
                                        final String series_uid = row.getString("series_uid");
                                        img.put("series_uid", series_uid);
                                        img.put("study_uid", row.getString("study_uid"));
                                        img.put("study_description", row.getString("study_description"));
                                        img.put("modality", row.getString("modality"));
                                        final List<String> instance_uids = row.getList("instance_uids", String.class);
                                        img.put("instance_uids", instance_uids);
                                        img.put("count", instance_uids.size());
                                        final String selectInstance = instance_uids.get(instance_uids.size() / 2);
                                        img.put("icon", String.format("%s/wado?instance_uid=%s", baseURI, selectInstance));
                                        img.put("uri", String.format("%s/dcm?series_uid=%s", baseURI, series_uid));
                                        return img;
                                    })
                                    .collect(toList());
                            JSONArray images = new JSONArray();
                            images.addAll(list);
                            return images;

                        });

        CompletableFuture.allOf(problems, drugs, vital_signs, dcm_images)
                .whenComplete((v, ex) -> {
                    // System.out.println("all " + Thread.currentThread().getName());
                    if (ex != null) {
                        ex.printStackTrace();
                        if (exchange.getStatusCode() == StatusCodes.OK)
                            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        exchange.getResponseSender().send(ex.getMessage());
                        exchange.endExchange();
                        return;
                    }
                    JSONArray probs = problems.join();
                    JSONArray drgs = drugs.join();
                    JSONArray signs = vital_signs.join();
                    JSONArray images = dcm_images.join();
                    JSONObject summary = new JSONObject();
                    summary.put("id", pat_sum_uuid.join().toString());

                    summary.put("active_problems", probs);
                    summary.put("drugs", drgs);
                    summary.put("vital_signs", signs);
                    JSONObject resObj = new JSONObject();
                    resObj.put("summary", summary);
                    resObj.put("medical_images", images);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    final String jsonString = resObj.toJSONString();
                    exchange.getResponseSender().send(jsonString);
                    // System.out.println("Returned...");
                });


    }

    public static void store_images(final String user_uid, List<DICOMClient.Instance> images) {
        images.stream()
                .collect(Collectors.groupingBy(DICOMClient.Instance::getSeriesUID))
                .forEach((seriesUID, lst) -> {
                    final int index = lst.size() / 2;
                    final DICOMClient.Instance typicalInstance = lst.get(index);
                    Optional<File> iconFile = typicalInstance.createIconFile(200);
                    try {
                        ByteBuffer byteBuffer;
                        if (iconFile.isPresent())
                            byteBuffer = ByteBuffer.wrap(Files.readAllBytes(iconFile.get().toPath()));
                        else {
                            try (final InputStream defDicomImg = MHAClinicalAPI.class.getResourceAsStream("dicom.jpg")) {
                                final byte[] b = new byte[defDicomImg.available()];
                                new DataInputStream(defDicomImg).readFully(b);
                                byteBuffer = ByteBuffer.wrap(b);
                            }
                        }
                        final List<String> instanceUIDs = lst.stream().map(DICOMClient.Instance::getInstanceUID).collect(toList());
                        CassandraClient.DB.executeAsync("INSERT INTO dcm_images(mha_uid, inserted, when, series_uid, study_uid, study_description, modality, icon_jpg, instance_uids) VALUES(?,now(),?, ?,?,?,?,?,?)",
                                user_uid,
                                typicalInstance.getAcquisitionDate(),
                                seriesUID,
                                typicalInstance.getStudyUID(),
                                typicalInstance.getStudyDescription(),
                                typicalInstance.getModality(),
                                byteBuffer,
                                instanceUIDs);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }

    public static void ask_triplestore(SPARQLClient sc, DICOMClient dc, final String user,
                                       final String imagesQuery,
                                       final String drugsQuery,
                                       final String vitalSignsQuery,
                                       final String problemsQuery,
                                       final String baseURI, HttpServerExchange exchange) {

        Map<String, Deque<String>> params = exchange.getQueryParameters();
        final LocalDate from = params.containsKey("from")
                ? LocalDate.parse(params.get("from").getFirst(), DateTimeFormatter.ISO_LOCAL_DATE)
                : LocalDate.MIN;
        final LocalDate to = params.containsKey("to")
                ? LocalDate.parse(params.get("to").getFirst(), DateTimeFormatter.ISO_LOCAL_DATE)
                : LocalDate.MAX;

        String q = imagesQuery.replace("{USER}", user);
        CompletableFuture<JSONArray> medical_images =
                sc.send_query_and_parse(q)
                        .thenComposeAsync(records -> {

                            // The Triplestore does not have all the information that we want
                            // e.g. the list of instances (images) contained in the Series
                            // so we need to contact the DICOM server (duh!)
                            final List<CompletableFuture<DICOMClient.Series>> completableFutureList = records.stream()
                                    .map(map -> map.get("series_uid"))
                                    .map(dc::find_series_async)
                                    .collect(toList());

                            return sequence(completableFutureList)
                                    .thenApplyAsync((List<DICOMClient.Series> series_list) -> listOfSeriesToJSON(baseURI, series_list));
                        });

        CompletableFuture<JSONArray> drugs =
                sc.send_query_and_parse(drugsQuery.replace("{USER}", user))
                        .thenApplyAsync(records -> {

                            List<JSONObject> results = records.stream()
                                    .map(map -> {
                                        JSONObject obj = new JSONObject();
                                        obj.put("title", map.get("title"));
                                        obj.put("atc_code", map.get("atc_code"));
                                        obj.put("start_date", map.get("start_date").substring(0, 10));
                                        obj.put("end_date", map.get("end_date").substring(0, 10));
                                        JSONObject dose = new JSONObject();
                                        dose.put("unit", map.get("dose_unit"));
                                        dose.put("value", Float.parseFloat(map.getOrDefault("dose", "0.0")));
                                        obj.put("dose", dose);
                                        return obj;
                                    }).collect(Collectors.toList());

                            JSONArray list = new JSONArray();
                            list.addAll(results);
                            return list;
                        });

        CompletableFuture<JSONArray> signs =
                sc.send_query_and_parse(vitalSignsQuery.replace("{USER}", user))
                        .thenApplyAsync(records -> {

                            List<JSONObject> results = records.stream()
                                    .map(map -> {
                                        JSONObject obj = new JSONObject();
                                        obj.put("title", map.get("title"));
                                        obj.put("loinc_code", map.getOrDefault("loinc_code", "").replace("http://purl.bioontology.org/ontology/LOINC/", ""));
                                        obj.put("when", map.getOrDefault("inserted_date", "1974-01-06").substring(0, 10));
                                        obj.put("units", map.get("units"));
                                        obj.put("value", Float.parseFloat(map.getOrDefault("value", "0.0")));
                                        return obj;
                                    }).collect(Collectors.toList());

                            JSONArray list = new JSONArray();
                            list.addAll(results);
                            return list;
                        });

        CompletableFuture<JSONArray> problems =
                sc.send_query_and_parse(problemsQuery.replace("{USER}", user))
                        .thenApplyAsync(records -> {

                            List<JSONObject> results = records.stream()
                                    .map(map -> {
                                        JSONObject obj = new JSONObject();
                                        obj.put("title", map.get("title"));
                                        if (!"".equals(map.get("code_snomed"))) {
                                            obj.put("code", map.get("code_snomed"));
                                            obj.put("code_system", "SNOMED-CT");
                                        } else {
                                            obj.put("code", map.get("code_icd_10"));
                                            obj.put("code_system", "ICD10");

                                        }
                                        return obj;
                                    }).collect(Collectors.toList());

                            JSONArray list = new JSONArray();
                            list.addAll(results);
                            return list;
                        });
        CompletableFuture.allOf(medical_images, drugs, signs, problems)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        ex.printStackTrace();
                        if (exchange.getStatusCode() == StatusCodes.OK)
                            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        exchange.getResponseSender().send(ex.getMessage());
                        exchange.endExchange();
                        return;
                    }
                    JSONArray probs = problems.join();
                    JSONArray drgs = drugs.join();
                    JSONArray vital_signs = signs.join();
                    JSONArray images = medical_images.join();
                    JSONObject summary = new JSONObject();
//                    summary.put("id", pat_sum_uuid.join().toString());

                    summary.put("active_problems", probs);
                    summary.put("drugs", drgs);
                    summary.put("vital_signs", vital_signs);
                    JSONObject resObj = new JSONObject();
                    resObj.put("summary", summary);
                    resObj.put("medical_images", images);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    final String jsonString = resObj.toJSONString();
                    exchange.getResponseSender().send(jsonString);
                });

    }

    private static JSONArray listOfSeriesToJSON(String baseURI,
                                                List<DICOMClient.Series> series_list) {
        final List<JSONObject> results = series_list.stream()
                .filter(series -> !series.isNull())
                .map(series -> {
                    final String series_uid = series.seriesUID;
                    JSONObject img = new JSONObject();
                    img.put("series_uid", series_uid);
                    img.put("when", series.seriesDate != null ? series.seriesDate.toInstant().toString().substring(0, 10) : null);
                    img.put("study_uid", series.studyUID);
                    img.put("study_description", series.studyDescription);
                    img.put("modality", series.modality);
                    img.put("instance_uids", series.instanceUID);
                    img.put("count", series.instanceUID.size());
                    final String selectInstance = series.instanceUID.get(series.instanceUID.size() / 2);
                    img.put("icon", String.format("%s/wado?instance_uid=%s", baseURI, selectInstance));
                    img.put("uri", String.format("%s/dcm?series_uid=%s", baseURI, series_uid));
                    return img;
                }).collect(toList());
        JSONArray images = new JSONArray();
        images.addAll(results);
        return images;
    }

    private static void quickly_dispatch(final HttpServerExchange exchange, Runnable func) {
        exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(),
                func);
    }

    public static void main(String... args) {


        if (args.length == 0) {
            System.err.println("Usage:\n\t java MHAClinicalAPI <config.properties>\nwhere config.properties is a properties file..");
            System.exit(-1);
        }
        Configuration config = Configuration.create(args[0]);
        assert config != null;

        AsyncHttpClient asyncHttpClient = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setAllowPoolingConnections(true)
                .setExecutorService(Executors.newCachedThreadPool())
                // .setMaxConnectionsPerHost(100)
                .build());
        //System.err.println("Configuration:\n"+config);

        sparqlClient = new SPARQLClient(asyncHttpClient, config.getSparqlURL());

        dicomClient = new DICOMClient(asyncHttpClient);
        dicomClient.setHost(config.getDicomHost())
                .setPort(config.getDicomPort())
                .setMyAET(config.getDicomCallingAET())
                .setSrvAET(config.getDicomCalledAET())
                .setWadoUrl(config.getWadoUrl());

        if (!dicomClient.verifyServer()) {
            System.out.println("DICOM Server verification " + dicomClient + " failed in C-ECHO");
            return;
        }


        CassandraClient.DB.connect(config.getCassandraKeyspace(), config.getCassandraUser(), config.getCassandraPwd(), config.getCassandraHost());
        final String imagesQuery = readQueryFromFile("DICOM_Series.sparql");
        final String drugsQuery = readQueryFromFile("Drugs.sparql");
        final String vitalSignsQuery = readQueryFromFile("Vital_signs.sparql");
        final String problemsQuery = readQueryFromFile("Problems.sparql");
        // String diary_query = readQueryFromFile("Diary.sparql");
//        System.out.println(imagesQuery);

        final int port = config.getPort();

        final String myURI = config.getMyURI();
        final String baseURI = myURI.endsWith("/") ? myURI.substring(0, myURI.length() - 1) : myURI;
        final String tempUploadDir = config.getTempUploadDir().endsWith(File.separator) ? config.getTempUploadDir() : config.getTempUploadDir() + File.separator;
        final String tempStoreDir = tempUploadDir + File.separator + "_store";

        final HttpHandler wadoProxyHandler = Handlers.proxyHandler(new SimpleProxyClientProvider(dicomClient.getWadoUrl()));

        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(routing()
                        .get("/patsum", exchange -> {
                            final Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                            if (!queryParameters.containsKey("u")) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify the 'user' in the 'u' query parameter");
                                return;
                            }
                            final String user = queryParameters.get("u").getFirst();
                            quickly_dispatch(exchange, () -> ask_triplestore(sparqlClient, dicomClient, user,
                                    imagesQuery, drugsQuery, vitalSignsQuery,problemsQuery,
                                    baseURI, exchange));
//                             quickly_dispatch(exchange, () -> ask_cassandra(user, baseURI, exchange));
                        })

                        .get("/dcm", exchange -> { // Retrieve a whole series
                            final Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                            if (!queryParameters.containsKey("series_uid")) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify the 'series_uid' query parameter");
                                return;
                            }
                            final String seriesUid = queryParameters.get("series_uid").getFirst();
                            quickly_dispatch(exchange, () -> get_dcm_series(dicomClient, seriesUid, exchange));

                        })

                        .get("/wado",
                                exchange -> {
                                    final Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                                    if (!queryParameters.containsKey("instance_uid")) {
                                        exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                        exchange.getResponseSender().send("You must specify the 'instance_uid' query parameter");
                                        return;
                                    }
                                    exchange.setQueryString(DICOMClient.build_wado_request_query(queryParameters.get("instance_uid").getFirst()));
                                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/jpeg");
                                    exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "max-age=86400");
                                    wadoProxyHandler.handleRequest(exchange);
                                })
                        .get("/series", exchange -> {
                            final Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                            if (!queryParameters.containsKey("id")) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify the 'id' query parameter");
                                return;
                            }
                            final String seriesUid = queryParameters.get("id").getFirst();
                            if ("".equals(seriesUid)) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify a non-empty value for the 'id' query parameter");
                                return;
                            }
                            quickly_dispatch(exchange, () -> {
                                dicomClient.find_series_async(seriesUid)
                                        .thenAccept(series -> {
                                            System.out.println(String.format("<%s> Series %s, pat_id=%s, study=%s, modality=%s\n",
                                                    Thread.currentThread().getName(),
                                                    series.seriesUID, series.patientId, series.studyUID, series.modality));
                                            if (series.isNull()) {
                                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                                exchange.getResponseSender().send(String.format("Series <%s> does not contain any instances!\n", seriesUid));
                                                exchange.endExchange();
                                                return;
                                            }

                                            JSONObject js = new JSONObject();
                                            js.put("series_uid", series.seriesUID);
                                            js.put("patient_uid", series.patientId);
                                            js.put("study_uid", series.studyUID);
                                            js.put("series_date", series.seriesDate);
                                            js.put("instance_uids", series.instanceUID.stream()
                                                    .map(instance_uid -> {
                                                        JSONObject jsi = new JSONObject();
                                                        jsi.put("instance_uid", instance_uid);
                                                        jsi.put("href", baseURI + "/wado?instance_uid=" + instance_uid);
                                                        return jsi;
                                                    })
                                                    .collect(toList()));

                                            exchange.setStatusCode(StatusCodes.OK);
                                            exchange.getResponseHeaders().put(new HttpString(HttpHeaders.CONTENT_TYPE), "application/json");
                                            exchange.getResponseSender().send(js.toJSONString());
                                            exchange.endExchange();
                                        })
                                        .exceptionally(ex -> {
                                            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                                            exchange.getResponseHeaders().put(new HttpString(HttpHeaders.CONTENT_TYPE), "text/plain");
                                            exchange.getResponseSender().send(ex.getMessage());
                                            exchange.endExchange();
                                            return null;
                                        });
                            });


                        })

                        .add("options", "/upload", exchange -> {
                            exchange.setStatusCode(StatusCodes.NO_CONTENT);
                            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
                            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "POST, GET, OPTIONS");
                            exchange.endExchange();

                        })
                        .post("/upload", new UploadHandler(tempUploadDir, tempStoreDir))).
                        build();

        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.printf("Server started, listening at %d\n", port);
    }


}
