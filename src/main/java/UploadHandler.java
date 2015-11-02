import io.undertow.io.IoCallback;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toList;

/**
 * Created by ssfak on 2/11/15.
 */
class UploadHandler implements HttpHandler {
    private final MultiPartParserDefinition m;
    private final String tempUploadDir;
    private final String tempStoreDir;

    public UploadHandler(MultiPartParserDefinition m, String tempUploadDir, String tempStoreDir) {
        this.m = m;
        this.tempUploadDir = tempUploadDir;
        this.tempStoreDir = tempStoreDir;
    }

    private static String partFileName(int chunkNumber) {
        return String.format("part_%05d", chunkNumber);
    }

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


            String user = data.getFirst("username").getValue();

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

                        if (ZipUtils.isZipFile(finalFile)) {
                            ForkJoinPool.commonPool().execute(() -> {
                                Path outputDir = Paths.get(tempStoreDir, identifier);
                                try {
                                    ZipUtils.unzipFile(outputDir, finalFile.toFile());
                                    List<DICOMClient.Instance> lst = MHAClinicalAPI.dicomClient.sendDcmFile(outputDir);
                                    MHAClinicalAPI.store_images(user, lst);

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
