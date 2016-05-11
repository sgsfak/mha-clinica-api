#!/bin/sh
# Download the JAI imageIO libs from http://download.java.net/media/jai-imageio/builds/release/1.1/jai_imageio-1_1-lib-linux-amd64.tar.gz
export PATH=$PATH:jai_imageio-1_1/lib
# After `mvn jar:jar` and `mvn package`, we have the "one-jar":
exec java -cp .:mha-clinical-api-1.0-SNAPSHOT.one-jar.jar:jai_imageio-1_1/lib/clibwrapper_jiio.jar:jai_imageio-1_1/lib/jai_imageio.jar com.simontuffs.onejar.Boot config.properties
