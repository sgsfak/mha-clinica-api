This is the code for the Clinical API server for the [MyHealthAvatar](myhealthavatar.org) project. This service offers a RESTful interface with the following functionalities:

* Upload a zip of DICOM images. The files are then sent to the backend DICOM server. The endpoint for this is the `/upload` using the `POST` HTTP method and it's compliant with the [Resumable.js](http://www.resumablejs.com/) client side (javascript) library, so that pretty large files can be uploaded in a robust way. This endpoint also supports [CORS](https://en.wikipedia.org/wiki/Cross-origin_resource_sharing)
* Retrieve information about a DICOM series using `GET` at the `/series` endpoint with the series instance UID supplied in the `id` query parameter. The server contacts the backend DICOM server (using C-FIND) and returns information about the patient id, the study instance UID, and the list of images ("SOP instance UIDs") in JSON format.
* Retrieve the whole DICOM series as a ZIP file using `GET` at the `/dcm` endpoint with the series instance UID supplied in the `id` query parameter. The images comprising the series are retrieved from the backend DICOM server using the [WADO protocol](http://www.research.ibm.com/haifa/projects/software/wado/).
* Retrieve a patient's clinical summary in JSON format using `GET` at the `/patsum` endpoint. The myHealthAvatar user's ID should be given in the `u` query parameter. The data are retrieved from the backend Cassandra cluster.


--- 
<small>
ssfak at ics dot forth dot gr
</small>
