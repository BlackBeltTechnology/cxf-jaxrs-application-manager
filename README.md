# JAX-RS application manager

JAX-RS application manager can be used to track JAX-RS application/resource/provider changes in OSGi environment.

## Preparing runtime environment

* download and install Apache Karaf (>= 4.1.0) from http://karaf.apache.org/download.html
* install declarative services, JAX-RS API and CXF implementation features including JAX-RS JSON provider implementation (Jackson):
```
feature:install scr cxf-jaxrs cxf-jackson
```
* copy cxf-jaxrs-application-manager-VERSION.jar into KARAF_HOME/deploy
* create JAX-RS resources (and custom providers optionally)

## Configuration options
