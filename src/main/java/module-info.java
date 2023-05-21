module com.redhat.crda {
  requires java.net.http;

  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.databind;
  requires jakarta.annotation;

  opens com.redhat.crda.backend to com.fasterxml.jackson.databind;

  exports com.redhat.crda;
  exports com.redhat.crda.backend;
}