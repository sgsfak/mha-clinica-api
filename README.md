### Introduction
This is the code for the Clinical API server for the [MyHealthAvatar](http://myhealthavatar.org/) project. This service offers a RESTful interface with the following functionalities:

* Upload a zip of DICOM images. The files are then sent to the backend DICOM server (using C-STORE command). The endpoint for this is the `/upload` using the `POST` HTTP method and it's compliant with the [Resumable.js](http://www.resumablejs.com/) client side (javascript) library, so that pretty large files can be uploaded in a robust way. This endpoint also supports [CORS](https://en.wikipedia.org/wiki/Cross-origin_resource_sharing)
* Retrieve information about a DICOM series using `GET` at the `/series` endpoint with the series instance UID supplied in the `series_id` query parameter. The server contacts the backend DICOM server (using C-FIND) and returns information about the patient id, the study instance UID, and the list of images ("SOP instance UIDs") in JSON format.
* Retrieve the whole DICOM series as a ZIP file using `GET` at the `/dcm` endpoint with the series instance UID supplied in the `series_uid` query parameter. The images comprising the series are retrieved from the backend DICOM server using the C-FIND and C-GET commands.
* Retrieve a preview (JPEG image) of an instance (image) of a DICOM series using `GET` at the `/wado` endpoint given an "instance UID" in the `instance_uid` query parameter. The endpoint actually "proxies" the backend DICOM server using the [WADO protocol](http://www.research.ibm.com/haifa/projects/software/wado/).
* Finally, retrieve a patient's clinical summary in JSON format using `GET` at the `/patsum` endpoint. The myHealthAvatar user's ID should be given in the `u` query parameter. The data are retrieved from the backend TripleStore using SPARQL (see the queries at `src/main/resources/Queries/` folder). Additional query string parameters exist for specifying a date range: `from` is for the start of the date period, and `to` for the end for the period. The format of these dates should be compliant with the ISO format, e.g. "2008-04-21".

The following drawing shows the architecture:

```
                                                             +----------------------------+
                                                             |                            |
                                                             |                            |
                                                             |       DICOM Server         |
                                                             |                            |
                                                             |                            |
                                                             +--------------+-------------+
                 +----------------------+                                   ^
   HTTP REST     |                      |                                   |
                 |                      |                                   |
+--------------->|                      |                                   |
                 |   MHA Clinical API   |                                   |
                 |                      +-----------------------------------+
                 |                      |         C-STORE, C-FIND, C-GET, WADO
                 |                      |
                 +-----------+------+---+
                             |      |
                             |      |                                                   +---------------------+
                             |      |                                                   |                     |
                             |      |              SPARQL                               |                     |
                             |      +-------------------------------------------------->|      TripleStore    |
                  Store      |                                                          |      (Virtuoso)     |
                  clinical   |                                                          |                     |
                  data       |        +--------------------------+                      |                     |
                             |        |                          |                      +---------------------+
                             |        |         CASSANDRA        |
                             +------->|     (the "data lake")    |
                                      |                          |
                                      +--------------------------+

```


### Security

The Clinical API server is typically deployed behind a reverse proxy that accepts all the requests for the MHA APIs and services and then routes to the responsible backend server. For the Clinical API server a typical [Apache 2](https://httpd.apache.org/) configuration that uses the [mod_rewrite](https://httpd.apache.org/docs/current/mod/mod_rewrite.html) and its [proxy support](https://httpd.apache.org/docs/current/rewrite/flags.html#flag_p) is the following:

```
		RewriteEngine on
		RewriteRule ^/mha/clinical-api/(.*)$ http://<server-ip>:9595/$1 [P]
```

where `<server-ip>` is the IP (or DNS name) of the Clinical API server (by default it listens on port 9595). If done this way, the server will accept the session "[cookies](https://en.wikipedia.org/wiki/HTTP_cookie)" after a user logs into the MHA portal and then it forwards them to the MHA "token/session validation API", e.g. to `https://myhealthavatar.org/mha/api/me`. The prefix `/mha/clinical-api/` can be changed but in that case you need to also adapt the `BASE_URI` property in the server's config file (see below).

By the way, the same configuration for the [nginx](https://www.nginx.com/) web server is as follows:

```
location /mha/clinical-api {
    rewrite  ^/mha/clinical-api/(.*)  /$1 break;
    proxy_redirect     off;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_set_header   Host              $http_host;
    proxy_set_header   X-NginX-Proxy     true;
    proxy_set_header   Connection        "";
    proxy_http_version 1.1;
    proxy_pass         http://127.0.0.1:9595;
}
```


### Configuration

Check the `config.properties` file, shown also below:

```
### Configuration properties for the MHA-ClinicalAPI server
## The listening port, the "base URL" (i.e. the externally visible url that any request to it is forwarded to 
## our server by the Proxy), log file (if needed), and temporary upload files location
PORT=9595
BASE_URI=https://myhealthavatar.org/mha/clinical-api
LOGFILE_DIR=/tmp/mha_clinical_api
TEMP_UPLOAD_DIR=/tmp/mha/uploads

## Configuration for the validation of session cookies and tokens
MHA_ACCESS_TOKEN_VALIDATOR_URI=https://myhealthavatar.org/mha/api/me

## DICOM Configuration
## Specify the DICOM Server host, port, "application entity", and WADO URL
DICOM_SERVER_HOST=localhost
DICOM_SERVER_PORT=11112
DICOM_SERVER_AET=DCM4CHEE
DICOM_MY_AET=MHAUploadAPI
DICOM_WADO_URL=http://localhost:8080/wado

## Triplestore
SPARQL_URL=<the endpoint accepting SPARQL requests>

## Cassandra
## Specify the connection parameters for Cassandra
CASSANDRA_HOST=10.1.5.13
CASSANDRA_KEYSPACE=mha_clinical
CASSANDRA_USER=<user-name>
CASSANDRA_PASSWORD=<password>
```

--- 
ssfak at ics dot forth dot gr

