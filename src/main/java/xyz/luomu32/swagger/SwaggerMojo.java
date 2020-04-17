package xyz.luomu32.swagger;

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

import io.swagger.models.Swagger;
import io.swagger.util.Json;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Mojo(name = "generate",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        configurator = "include-project-dependencies"
)
public class SwaggerMojo extends AbstractMojo {

    @Parameter
    private String scanPackage;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject mavenProject;

    /**
     * Location of the swagger api file.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-api")
    private File outputDirectory;

    public void execute() throws MojoExecutionException {
        Swagger swagger = new Parser(getLog()).parse(mavenProject, scanPackage);

        File f = outputDirectory;

        if (!f.exists()) {
            f.mkdirs();
        }

        try {
            Files.write(Paths.get(f.toPath().toString(), "swagger.json"), Json.pretty(swagger).getBytes());
        } catch (IOException e) {
            throw new MojoExecutionException("can not write swagger api file to directory:" + outputDirectory.getName(), e);
        }
    }
}
