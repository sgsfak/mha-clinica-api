import java.io.*;
import java.net.URL;
import java.util.Optional;
import java.util.Properties;

/**
 * Created by ssfak on 12/10/15.
 */
public class Configuration {
    private Properties properties;

    private int port;
    private String tempUploadDir;
    private String myURI;

    private String cassandraHost;
    private String cassandraUser;
    private String cassandraPwd;
    private String cassandraKeyspace;


    private String dicomHost;
    private String dicomCallingAET;
    private String dicomCalledAET;
    private int dicomPort;
    private String wadoUrl;

    private Configuration() {}

    static Optional<String> whatsMyIP() {
        try (InputStream response = new URL("http://ipinfo.io/ip").openStream()){
            BufferedReader in = new BufferedReader(new InputStreamReader(response));
            String s = in.readLine();
            return Optional.of(s);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }
    static Configuration create(String fileName) {

        Configuration config = new Configuration();

        config.properties = new Properties();
        try (InputStream is = new FileInputStream(new File(fileName))) {
            config.properties.load(is);

            config.port = Optional.of(config.properties.getProperty("PORT")).map(Integer::parseInt).orElse(9090);

            config.tempUploadDir = Optional.of(config.properties.getProperty("TEMP_UPLOAD_DIR")).orElse("/tmp/mhaclinicalapi/uploads");

            config.cassandraHost = config.properties.getProperty("CASSANDRA_HOST");
            config.cassandraUser = config.properties.getProperty("CASSANDRA_USER");
            config.cassandraPwd = config.properties.getProperty("CASSANDRA_PASSWORD");
            config.cassandraKeyspace = Optional.of(config.properties.getProperty("CASSANDRA_KEYSPACE")).orElse("mha_clinical");

            config.dicomHost = config.properties.getProperty("DICOM_SERVER_HOST");
            config.dicomCalledAET = config.properties.getProperty("DICOM_SERVER_AET");
            config.dicomCallingAET = config.properties.getProperty("DICOM_MY_AET");
            config.dicomPort = Optional.of(config.properties.getProperty("DICOM_SERVER_PORT")).map(Integer::parseInt).orElse(11112);
            config.wadoUrl = config.properties.getProperty("DICOM_WADO_URL");


            String my_host = config.properties.getProperty("MY_HOST");
            if (my_host == null)
                my_host = whatsMyIP().orElse("www.example.org");
            config.myURI = String.format("http://%s:%d/", my_host, config.port);
            System.err.println("Listening on " + config.myURI);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return config;
    }

    public int getPort() {
        return port;
    }

    public String toString()
    {
        return this.properties.toString();
    }
    public String getCassandraKeyspace() {
        return cassandraKeyspace;
    }


    public String getCassandraHost() {
        return cassandraHost;
    }

    public String getCassandraPwd() {
        return cassandraPwd;
    }

    public String getCassandraUser() {
        return cassandraUser;
    }

    public String getDicomCalledAET() {
        return dicomCalledAET;
    }

    public String getDicomCallingAET() {
        return dicomCallingAET;
    }

    public String getDicomHost() {
        return dicomHost;
    }

    public String getWadoUrl() {
        return wadoUrl;
    }

    public String getMyURI() {
        return myURI;
    }

    public String getTempUploadDir() {
        return tempUploadDir;
    }

    public int getDicomPort() {
        return dicomPort;
    }

    public Properties getProperties() {
        return properties;
    }

}
