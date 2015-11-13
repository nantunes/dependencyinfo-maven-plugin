package pt.webdetails;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.ops4j.pax.url.mvn.Handler;
import org.pentaho.js.require.RequireJsDependencyResolver;
import org.pentaho.js.require.RequireJsMerger;
import org.pentaho.osgi.platform.webjars.WebjarsURLConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mojo(name = "dependencyinfo", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresDependencyResolution = ResolutionScope.TEST)
public class DependencyInfo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject mavenProject;

  public void execute() throws MojoExecutionException {
    final List<Dependency> dependencyArtifacts = mavenProject.getTestDependencies();

    Handler mavenUrlHandler = new Handler();
    JSONParser parser = new JSONParser();

    String bundleXmlString = "";
    String dependenciesJsonString = "";
    RequireJsMerger merger = null;
    HashMap<String, String> selfDependencies = null;

    boolean jsUnitTests = false;
    List<Profile> activeProfiles = mavenProject.getActiveProfiles();
    getLog().info(activeProfiles.toString());
    for (Profile p: activeProfiles) {
      if(p.getId().equals("jsUnitTests")) {
        jsUnitTests = true;

        merger = new RequireJsMerger();
        selfDependencies = new HashMap<>();

        break;
      }
    }

    for (Dependency dep : dependencyArtifacts) {
      final boolean isRuntime = dep.getScope().equals("runtime");
      final boolean isRuntimeOrProvided = isRuntime || dep.getScope().equals("provided");

      if (jsUnitTests || isRuntimeOrProvided) {
        String prefix = null;
        String isDependency = "false";
        if (dep.getGroupId().equals("pentaho")) {
          if (dep.getArtifactId().equals("common-ui")) {
            prefix = "pentaho-platform-plugin-mvn:";
          } else if (dep.getArtifactId().equals("pentaho-requirejs-osgi-manager")) {
            prefix = "mvn:";
            isDependency = "true";
          }
        } else if (dep.getGroupId().startsWith("org.webjars")) {
          prefix = "pentaho-webjars:mvn:";

          if(isRuntimeOrProvided) {
            dependenciesJsonString += "\"pentaho-webjar-deployer:" + dep.getGroupId() + "/" + dep.getArtifactId() + "\": \"" + dep.getVersion() + "\",\n";
          }

          if(jsUnitTests) {
            try {
              WebjarsURLConnection connection = new WebjarsURLConnection(new URL(null, "mvn:" + dep.getGroupId() + "/" + dep.getArtifactId() + "/" + dep.getVersion(), mavenUrlHandler));
              connection.connect();

              InputStream inputStream = connection.getInputStream();
              File tempFile = File.createTempFile("webjar", ".zip");
              FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
              IOUtils.copy(inputStream, fileOutputStream);
              fileOutputStream.close();

              ZipFile zipInputStream = new ZipFile(tempFile);
              ZipEntry entry = zipInputStream.getEntry("META-INF/js/require.json");

              String jsonFile = IOUtils.toString(zipInputStream.getInputStream(entry), "UTF-8");
              JSONObject json = (JSONObject) parser.parse(jsonFile);

              merger.merge(json);

              tempFile.delete();

              selfDependencies.put("pentaho-webjar-deployer:" + dep.getGroupId() + "/" + dep.getArtifactId(), dep.getVersion());
            } catch (Exception e) {
              getLog().error(e.getMessage());
            }
          }
        } else {
          continue;
        }

        if(isRuntime) {
          bundleXmlString += "<bundle dependency=\"" + isDependency + "\">" + prefix + dep.getGroupId() + "/" + dep.getArtifactId() + "/" + dep.getVersion() + (dep.getType().equals("jar") ? "" : "/" + dep.getType()) + "</bundle>";
        }
      }
    }

    mavenProject.getProperties().setProperty("dependencyinfo.ui-bundles", bundleXmlString);

    if(!dependenciesJsonString.isEmpty()) {
      dependenciesJsonString = dependenciesJsonString.substring(0, dependenciesJsonString.length()-2);
    }

    mavenProject.getProperties().setProperty("dependencyinfo.ui-dependencies", dependenciesJsonString);

    if(jsUnitTests) {
      try {
        JSONObject meta = new JSONObject();

        JSONObject modules = new JSONObject();
        JSONObject module = new JSONObject();
        JSONObject ver = new JSONObject();

        ver.put( "dependencies",  selfDependencies );
        module.put( mavenProject.getVersion(), ver );
        modules.put( mavenProject.getArtifactId(), module );
        meta.put( "modules", modules );

        JSONObject artifacts = new JSONObject();
        JSONObject artifact = new JSONObject();
        ver = new JSONObject();

        HashMap<String, String> availableModules = new HashMap<>();
        availableModules.put( mavenProject.getArtifactId(), mavenProject.getVersion() );
        ver.put( "modules", availableModules );
        artifact.put( mavenProject.getVersion(), ver );
        artifacts.put( mavenProject.getArtifactId(), artifact );
        meta.put( "artifacts", artifacts );

        JSONObject requireJsonObject = new JSONObject();
        requireJsonObject.put( "requirejs-osgi-meta", meta );

        merger.merge(requireJsonObject);
      } catch (Exception e) {
        getLog().error(e.getMessage());
      }

      JSONObject result = merger.getRequireConfig();
      RequireJsDependencyResolver.processMetaInformation(result);

      mavenProject.getProperties().setProperty("dependencyinfo.test-dependencies", result.toJSONString());
    }
  }
}
