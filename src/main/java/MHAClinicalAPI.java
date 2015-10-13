/**
 * Created by ssfak on 25/9/15.
 */

import com.datastax.driver.core.Row;
import com.opencsv.CSVReader;
import io.undertow.Undertow;
import io.undertow.io.IoCallback;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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


    public static void get_dcm_image(final String user, final String series_id, HttpServerExchange exchange) {

        CassandraClient.DB.executeAsync("select icon_jpg from dcm_images where mha_uid=? and series_uid=? limit 1", user, series_id)
                .thenAcceptAsync(rows -> {
                    final Row one = rows.one();
                    if (one == null) {
                        exchange.setStatusCode(404);
                        exchange.endExchange();
                        return;
                    }
                    final ByteBuffer iconJpg = one.getBytes("icon_jpg");

                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/jpeg");
                    exchange.getResponseSender().send(iconJpg);
                    exchange.endExchange();
                });
    }

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
                                String when = row.getTimestamp("when").toInstant().toString();
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
                                        try {
                                            img.put("href", String.format("%s/dcm?u=%s&id=%s", baseURI, URLEncoder.encode(user, "UTF-8"), series_uid));
                                        } catch (UnsupportedEncodingException e) {
                                        }
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
                        if (exchange.getStatusCode() == 200)
                            exchange.setStatusCode(500);
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
                    exchange.getResponseSender().send(resObj.toJSONString());
                    exchange.endExchange();
                    // System.out.println("Returned...");
                });


    }

    public static void store_images(final String user_uid, List<DICOMClient.Instance> images) {
        images.stream()
                .collect(Collectors.groupingBy(DICOMClient.Instance::getSeriesUID))
                .forEach((seriesUID, lst) -> {
                    final int index = lst.size() / 2;
                    final DICOMClient.Instance typicalInstance = lst.get(index);
                    File iconFile = typicalInstance.createIconFile(200).orElseGet(() -> new File("Dfsfd"));
                    try {
                        final ByteBuffer byteBuffer = ByteBuffer.wrap(Files.readAllBytes(iconFile.toPath()));
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
                exchange.getResponseSender().send(resOnj.toJSONString(), IoCallback.END_EXCHANGE);
            } catch (IOException ex) {
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.getResponseSender().send(ex.getMessage(), IoCallback.END_EXCHANGE);

            }
        }).exceptionally(ex -> {
            System.out.println("exceptionally returned (IO thread? " + exchange.isInIoThread() + ") at thread " + Thread.currentThread().getId() + "\n");
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.getResponseSender().send(ex.getMessage(), IoCallback.END_EXCHANGE);
            return null;
        });
    }

    private static void visitEntries(final Path zipPath, FileVisitor<Path> visitor) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(new URI("jar", zipPath.toUri().toString(), null), Collections.singletonMap("create", "false"), null)) {
            fs.getRootDirectories().forEach(root -> {
                try {
                    Files.walkFileTree(root, visitor);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }

    private static boolean isZipFile(final Path file) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file.toFile()))) {
            boolean isZip = in.readInt() == 0x504b0304;
            return isZip;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }

    }

    public static void main(String... args) {

        if (args.length == 0) {
            System.err.println("Usage:\n\t java MHAClinicalAPI <config.properties>\nwhere config.properties is a properties file..");
            System.exit(-1);
        }
        Configuration config = Configuration.create(args[0]);
        assert config != null;

        //System.err.println("Configuration:\n"+config);

        DICOMClient dcmClient = new DICOMClient().setHost(config.getDicomHost())
                .setPort(config.getDicomPort())
                .setMyAET(config.getDicomCallingAET())
                .setSrvAET(config.getDicomCalledAET());

        if (!dcmClient.verifyServer()) {
            System.out.println("DICOM Server verification " + dcmClient + " failed in C-ECHO");
            return;
        }

        CassandraClient.DB.connect(config.getCassandraKeyspace(), config.getCassandraUser(), config.getCassandraPwd(), config.getCassandraHost());
        final SPARQLClient sc = new SPARQLClient();
        // String activities_query = readQueryFromFile("Activities.sparql");
        // String diary_query = readQueryFromFile("Diary.sparql");
        // System.out.println(activities_query);

        final int port = config.getPort();
        MultiPartParserDefinition m = new MultiPartParserDefinition();
        m.setMaxIndividualFileSize(10 * 1024 * 1024);

        final String myURI = config.getMyURI();
        final String baseURI = myURI.endsWith("/") ? myURI.substring(0, myURI.length() - 1) : myURI;
        final String tempUploadDir = config.getTempUploadDir().endsWith(File.separator) ? config.getTempUploadDir() : config.getTempUploadDir() + File.separator;
        final String tempStoreDir = tempUploadDir + File.separator + "_store";

        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(routing()
                        .get("/patsum", exchange -> {
                            Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                            if (!queryParameters.containsKey("u")) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify the 'user' in the 'u' query parameter");
                                return;
                            }
                            String user = queryParameters.get("u").getFirst();
                            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(),
                                    // () -> ask_triplestore(sc, user, diary_query, exchange)
                                    () -> ask_cassandra(user, baseURI, exchange)
                            );
                        })

                        .get("/dcm", exchange -> {
                            Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
                            if (!queryParameters.containsKey("u")) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify the 'user' in the 'u' query parameter");
                                return;
                            } else if (!queryParameters.containsKey("id")) {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("You must specify the 'id' query parameter");
                                return;
                            }
                            String user = queryParameters.get("u").getFirst();
                            String id = queryParameters.get("id").getFirst();
                            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(),
                                    () -> get_dcm_image(user, id, exchange)
                            );

                        })
                        .get("/ps", exchange -> {
                            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(),
                                    () -> ask_cassandra("23068400115", baseURI, exchange)
                            );
                        })
                        .add("options", "/upload", exchange -> {
                            exchange.setStatusCode(StatusCodes.NO_CONTENT);
                            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
                            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "POST, GET, OPTIONS");
                            exchange.endExchange();

                        })
                        .post("/upload", new HttpHandler() {
                                    @Override
                                    public void handleRequest(HttpServerExchange exchange) throws Exception {
                                        //  System.out.println(exchange.isInIoThread() + " " + Thread.currentThread().getName());
                                        if (exchange.isInIoThread()) {
                                            exchange.dispatch(this);
                                            return;
                                        }
                                        exchange.startBlocking();

                                        try (FormDataParser formDataParser = m.create(exchange)) {


                                            FormData data = formDataParser.parseBlocking();

                                            int chunkNumber = Integer.parseInt(data.getFirst("resumableChunkNumber").getValue());
                                            int totalChunks = Integer.parseInt(data.getFirst("resumableTotalChunks").getValue());
                                            long chunkSize = Long.parseLong(data.getFirst("resumableChunkSize").getValue());

                                            String identifier = data.getFirst("resumableIdentifier").getValue();
                                            long totalSize = Long.parseLong(data.getFirst("resumableTotalSize").getValue());
                                            String filename = data.getFirst("resumableFilename").getValue();

                                            final Path tempFile = data.getFirst("file").getPath();

                                            if (chunkNumber < totalChunks && tempFile.toFile().length() < chunkSize) {
                                            /*


                                                The chuck has not been transferred in its entirety!
                                                Say to client to re-upload it

                                                The documentation at https://github.com/23/resumable.js says:
                                                    For every request, you can confirm reception in HTTP status codes:
                                                    200: The chunk was accepted and correct. No need to re-upload.
                                                    404, 415. 500, 501: The file for which the chunk was uploaded is not supported, cancel the entire upload.
                                                    Anything else: Something went wrong, but try re-uploading the file.

                                            */
                                                exchange.setStatusCode(StatusCodes.BAD_REQUEST); // 400
                                                exchange.endExchange();
                                                return;
                                            }
                                            final Path dir = Files.createDirectories(Paths.get(tempUploadDir + identifier));
                                            final String partFileName = partFileName(chunkNumber);
                                            final Path partFile = Paths.get(dir.toString(), partFileName);
                                            Files.move(tempFile, partFile);

                                            List<File> parts;
                                            try (final Stream<Path> pathStream = Files.walk(dir, 1)) {
                                                parts = pathStream
                                                        .map(Path::toFile)
                                                        .filter(file -> file.isFile() && file.getName().startsWith("part"))
                                                        .collect(toList());
                                            }
                                            final long curSize = parts.stream().collect(summingLong(File::length));


                                            System.out.printf("[%s]: CurSize: %d TotalSize: %d\n", Thread.currentThread().getName(), curSize, totalSize);

                                            if (curSize >= totalSize) {
                                                try {
                                                    Path finalFile = Files.createFile(Paths.get(dir.toString(), filename));

                                                    try (RandomAccessFile raf = new RandomAccessFile(finalFile.toString(), "rw")) {
                                                        for (int i = 1; i <= totalChunks; ++i) {
                                                            final String fileName = partFileName(i);
                                                            final Path partPath = Paths.get(dir.toString(), fileName);
                                                            raf.write(Files.readAllBytes(partPath));
                                                            Files.delete(partPath);
                                                        }
                                                        System.out.println(Thread.currentThread().getName() + " Created... ");

                                                        if (isZipFile(finalFile)) {
                                                            ForkJoinPool.commonPool().execute(() -> {
                                                                Path outputDir = Paths.get(tempStoreDir, identifier);
                                                                try {
                                                                    unzipFile(outputDir, finalFile.toFile());
                                                                    List<DICOMClient.Instance> lst = dcmClient.sendDcmFile(outputDir);
                                                                    store_images("zdeng", lst);

                                                                } catch (Throwable t) {
                                                                    t.printStackTrace();
                                                                }
                                                            });
                                                        }
                                                        exchange.setStatusCode(StatusCodes.OK);
                                                        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
                                                        exchange.getResponseSender().send("Filename: " + filename + " saved...", IoCallback.END_EXCHANGE);
                                                    }
                                                } catch (FileAlreadyExistsException ex) {
                                                    System.out.println(Thread.currentThread().getName() + " Final file exists...");
                                                }
                                            } else {
                                                exchange.setStatusCode(StatusCodes.OK);
                                                exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
                                                exchange.getResponseSender().send("OK", IoCallback.END_EXCHANGE);
                                            }
                                        }
                                    }
                                }

                        )).
                        build();

        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.printf("Server started, listening at %d\n", port);
    }

    private static void unzipFile(Path outputDir, File zipFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            final int MAX_BUF = 4 * 1024 * 1024;
            byte buffer[] = new byte[MAX_BUF];
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                final Path outputEntryPath = Paths.get(outputDir.toString(), entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outputEntryPath);
                } else {
                    if (!Files.exists(outputEntryPath.getParent())) {
                        Files.createDirectories(outputEntryPath.getParent());
                    }
                    try (FileOutputStream fos = new FileOutputStream(outputEntryPath.toString())) {
                        while (true) {
                            int k = zis.read(buffer, 0, MAX_BUF);
                            if (k > 0)
                                fos.write(buffer, 0, k);
                            else
                                break;
                        }
                    }
                    // System.out.println("==> wrote " + entry.getName() + " at " + outputEntryPath);
                }
                zis.closeEntry();
            }
        }
    }

    private static String partFileName(int chunkNumber) {
        return String.format("part_%05d", chunkNumber);
    }
}
