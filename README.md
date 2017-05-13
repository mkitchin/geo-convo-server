# geo-convo-server
Twitter conversation scanning and mapping tool, made with SpringBoot, Kotlin, OpenLayers3 and jQuery Mobile.

Uses websockets for continuous, low-overhead updates and geospatial bucketing for (somewhat) decluttered presentation.
# Features
In terms of the UX:
![alt text](doc/main-ux-1.png "Main UX #1")

![alt text](doc/main-ux-2.png "Main UX #2")

![alt text](doc/main-ux-3.png "Main UX #3")

# Getting Started

## Tools
New-ish Linux, Windows, or Mac OS.

[Download/install Java 8 SDK.](http://www.oracle.com/technetwork/java/javase/downloads/index.html)

[Download/install Apache Maven.](https://maven.apache.org/download.cgi)

Ensure both are in your working path.

## Create Twitter App

You'll need to [create a Twitter app](https://apps.twitter.com/), then get app and user keys/secrets in order to build or run the software.

Twitter app management page with the important parts highlighted:
![alt text](doc/twitter-app-setup-1.png "Twitter app setup #1")

## Create Mabpox Account
 
You'll need to [create a Mapbox account](https://www.mapbox.com/signup/), then get a public token to run the software.  

Mapbox account management page with the important parts highlighted:
![alt text](doc/mapbox-account-setup-1.png "Mapbox account setup #1")
 
# Building

## Perquisites

### Update src/main/resources/application.yml
Fill out Twitter keys/secrets obtained above, per instructions at top of file.
 
### Update src/main/resources/static/app.js
Fill out Mapbox token obtained above, per instructions at top of file.

## (Actually) Build
In bash/cmd:
```Bash
$ cd <path-to-source-folder>
$ mvn clean package
```

You may build with tests disabled if you'd rather not embed Twitter keys/secrets, as follows:
```Bash
$ cd <path-to-source-folder>
$ mvn clean package -DskipTests
```

This generates a JAR file of the following form:
```Bash
target/geo-convo-server-<version>-<snapshot-or-release>.jar
```
E.g. (used in examples below):
```Bash
target/geo-convo-server-0.0.1-SNAPSHOT.jar
```

# Deploying
1. Copy generated JAR file to wherever it's to be deployed.
1. Copy `data` folder to to the same location.
1. (Optionally) Copy `appplication.yml`, referenced above to the same location to override embedded settings (e.g., Twitter keys/secrets).

# Running
In bash/cmd:
```Bash
$ cd <path-to-run-folder>
$ java -jar geo-convo-server-0.0.1-SNAPSHOT.jar
```
It's fine to run from the source folder, as follows:
```Bash
$ cd <path-to-source-folder>
$ java -jar target/geo-convo-server-0.0.1-SNAPSHOT.jar
```

# Using
Navigate to the following URL:
```Bash
http://localhost:8080
```

If all tokens/secrets have been set correctly, the map will be visible and coversations will begin to appear within a minute (expect >60min, bandwidth depending).

# Geospatial Bucketing
In an effort to collate Tweets to/from from approximate locations I used the Natural Earth Populated Places dataset and qGIS to generate the following Voronoi (Thiessen) polygons:
![alt text](doc/location-polygons-1.png "Mapbox account setup #1")

This represents ~2000 cities from the original dataset, selected by `SCALERANK` and `RANK_MIN` (population) values.  

The `LocationsService` in the software matches Tweet locations against these polygons, then groups matched Tweets at the latitude/logitude of the related city.

These polygons are stored in a shapefile referenced in the `application.yml` file and may be modified to provide differently-distributed circles and link endpoints.  

# References
## Main Elements
- [SpringBoot](https://projects.spring.io/spring-boot/)
- [Kotlin](https://kotlinlang.org/)
- [OpenLayers3](https://openlayers.org/)
- [jQuery Mobile](https://jquerymobile.com/)

## Supporting APIs
- [Twitter4j](http://twitter4j.org/en/)
- [GeoTools](http://www.geotools.org/)

## Other
- [NaturalEarth Populated Places](http://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-populated-places/)
- [qGIS](http://www.qgis.org)
