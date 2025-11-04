/*
 * Copyright (C)2015 - Jeroen van Erp <jeroen@hierynomus.com>
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
import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class PypiRepository extends Repository {
    private static final Logger logger = Logging.getLogger(PypiRepository.class)

    String queryUrl

    private Template template

    PypiRepository() {
        this('https://pypi.org/pypi/${dep.name}/json')
    }

    PypiRepository(String queryUrl) {
        this.queryUrl = queryUrl
        def engine = new SimpleTemplateEngine()
        this.template = engine.createTemplate(queryUrl)
    }

    @Override
    String getReleaseUrl(JythonExtension extension, ExternalModuleDependency dep) {
        String queryUrl = template.make(['dep': dep]).toString()
        logger.debug("Querying PyPI: $queryUrl")
        
        CloseableHttpClient httpClient = createHttpClient(extension)
        try {
            HttpGet request = new HttpGet(queryUrl)
            return httpClient.execute(request, response -> {
                int statusCode = response.getCode()
                if (statusCode >= 200 && statusCode < 300) {
                    def jsonSlurper = new JsonSlurper()
                    def json = jsonSlurper.parse(response.getEntity().getContent())
                    
                    if (json.releases) {
                        def release_urls = json.releases[dep.version]
                        if (release_urls) {
                            for (u in release_urls) {
                                if (u['python_version'] == 'source') {
                                    return u['url']
                                }
                            }
                        }
                    }
                }
                return null
            })
        } finally {
            httpClient.close()
        }
    }

    @Override
    String group(ExternalModuleDependency dep) {
        if (dep.group) {
            return "pypi/" + dep.group
        } else {
            return "pypi"
        }
    }
}
