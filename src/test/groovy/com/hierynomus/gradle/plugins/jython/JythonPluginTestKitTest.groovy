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
package com.hierynomus.gradle.plugins.jython

import com.xebialabs.restito.server.StubServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.jar.JarFile

import static com.xebialabs.restito.builder.stub.StubHttp.whenHttp
import static com.xebialabs.restito.semantics.Action.*
import static com.xebialabs.restito.semantics.Condition.*

/**
 * Integration tests using Gradle TestKit for proper task lifecycle testing.
 * This replaces the ProjectBuilder-based tests in JythonPluginTest with full
 * Gradle execution context, including task dependencies, up-to-date checks, and
 * proper task execution lifecycle.
 * 
 * All tests use a local StubServer to mock HTTP responses for controlled testing.
 */
class JythonPluginTestKitTest extends Specification {
    
    @TempDir
    Path testProjectDir
    File buildFile
    StubServer server

    def setup() {
        buildFile = new File(testProjectDir.toFile(), 'build.gradle')
        
        // Start stub server for mocking HTTP responses
        server = new StubServer()
        server.run()
        
        // Setup mock responses for test resources
        whenHttp(server)
            .match(get("/test/pylib/pylib-0.1.0.tar.gz"))
            .then(ok(), resourceContent("pylib-0.1.0.tar.gz"), contentType("application/tar+gz"))
        
        whenHttp(server)
            .match(get("/test/otherlib/otherlib-0.1.0.zip"))
            .then(ok(), resourceContent("otherlib-0.1.0.zip"), contentType("application/zip"))
        
        whenHttp(server)
            .match(get("/test/renamed-lib/renamed-lib-0.1.0.tar.gz"))
            .then(ok(), resourceContent("renamed-lib-0.1.0.tar.gz"), contentType("application/tar+gz"))
        
        whenHttp(server)
            .match(get("/redirect/pylib/pylib-0.1.0.tar.gz"))
            .then(status(org.glassfish.grizzly.http.util.HttpStatus.MOVED_PERMANENTLY_301), 
                  header("Location", "/test/pylib/pylib-0.1.0.tar.gz"))
    }

    def cleanup() {
        server?.stop()
    }

    /**
     * Returns the jython configuration block that points to the local stub server.
     * This is necessary to prevent the plugin from downloading from the actual PyPI.
     */
    private String getJythonConfig() {
        """
jython {
    sourceRepositories = [
        'http://localhost:${server.port}/\${dep.group}/\${dep.name}/\${dep.name}-\${dep.version}.tar.gz',
        'http://localhost:${server.port}/\${dep.group}/\${dep.name}/\${dep.name}-\${dep.version}.zip'
    ]
}
"""
    }

    def "should download defined jython library dependency"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython "test:pylib:0.1.0"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
        !new File(testProjectDir.toFile(), "build/jython/main/requirements.txt").exists()
    }

    def "should download jython library dependency not containing directory structure"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython "test:otherlib:0.1.0"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/otherlib/__init__.py").exists()
        !new File(testProjectDir.toFile(), "build/jython/main/requirements.txt").exists()
    }

    def "should bundle runtime deps in jar if Java plugin is applied"() {
        given:
        buildFile << """
plugins {
    id "java"
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython "test:pylib:0.1.0"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jar', '--stacktrace')
                .build()

        then:
        result.task(":jar").outcome == TaskOutcome.SUCCESS
        
        def jarFile = new File(testProjectDir.toFile(), "build/libs/${testProjectDir.toFile().name}.jar")
        jarFile.exists()
        
        def jarEntries = getJarEntries(jarFile)
        jarEntries.contains("pylib/__init__.py")
    }

    def "should handle python libraries which are redirected to another url"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython "redirect:pylib:0.1.0"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
    }

    def "should handle github python libraries for which the repository name is not the python module name"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython "test:renamed-lib:0.1.0:reallib"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/reallib/__init__.py").exists()
    }

    def "should support getting a single file from the module directory"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython("test:pylib:0.1.0") {
        artifact {
            name = "pylib/other-artifact"
            extension = "py"
        }
    }
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        !new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
        new File(testProjectDir.toFile(), "build/jython/main/pylib/other-artifact.py").exists()
    }

    def "should support getting a single file from the tar.gz"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython("test:pylib:0.1.0") {
        artifact {
            name = "artifact"
            extension = "py"
        }
    }
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        !new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
        new File(testProjectDir.toFile(), "build/jython/main/artifact.py").exists()
    }

    def "should work with new PythonDependency"() {
        given:
        buildFile << """
plugins {
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython python("test:pylib:0.1.0") {}
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
        !new File(testProjectDir.toFile(), "build/jython/main/requirements.txt").exists()
    }

    def "should define paths to copy in PythonDependency for extraction"() {
        given:
        buildFile << """
plugins {
    id "java"
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython python("test:pylib:0.1.0") {
        moduleName = "sublib"
        copy {
            from "src/sublib"
        }
    }
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        !new File(testProjectDir.toFile(), "build/jython/main/src/sublib/__init__.py").exists()
        new File(testProjectDir.toFile(), "build/jython/main/sublib/__init__.py").exists()
    }

    def "should define from/into to copy in PythonDependency for extraction"() {
        given:
        buildFile << """
plugins {
    id "java"
    id "com.github.hierynomus.jython"
}

${jythonConfig}

dependencies {
    jython python("test:pylib:0.1.0") {
        useModuleName = false
        copy {
            from "src/sublib"
            into "sublib"
        }
    }
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        !new File(testProjectDir.toFile(), "build/jython/main/src/sublib/__init__.py").exists()
        !new File(testProjectDir.toFile(), "build/jython/main/__init__.py").exists()
        new File(testProjectDir.toFile(), "build/jython/main/sublib/__init__.py").exists()
    }

    def "should be able to add string based repository"() {
        given:
        buildFile << """
plugins {
    id "java"
    id "com.github.hierynomus.jython"
}

jython {
    sourceRepositories = []
    repository 'http://localhost:${server.port}/\${dep.group}/\${dep.name}/\${dep.name}-\${dep.version}.tar.gz'
}

dependencies {
    jython "test:pylib:0.1.0"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
    }

    def "should use cache for python artifacts"() {
        given:
        def cacheDir = new File(testProjectDir.toFile(), "cache")
        def cachePath = new File(cacheDir, "test/cached/0.42.0")
        cachePath.mkdirs()
        
        // Copy test resource to cache
        def cachedFile = new File(cachePath, "cached-0.42.0.tar.gz")
        cachedFile.withOutputStream { out ->
            out << Thread.currentThread().getContextClassLoader().getResourceAsStream("pylib-0.1.0.tar.gz")
        }
        
        buildFile << """
plugins {
    id "java"
    id "com.github.hierynomus.jython"
}

jython {
    pyCacheDir = file("${cacheDir.absolutePath.replace('\\', '\\\\')}")
    sourceRepositories = [
        'http://localhost:${server.port}/\${dep.group}/\${dep.name}/\${dep.name}-\${dep.version}.tar.gz'
    ]
}

dependencies {
    jython "test:cached:0.42.0:pylib"
}
"""

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments('jythonDownload', '--stacktrace')
                .build()

        then:
        result.task(":jythonDownload").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir.toFile(), "build/jython/main/pylib/__init__.py").exists()
    }

    private List<String> getJarEntries(File jarFile) {
        def entries = []
        new JarFile(jarFile).withCloseable { jar ->
            jar.entries().each { entry ->
                entries.add(entry.name)
            }
        }
        return entries
    }
}
