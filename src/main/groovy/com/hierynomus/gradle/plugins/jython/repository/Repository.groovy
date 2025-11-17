/*
 * Copyright (C) 2015 Jeroen van Erp <jeroen@hierynomus.com>
 * Copyright (C) 2025 Digital.ai
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
package com.hierynomus.gradle.plugins.jython.repository

import com.hierynomus.gradle.plugins.jython.JythonExtension
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.HttpHost
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

abstract class Repository implements Serializable {
    final Logger logger = Logging.getLogger(this.getClass())

    File resolve(JythonExtension extension, ExternalModuleDependency dep) {
        File cachePath = toCachePath(extension.pyCacheDir, dep)
        File cachedArtifact = listExistingArtifact(cachePath, dep)
        if (!cachedArtifact) {
            String url = getReleaseUrl(extension, dep)
            if (url) {
                logger.lifecycle("Downloading :${dep.name}:${dep.version} from $url")

                CloseableHttpClient httpClient = createHttpClient(extension)
                try {
                    HttpGet request = new HttpGet(url)
                    File resultFile = httpClient.execute(request, response -> {
                        int statusCode = response.getCode()
                        logger.lifecycle("Got HTTP response: ${statusCode} for url: $url")
                        
                        if (statusCode >= 200 && statusCode < 300) {
                            File targetFile
                            if (url.endsWith(".zip")) {
                                targetFile = new File(cachePath, artifactName(dep) + ".zip")
                            } else if (url.endsWith(".tar.gz")) {
                                targetFile = new File(cachePath, artifactName(dep) + ".tar.gz")
                            } else {
                                throw new IllegalArgumentException("Unknown Python artifact extension: $url for dependency $dep")
                            }
                            
                            if (!targetFile.getParentFile().exists()) {
                                targetFile.getParentFile().mkdirs()
                            }
                            
                            targetFile.withOutputStream { os ->
                                def is = new BufferedInputStream(response.getEntity().getContent())
                                os << is
                                is.close()
                            }
                            
                            logger.lifecycle("Successfully downloaded to: ${targetFile.absolutePath}")
                            return targetFile
                        } else {
                            logger.lifecycle("Non-success status code ${statusCode} for url: $url, trying next repository...")
                            return null
                        }
                    })
                    
                    if (resultFile) {
                        cachedArtifact = resultFile
                    }
                } catch (Exception e) {
                    logger.lifecycle("Failed to download from $url: ${e.class.name}: ${e.message}")
                    logger.debug("Full stack trace:", e)
                } finally {
                    httpClient.close()
                }
            } else {
                logger.lifecycle("No URL returned by getReleaseUrl for :${dep.name}:${dep.version}")
            }
        } else {
            logger.info("Using cached artifact $cachedArtifact for depedency :${dep.name}:${dep.version}")
        }
        return cachedArtifact
    }

    CloseableHttpClient createHttpClient(JythonExtension extension) {
        def builder = HttpClients.custom()
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setRedirectsEnabled(true)
                        .build())
        
        def p = extension.project
        
        if (p.hasProperty("systemProp.http.proxyHost")) {
            configureProxy(p, "http", builder)
        } else if (p.hasProperty("systemProp.https.proxyHost")) {
            configureProxy(p, "https", builder)
        }
        
        return builder.build()
    }

    private def configureProxy(Project project, String proxyScheme, def builder) {
        String proxyHost = project.property("systemProp.${proxyScheme}.proxyHost") as String
        int proxyPort = project.property("systemProp.${proxyScheme}.proxyPort") as Integer
        HttpHost proxy = new HttpHost(proxyScheme, proxyHost, proxyPort)
        builder.setProxy(proxy)
        
        if (project.hasProperty("systemProp.${proxyScheme}.proxyUsername")) {
            String username = project.property("systemProp.${proxyScheme}.proxyUsername") as String
            String password = project.property("systemProp.${proxyScheme}.proxyPassword") as String
            
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider()
            credentialsProvider.setCredentials(
                new AuthScope(proxyHost, proxyPort),
                new UsernamePasswordCredentials(username, password.toCharArray())
            )
            builder.setDefaultCredentialsProvider(credentialsProvider)
        }
    }

    /**
     * List the cache directory contents to check whether there is a pre-existing artifact
     * This is needed because we do not know beforehand without doing a network call what the
     * type of the artifact will be (zip, tar.gz, ...)
     *
     * @param artifactCachePath The path where the artifact is cached.
     * @param pythonDependency The dependency
     * @return The pre-existing artifact, or null
     */
    File listExistingArtifact(File artifactCachePath, ExternalModuleDependency pythonDependency) {
        String artifactName = artifactName(pythonDependency)
        def files = artifactCachePath.listFiles(new FilenameFilter() {
            @Override
            boolean accept(File dir, String name) {
                return name.startsWith(artifactName)
            }
        })
        if (files) {
            return files[0]
        } else {
            return null
        }
    }

    abstract String getReleaseUrl(JythonExtension extension, ExternalModuleDependency dep)

    String group(ExternalModuleDependency dep) {
        return dep.group
    }

    String artifactName(ExternalModuleDependency dep) {
        return "${dep.name}-${dep.version}"
    }

    private File toCachePath(File pyCacheDir, ExternalModuleDependency dep) {
        new File(pyCacheDir, "${group(dep)}/${dep.name}/${dep.version}")
    }

}
