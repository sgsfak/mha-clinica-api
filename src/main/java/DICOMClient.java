import com.pixelmed.dicom.*;
import com.pixelmed.display.ConsumerFormatImageMaker;
import com.pixelmed.network.*;

import javax.swing.text.html.Option;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
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


    DICOMClient() {}

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
    public static boolean isDICOMFile(Path path)
    {
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
    public String toString()
    {
        return String.format("DICOMClient{%s:%d, calledAET=%s, callingAET=%s}",
                this.host, this.port, this.srvAET, this.myAET);
    }
    public boolean verifyServer()
    {
        try {
            new VerificationSOPClassSCU(this.host,this.port, this.srvAET, this.myAET,false, 0);
        }
        catch (Exception e) {
            e.printStackTrace(System.err);
            return false;
        }
        return true;
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
        public Instance(File fn)
        {
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
                    this.acquisitionDate =  dateFormat.parse(acqDate);
                } catch (ParseException e) {
                    e.printStackTrace();
                }


                studyDescription = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.StudyDescription), "");
                modality = Attribute.getSingleStringValueOrDefault(attr.get(TagFromName.Modality), "");

                isValid = true;
            }
            catch (DicomException de) {
            }
            catch (IOException ex) {
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

    public List<Instance> sendDcmFile(Path dir)
    {
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
                System.out.println(" Directory "+ dir + " does not contain DCM files?");
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
                },1);
            }
            finally {
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

}
