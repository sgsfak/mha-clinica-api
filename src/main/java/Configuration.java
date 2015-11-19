import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;

/**
 * Created by ssfak on 12/10/15.
 */
public class Configuration {
    private Properties properties;

    private int port;
    private Path logDirPath;
    private String tempUploadDir;
    private String myURI;

    private String accessTokenValidatorURI;

    private String cassandraHost;
    private String cassandraUser;
    private String cassandraPwd;
    private String cassandraKeyspace;


    private String dicomHost;
    private String dicomCallingAET;
    private String dicomCalledAET;
    private int dicomPort;
    private String wadoUrl;

    private String sparqlURL;

    private Configuration() {
    }

    static Optional<String> whatsMyIP() {
        try (InputStream response = new URL("http://ipinfo.io/ip").openStream()) {
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

            config.port = Optional.ofNullable(config.properties.getProperty("PORT")).map(Integer::parseInt).orElse(9090);

            String logDir = config.properties.getProperty("LOGFILE_DIR");
            if (logDir != null) {
                final Path logDirPath = Paths.get(logDir);
                if (!Files.exists(logDirPath) || !logDirPath.toFile().isDirectory())
                    Files.createDirectory(logDirPath);
                config.logDirPath = logDirPath;
            }


            config.tempUploadDir = Optional.ofNullable(config.properties.getProperty("TEMP_UPLOAD_DIR")).orElse("/tmp/mhaclinicalapi/uploads");

            config.accessTokenValidatorURI = config.properties.getProperty("MHA_ACCESS_TOKEN_VALIDATOR_URI");

            config.cassandraHost = config.properties.getProperty("CASSANDRA_HOST");
            config.cassandraUser = config.properties.getProperty("CASSANDRA_USER");
            config.cassandraPwd = config.properties.getProperty("CASSANDRA_PASSWORD");
            config.cassandraKeyspace = Optional.ofNullable(config.properties.getProperty("CASSANDRA_KEYSPACE")).orElse("mha_clinical");

            config.dicomHost = config.properties.getProperty("DICOM_SERVER_HOST");
            config.dicomCalledAET = config.properties.getProperty("DICOM_SERVER_AET");
            config.dicomCallingAET = config.properties.getProperty("DICOM_MY_AET");
            config.dicomPort = Optional.ofNullable(config.properties.getProperty("DICOM_SERVER_PORT")).map(Integer::parseInt).orElse(11112);
            config.wadoUrl = config.properties.getProperty("DICOM_WADO_URL");

            config.sparqlURL = config.properties.getProperty("SPARQL_URL");

            config.myURI = Optional.ofNullable(config.properties.getProperty("BASE_URI"))
                    .orElseGet(()->{
                        String my_host = whatsMyIP().orElse("www.example.org");
                        return String.format("http://%s:%d/", my_host, config.port);
                    });
            System.err.println(String.format("Listening on %d and (externally) accepting requests at %s", config.port, config.myURI));
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

    public Path getLogDirPath() {
        return logDirPath;
    }

    public String getAccessTokenValidatorURI() {
        return accessTokenValidatorURI;
    }

    public String toString() {
        return this.properties.toString();
    }

    public String getCassandraKeyspace() {
        return cassandraKeyspace;
    }

    public String getSparqlURL() {
        return sparqlURL;
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
