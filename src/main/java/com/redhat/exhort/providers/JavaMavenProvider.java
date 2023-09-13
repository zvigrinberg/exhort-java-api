/*
 * Copyright © 2023 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.exhort.providers;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.redhat.exhort.Api;
import com.redhat.exhort.Provider;
import com.redhat.exhort.sbom.Sbom;
import com.redhat.exhort.sbom.SbomFactory;
import com.redhat.exhort.tools.Ecosystem;
import com.redhat.exhort.tools.Ecosystem.Type;
import com.redhat.exhort.tools.Operations;

/**
 * Concrete implementation of the {@link Provider} used for converting dependency trees
 * for Java Maven projects (pom.xml) into a content Dot Graphs for Stack analysis or Json for
 * Component analysis.
 **/
public final class JavaMavenProvider extends Provider {
  public static void main(String[] args) throws IOException {
    JavaMavenProvider javaMavenProvider = new JavaMavenProvider();
    Content content = javaMavenProvider.provideStack(Path.of("/tmp/bug-070923/pom.xml"));
    String report = new String(content.buffer);
    System.out.println(report);
  }
  public JavaMavenProvider() {
    super(Type.MAVEN);
  }

  @Override
  public Content provideStack(final Path manifestPath) throws IOException {
    // check for custom mvn executable
    var mvn = Operations.getCustomPathOrElse("mvn");
    // clean command used to clean build target
    var mvnCleanCmd = new String[]{mvn, "-q", "clean", "-f", manifestPath.toString()};
    // execute the clean command
    Operations.runProcess(mvnCleanCmd);
    // create a temp file for storing the dependency tree in
    var tmpFile = Files.createTempFile("exhort_dot_graph_", null);
    // the tree command will build the project and create the dependency tree in the temp file
    var mvnTreeCmd = new ArrayList<String>() {{
      add(mvn);
      add("-q");
      add("dependency:tree");
      add("-DoutputType=dot");
      add("-Dscope=compile");
      add("-Dscope=runtime");
      add(String.format("-DoutputFile=%s", tmpFile.toString()));
      add("-f");
      add(manifestPath.toString());
    }};
    // if we have dependencies marked as ignored, exclude them from the tree command
    var ignored = getDependencies(manifestPath).stream()
      .filter(d -> d.ignored)
      .map(DependencyAggregator::toPurl)
      .map(PackageURL::getCoordinates)
      .collect(Collectors.toList());
    // execute the tree command
    Operations.runProcess(mvnTreeCmd.toArray(String[]::new));
    var sbom = buildSbomFromDot(tmpFile);
    // build and return content for constructing request to the backend
    return new Content(sbom.filterIgnoredDeps(ignored).getAsJsonString().getBytes(), Api.CYCLONEDX_MEDIA_TYPE);
  }

  private Sbom buildSbomFromDot(Path dotFile) throws IOException {
    var sbom = SbomFactory.newInstance(Sbom.BelongingCondition.PURL);
    var reader = new BufferedReader(Files.newBufferedReader(dotFile));
    String line = reader.readLine();
    while (line != null) {
      if(line.startsWith("digraph ")) {
        var dotPkg = line.replace("digraph", "")
          .replace("{", "");
        sbom.addRoot(dotPkgToPurl(dotPkg));
      } else if(line.endsWith("}") || line.trim().isBlank()) {
        // ignore
      } else {
        var parts = line.replaceAll(";", "").split("->");
        if(parts.length == 2) {
          var src = dotPkgToPurl(parts[0]);
          var target = dotPkgToPurl(parts[1]);
          sbom.addDependency(src, target);
        }
      }
      line = reader.readLine();
    }
    return sbom;
  }

  private PackageURL dotPkgToPurl(String dotPkg) {
    var parts = dotPkg.
    replaceAll("\"", "")
        .trim().split(":");
    if(parts.length >= 4) {
      try {
        return new PackageURL(Ecosystem.Type.MAVEN.getType(), parts[0], parts[1], parts[3], null, null);
      } catch (MalformedPackageURLException e) {
        throw new IllegalArgumentException("Unable to parse dot package: " + dotPkg, e);
      }
    }
    throw new IllegalArgumentException("Invalid dot package format: " + dotPkg);
  }

  @Override
  public Content provideComponent(byte[] manifestContent) throws IOException {
    // check for custom mvn executable
    var mvn = Operations.getCustomPathOrElse("mvn");
    // save content in temporary file
    var originPom = Files.createTempFile("exhort_orig_pom_", ".xml");
    Files.write(originPom, manifestContent);
    // create a temp file for storing the effective pom in
    var tmpEffPom = Files.createTempFile("exhort_eff_pom_", ".xml");
    // build effective pom command
    var mvnEffPomCmd = new String[]{
      mvn,
      "-q",
      "clean",
      "help:effective-pom",
      String.format("-Doutput=%s", tmpEffPom.toString()),
      "-f", originPom.toString()
    };
    // execute the effective pom command
    Operations.runProcess(mvnEffPomCmd);
    // if we have dependencies marked as ignored grab ignored dependencies from the original pom
    // the effective-pom goal doesn't carry comments
    List<DependencyAggregator> dependencies = getDependencies(originPom);
    var ignored = dependencies.stream().filter(d -> d.ignored).map(DependencyAggregator::toPurl).collect(Collectors.toSet());
    var testsDeps = dependencies.stream().filter(DependencyAggregator::isTestDependency).collect(Collectors.toSet());
    var deps = getDependencies(tmpEffPom);
    var sbom = SbomFactory.newInstance().addRoot(getRoot(tmpEffPom));
    deps.stream()
      .filter(dep -> !testsDeps.contains(dep))
      .map(DependencyAggregator::toPurl)
      .filter(dep -> !ignored.contains(dep))
      .forEach(d -> sbom.addDependency(sbom.getRoot(), d));

    // build and return content for constructing request to the backend
    return new Content(sbom.getAsJsonString().getBytes(), Api.CYCLONEDX_MEDIA_TYPE);
  }

  private PackageURL getRoot(final Path manifestPath) throws IOException {
    XMLStreamReader reader = null;
    try {
      reader = XMLInputFactory.newInstance().createXMLStreamReader(Files.newInputStream(manifestPath));
      DependencyAggregator dependencyAggregator = null;
      boolean isRoot = false;
      while (reader.hasNext()) {
        reader.next(); // get the next event
        if (reader.isStartElement() && "project".equals(reader.getLocalName())) {
          isRoot = true;
          dependencyAggregator = new DependencyAggregator();
          continue;
        }
        if (!Objects.isNull(dependencyAggregator)) {
          if (reader.isStartElement()) {
            switch (reader.getLocalName()) {
              case "groupId": // starting "groupId" tag, get next event and set to aggregator
                reader.next();
                dependencyAggregator.groupId = reader.getText();
                break;
              case "artifactId": // starting "artifactId" tag, get next event and set to aggregator
                reader.next();
                dependencyAggregator.artifactId = reader.getText();
                break;
              case "version": // starting "version" tag, get next event and set to aggregator
                reader.next();
                dependencyAggregator.version = reader.getText();
                break;
            }
          }
          if (isRoot && dependencyAggregator.isValid()) {
            return dependencyAggregator.toPurl();
          }
        }
      }
    } catch (XMLStreamException exc) {
      throw new IOException(exc);
    } finally {
      if (!Objects.isNull(reader)) {
        try {
          reader.close(); // close stream if open
        } catch (XMLStreamException e) {
          //
        }
      }
    }

    throw new IllegalStateException("Unable to retrieve Root dependency from effective pom");
  }

  private List<DependencyAggregator> getDependencies(final Path manifestPath) throws IOException {
    List<DependencyAggregator> deps = new ArrayList<>();
    XMLStreamReader reader = null;
    try {
      //get a xml stream reader for the manifest file
      reader = XMLInputFactory.newInstance().createXMLStreamReader(Files.newInputStream(manifestPath));
      // the following dependencyIgnore object is used to aggregate dependency data over iterations
      // when a "dependency" tag starts, it will be initiated,
      // when a "dependency" tag ends, it will be parsed, act upon, and reset
      DependencyAggregator dependencyAggregator = null;
      while (reader.hasNext()) {
        reader.next(); // get the next event
        if (reader.isStartElement() && "dependency".equals(reader.getLocalName())) {
            // starting "dependency" tag, initiate aggregator
            dependencyAggregator = new DependencyAggregator();
            continue;
        }

        // if dependency aggregator haven't been initiated,
        // we're currently not iterating over a "dependency" tag - no need for further parsing
        if (!Objects.isNull(dependencyAggregator)) {
          // if we hit an ignore comment, mark aggregator to be ignored
          if (reader.getEventType() == XMLStreamConstants.COMMENT
              && "exhortignore".equals(reader.getText().strip())
          ) {
            dependencyAggregator.ignored = true;
            continue;
          }

          if (reader.isStartElement()) {
            // NOTE if we want to include "scope" tags in ignore,
            // add a case here and a property in DependencyIgnore
            switch (reader.getLocalName()) {
              case "groupId": // starting "groupId" tag, get next event and set to aggregator
                reader.next();
                dependencyAggregator.groupId = reader.getText();
                break;
              case "artifactId": // starting "artifactId" tag, get next event and set to aggregator
                reader.next();
                dependencyAggregator.artifactId = reader.getText();
                break;

              case "scope":
                reader.next();
                dependencyAggregator.scope = reader.getText() != null ? reader.getText() : "*";
                break;
              case "version": // starting "version" tag, get next event and set to aggregator
                reader.next();
                dependencyAggregator.version = reader.getText();
                break;
            }
          }

          if (reader.isEndElement() && "dependency".equals(reader.getLocalName())) {
            // add object to list and reset dependency aggregator
            deps.add(dependencyAggregator);
            dependencyAggregator = null;
          }
        }
      }
    } catch (XMLStreamException exc) {
      throw new IOException(exc);
    } finally {
      if (!Objects.isNull(reader)) {
        try {
          reader.close(); // close stream if open
        } catch (XMLStreamException e) {
          //
        }
      }
    }

    return deps;
  }

  // NOTE if we want to include "scope" tags in ignore,
  // add property here and a case in the start-element-switch in the getIgnored method
  /** Aggregator class for aggregating Dependency data over stream iterations, **/
  private final static class DependencyAggregator {
    private String scope="*";
    private String groupId;
    private String artifactId;
    private String version;
    boolean ignored = false;

    /**
     * Get the string representation of the dependency to use as excludes
     * @return an exclude string for the dependency:tree plugin, ie. group-id:artifact-id:*:version
     */
    @Override
    public String toString() {
      // NOTE if you add scope, don't forget to replace the * with its value
      return String.format("%s:%s:%s:%s", groupId, artifactId,scope, version);
    }

    public boolean isValid() {
      return Objects.nonNull(groupId) && Objects.nonNull(artifactId) && Objects.nonNull(version);
    }

    public boolean isTestDependency()
    {
      return scope.trim().equals("test");
    }

    /**
     * Convert the {@link DependencyAggregator} object to a {@link PackageAggregator}
     * @return a new instance of {@link PackageAggregator}
     * @throws MalformedPackageURLException
     */
    public PackageURL toPurl() {
      try {
        return new PackageURL(Ecosystem.Type.MAVEN.getType(), groupId, artifactId, version, null, null);
      } catch (MalformedPackageURLException e) {
        throw new IllegalArgumentException("Unable to parse PackageURL", e);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof DependencyAggregator)) return false;
      var that = (DependencyAggregator) o;
      // NOTE we do not compare the ignored field
      // This is required for comparing pom.xml with effective_pom.xml as the latter doesn't
      // contain comments indicating ignore
      return Objects.equals(this.groupId, that.groupId) &&
        Objects.equals(this.artifactId, that.artifactId) &&
        Objects.equals(this.version, that.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(groupId, artifactId, version);
    }
  }
}