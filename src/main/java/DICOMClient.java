import com.ning.http.client.*;
import com.pixelmed.dicom.*;
import com.pixelmed.display.ConsumerFormatImageMaker;
import com.pixelmed.network.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by ssfak on 12/10/15.
 */
public class DICOMClient {
    private String host = "213.165.94.158";
    private int port = 11112;
    private String myAET = "MHAUploadAPI";
    private String srvAET = "MHA";

    AsyncHttpClient httpClient;

    private ExecutorService executorService = Executors.newFixedThreadPool(5);

    DICOMClient(AsyncHttpClient httpClient) {
        this.httpClient = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setAllowPoolingConnections(true)
                //.setMaxConnectionsPerHost(10)
                .build());
    }

    public DICOMClient setHost(String host) {
        this.host = host;
        return this;
    }

    public DICOMClient setPort(int port) {
        this.port = port;
        return this;
    }

    public DICOMClient setMyAET(String myAET) {
        this.myAET = myAET;
        return this;
    }

    public DICOMClient setSrvAET(String srvAET) {
        this.srvAET = srvAET;
        return this;
    }

    @Deprecated
    public static boolean isDICOMFile(Path path) {
        long magic_pos = 128;
        File file = path.toFile();
        // byte[] x = new byte [] {0x44, 0x49, 0x43, 0x4D, 0x02, 0x00}; // "DICM"
        if (file.isDirectory() || file.length() < magic_pos + 4)
            return false;
        try (RandomAccessFile raf = new RandomAccessFile(path.toString(), "r")) {
            raf.seek(magic_pos);
            return raf.readInt() == 0x4449434D; // |DICM|
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public String toString() {
        return String.format("DICOMClient{%s:%d, calledAET=%s, callingAET=%s}",
                this.host, this.port, this.srvAET, this.myAET);
    }

    public boolean verifyServer() {
        try {
            new VerificationSOPClassSCU(this.host, this.port, this.srvAET, this.myAET, false, 0);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return false;
        }
        return true;
    }

    class Series {
        public String patientId;
        public String studyUID;
        public String seriesUID;
        public String modality;
        public List<String> instanceUID;

        public Series() {
            this.instanceUID = new ArrayList<>(10);
        }
        boolean isNull()
        {
            return this.seriesUID == null;
        }
    }

    class Instance {
        private String file;
        private String patientId;
        private String studyUID;
        private String seriesUID;
        private String instanceUID;
        private Date acquisitionDate;

        private String studyDescription;
        private String modality;

        private boolean isValid = false;

        public Instance(File fn) {
            file = fn.getAbsolutePath();
            try (DicomInputStream dis = new DicomInputStream(new FileInputStream(fn))) {
                AttributeList attr = new AttributeList();
                attr.read(dis, TagFromName.PixelData);

                patientId = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.PatientID), "");
                studyUID = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.StudyInstanceUID), "");
                seriesUID = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.SeriesInstanceUID), "");
                instanceUID = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.SOPInstanceUID), "");

                String acqDate = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.SeriesDate), "19740106");
                final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
                try {
                    this.acquisitionDate = dateFormat.parse(acqDate);
                } catch (ParseException e) {
                    e.printStackTrace();
                }


                studyDescription = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.StudyDescription), "");
                modality = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.Modality), "");

                isValid = true;
            } catch (DicomException de) {
            } catch (IOException ex) {
            }
        }

        public boolean isValid() {
            return isValid;
        }

        public String getFile() {
            return file;
        }

        public String toString() {

            return String.format("%s / %s / %s / %s", patientId, studyUID, seriesUID, instanceUID);
        }

        public Date getAcquisitionDate() {
            return acquisitionDate;
        }

        public String getSeriesUID() {
            return seriesUID;
        }

        public String getInstanceUID() {
            return instanceUID;
        }

        public Optional<File> createIconFile() {
            return createIconFile(100);
        }

        public Optional<File> createIconFile(int size) {

            final String ICON_FORMAT = "jpeg";
            final String ICON_SUFFIX = ".jpg";
            final int ICON_QUALITY = 100;
            try {
                File iconFile = File.createTempFile("icon", ICON_SUFFIX);

                iconFile.deleteOnExit();
                ConsumerFormatImageMaker.convertFileToEightBitImage(this.file,
                        iconFile.getAbsolutePath(),
                        ICON_FORMAT,
                        0, 0,
                        size,
                        0, ICON_QUALITY,
                        ConsumerFormatImageMaker.NO_ANNOTATIONS,
                        0);

                return Optional.of(iconFile);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (DicomException e) {
                e.printStackTrace();
            }
            return Optional.empty();
        }

        public String getModality() {
            return modality;
        }

        public String getStudyDescription() {
            return studyDescription;
        }

        public String getStudyUID() {
            return studyUID;
        }
    }

    public List<Instance> sendDcmFile(Path dir) {
        final Set<String> sentSopInstances = new HashSet<>();

        List<Instance> sentInstances = new ArrayList<>();

        try (Stream<Path> files = Files.walk(dir)) {

            String[] fileNames = files.map(Path::toFile).filter(DicomFileUtilities::isDicomOrAcrNemaFile)
                    .map(File::getAbsolutePath)
                    .collect(Collectors.toList()).toArray(new String[0]);
            SetOfDicomFiles dcmFiles = new SetOfDicomFiles(fileNames);

            List<Instance> instances = Stream.of(fileNames)
                    .map(File::new)
                    .map(Instance::new)
                    .filter(Instance::isValid)
                    .collect(Collectors.toList());

            if (instances.isEmpty()) {
                System.out.println(" Directory " + dir + " does not contain DCM files?");
                return sentInstances;
            }
//            instances.forEach(System.out::println);
            final LinkedList lpc = PresentationContextListFactory.createNewPresentationContextList(dcmFiles, 2);

            Association association = null;
            try {
                association = AssociationFactory.createNewAssociation(this.host, this.port, this.srvAET, this.myAET, lpc, null, false, 0);
                new StorageSOPClassSCU(association, dcmFiles, new MultipleInstanceTransferStatusHandler() {
                    @Override
                    public void updateStatus(int nRemaining, int nCompleted, int nFailed, int nWarning, String sopInstanceUID) {
                        // System.out.println("Sent " + sopInstanceUID + " remaining: "+nRemaining + " (failed:" + nFailed+")");
                        sentSopInstances.add(sopInstanceUID);

                    }
                }, 1);
            } finally {
                if (association != null)
                    association.release();
            }

            instances.stream()
                    .filter(instance1 -> sentSopInstances.contains(instance1.getInstanceUID()))
                    .forEach(instance -> {
                        sentInstances.add(instance);
                    });

        } catch (IOException e) {
            e.printStackTrace();
        } catch (DicomNetworkException e) {
            e.printStackTrace();
        }


        return sentInstances;
    }

    public CompletableFuture<Series> get_series_async(final String series_uid) {
        CompletableFuture<Series> fut = new CompletableFuture<>();
        this.executorService.execute(() -> {
            try {
                final Series series = get_series_sync(series_uid);
                fut.complete(series);
            } catch (Exception ex) {
                fut.completeExceptionally(ex);
            }

        });

        return fut;
    }

    private Series get_series_sync(final String series_uid) throws IOException, DicomNetworkException, DicomException {
        SpecificCharacterSet specificCharacterSet = new SpecificCharacterSet((String[]) null);
        AttributeList identifier = new AttributeList();
        {
            AttributeTag t = TagFromName.QueryRetrieveLevel;
            Attribute a = new CodeStringAttribute(t);
            a.addValue("IMAGE");
            identifier.put(t, a);
        }
        {
            AttributeTag t = TagFromName.SeriesInstanceUID;
            Attribute a = new UniqueIdentifierAttribute(t);
            a.addValue(series_uid);
            identifier.put(t, a);
        }
        {
            AttributeTag t = TagFromName.PatientID;
            Attribute a = new LongStringAttribute(t, specificCharacterSet);
            a.addValue("");
            identifier.put(t, a);
        }
        {
            AttributeTag t = TagFromName.SOPInstanceUID;
            Attribute a = new LongStringAttribute(t, specificCharacterSet);
            a.addValue("");
            identifier.put(t, a);
        }
        {
            AttributeTag t = TagFromName.StudyInstanceUID;
            Attribute a = new UniqueIdentifierAttribute(t);
            a.addValue("");
            identifier.put(t, a);
        }
        {
            AttributeTag t = TagFromName.Modality;
            Attribute a = new UniqueIdentifierAttribute(t);
            a.addValue("");
            identifier.put(t, a);
        }
        final Series series = new Series();
        new FindSOPClassSCU(this.host, this.port, this.srvAET, this.myAET,
                SOPClass.StudyRootQueryRetrieveInformationModelFind,
                identifier,
                new IdentifierHandler() {
                    boolean firstTime = true;
                    @Override
                    public void doSomethingWithIdentifier(AttributeList list) throws DicomException {
                        // System.out.println(list);
                        if (this.firstTime) {
                            series.patientId = list.get(TagFromName.PatientID).getSingleStringValueOrEmptyString();
                            series.seriesUID = list.get(TagFromName.SeriesInstanceUID).getSingleStringValueOrEmptyString();
                            series.studyUID = list.get(TagFromName.StudyInstanceUID).getSingleStringValueOrEmptyString();
                            series.modality = list.get(TagFromName.Modality).getSingleStringValueOrEmptyString();
                            this.firstTime = false;
                        }
                        final Attribute attribute = list.get(TagFromName.SOPInstanceUID);
                        final String instance_uid = attribute.getSingleStringValueOrNull();
                        if (instance_uid != null)
                            series.instanceUID.add(instance_uid);
                    }
                },
                1);
        return series;
    }

    private String wadoUrl = "http://localhost:8080/wado";

    public void setWadoUrl(String wadoUrl) {
        this.wadoUrl = wadoUrl;
    }

    public class FileSaveHandler extends AsyncCompletionHandlerBase {

        private Path path;
        private FileOutputStream fos;
        private CompletableFuture<Path> fut;
        private boolean error = false;

        FileSaveHandler(Path f, CompletableFuture<Path> fut) throws FileNotFoundException {
            this.path = f;
            this.fos = new FileOutputStream(f.toFile());
            this.fut = fut;
        }

        @Override
        public STATE onStatusReceived(HttpResponseStatus status) throws Exception {
            if (status.getStatusCode() / 100 == 2)
                return STATE.CONTINUE;
            error = true;
            return STATE.ABORT;

        }

        @Override
        public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            fos.write(bodyPart.getBodyPartBytes());
            // System.out.println("Called "+bodyPart.length());
            return STATE.CONTINUE;
        }


        @Override
        public Response onCompleted(Response response) throws Exception {
            // System.out.println(" * " + this.path + " Error? " + error);
            fos.close();
            if (error)
                fut.complete(null);
            else
                fut.complete(this.path);
            return response;
        }

        @Override
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            error = true;
        }
    }

    public CompletableFuture<Path> wado_retrieve_instance(final String instanceUID, final Path saveTo,
                                                          final boolean downloadJpeg) {
        Request r = new RequestBuilder()
                .setMethod("GET")
                .setUrl(wadoUrl)
                .addQueryParam("requestType", "WADO")
                .addQueryParam("studyUID", "")
                .addQueryParam("seriesUID", "")
                .addQueryParam("objectUID", instanceUID)
                .addQueryParam("contentType", downloadJpeg ? "image/jpeg" : "application/dicom")
                .build();
        // System.out.println("-->" + r.getUri());
        CompletableFuture<Path> fut = new CompletableFuture<>();
        try {
            final FileSaveHandler handler;
            handler = new FileSaveHandler(saveTo, fut);
            this.httpClient.executeRequest(r, handler);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            fut = CompletableFuture.completedFuture(null);
        }
        return fut;
    }

}
