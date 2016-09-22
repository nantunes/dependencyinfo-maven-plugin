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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    RequireJsMerger testsMerger = null;
    Map<String, String> selfDependencies = null;

    boolean jsUnitTests = false;
    List<Profile> activeProfiles = mavenProject.getActiveProfiles();
    getLog().info(activeProfiles.toString());
    for (Profile p : activeProfiles) {
      if (p.getId().equals("jsUnitTests")) {
        jsUnitTests = true;

        testsMerger = new RequireJsMerger();
        selfDependencies = new HashMap<>();

        break;
      }
    }

    String dependenciesJsonString = "";

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
          } else {
            continue;
          }
        } else if (dep.getGroupId().startsWith("org.webjars")) {
          prefix = "pentaho-webjars:mvn:";

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

            tempFile.delete();

            if (isRuntimeOrProvided) {
              if (json.containsKey("requirejs-osgi-meta")) {
                Map<String, Object> runtimeMeta = (Map<String, Object>) json.get("requirejs-osgi-meta");
                if (runtimeMeta.containsKey("modules")) {
                  Map<String, Map<String, Map<String, ?>>> availableModules = (Map<String, Map<String, Map<String, ?>>>) runtimeMeta.get("modules");
                  for (String module : availableModules.keySet()) {
                    final Map<String, Map<String, ?>> moduleInfo = availableModules.get(module);

                    for (String version : moduleInfo.keySet()) {
                      dependenciesJsonString += "\"" + module + "\": \"" + version + "\",\n";
                    }
                  }
                }
              }
            }

            if (jsUnitTests) {
              testsMerger.merge(json);
              selfDependencies.put("mvn:" + dep.getGroupId() + "/" + dep.getArtifactId(), dep.getVersion());
            }
          } catch (Exception e) {
            getLog().error(e.getMessage());
          }
        } else {
          continue;
        }

        if (isRuntime) {
          bundleXmlString += "<bundle dependency=\"" + isDependency + "\">" + prefix + dep.getGroupId() + "/" + dep.getArtifactId() + "/" + dep.getVersion() + (dep.getType().equals("jar") ? "" : "/" + dep.getType()) + "</bundle>";
        }
      }
    }

    if (!dependenciesJsonString.isEmpty()) {
      dependenciesJsonString = dependenciesJsonString.substring(0, dependenciesJsonString.length() - 2);
    }
    mavenProject.getProperties().setProperty("dependencyinfo.ui-dependencies", dependenciesJsonString);

    mavenProject.getProperties().setProperty("dependencyinfo.ui-bundles", bundleXmlString);

    if (jsUnitTests) {
      try {
        Map<String, Object> meta = new HashMap<>();

        Map<String, Map<String, Map<String, Map<String, String>>>> modules = new HashMap<>();
        Map<String, Map<String, Map<String, String>>> module = new HashMap<>();
        Map<String, Map<String, String>> ver = new HashMap<>();

        ver.put("dependencies", selfDependencies);
        module.put(mavenProject.getVersion(), ver);
        modules.put(mavenProject.getArtifactId(), module);
        meta.put("modules", modules);

        Map<String, Map<String, Map<String, String>>> artifacts = new HashMap<>();
        Map<String, Map<String, String>> artifact = new HashMap<>();

        HashMap<String, String> availableModules = new HashMap<>();
        availableModules.put(mavenProject.getArtifactId(), mavenProject.getVersion());
        artifact.put(mavenProject.getVersion(), availableModules);
        artifacts.put(mavenProject.getArtifactId(), artifact);
        meta.put("artifacts", artifacts);

        Map<String, Object> requireJsonObject = new HashMap<>();
        requireJsonObject.put("requirejs-osgi-meta", meta);

        testsMerger.merge(requireJsonObject);
      } catch (Exception e) {
        getLog().error(e.getMessage());
      }

      Map<String, Object> testsConfig = testsMerger.getRequireConfig();
      RequireJsDependencyResolver.processMetaInformation(testsConfig);

      mavenProject.getProperties().setProperty("dependencyinfo.test-dependencies", JSONObject.toJSONString(testsConfig));
    }
  }
}
