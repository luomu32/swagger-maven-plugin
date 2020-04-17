package xyz.luomu32.swagger;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.models.*;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.FormParameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.reflections.Reflections;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Parser {

    private final Log log;

    public Parser(Log log) {
        this.log = log;
    }

    public Swagger parse(MavenProject mavenProject, String scanPackage) {

        Info info = new Info()
                .title(mavenProject.getName())
                .description(mavenProject.getDescription());

        List licenses = mavenProject.getLicenses();
        if (null != licenses && !licenses.isEmpty()) {
            org.apache.maven.model.License license = (org.apache.maven.model.License) licenses.get(0);
            info.setLicense(new License().name(license.getName())
                    .url(license.getUrl()));
        }

        Swagger swagger = new Swagger();
        swagger.setInfo(info);

        try {
            List<Api> apis = fetchApi(scanPackage, mavenProject.getBuild().getOutputDirectory());

            Map<Class<?>/*controllerClass*/, List<Api>> apiMaps =
                    apis.stream().collect(Collectors.groupingBy(Api::getControllerClass));

            List<Tag> tags = new ArrayList<>();
            apiMaps.keySet().forEach(clazz -> {
                if (clazz.isAnnotationPresent(io.swagger.annotations.Api.class)) {
                    io.swagger.annotations.Api api = clazz.getAnnotation(io.swagger.annotations.Api.class);
                    if (api.tags() != null && api.tags().length != 0) {
                        tags.add(new Tag().name(api.tags()[0]));
                    }
                }
            });
            swagger.setTags(tags);
            Map<String, Path> paths = new HashMap<>();
            apis.forEach(api -> {
                Path path = new Path();
                for (String s : api.getMethod()) {
                    switch (s) {
                        case "GET":
                            path.get(new Operation().summary(api.getName()));
                            break;
                        case "POST":
                            path.post(new Operation().summary(api.getName()));
                            break;
                        case "PUT":
                            path.put(new Operation().summary(api.getName()));
                            break;
                        case "DELETE":
                            path.delete(new Operation().summary(api.getName()));
                            break;
                    }
                }
                path.setParameters(api.getParameters().stream().map(p -> {
                    io.swagger.models.parameters.Parameter parameter;
                    if (p.getType().equals("path")) {
                        parameter = new PathParameter();
                        parameter.setName(p.getName());
                        parameter.setDescription(p.getDesc());
                    } else if (p.getType().equals("body")) {
                        parameter = new BodyParameter();
                        parameter.setName(p.getName());
                        parameter.setDescription(p.getDesc());
                    } else if (p.getType().equals("query")) {
                        parameter = new QueryParameter();
                        parameter.setName(p.getName());
                        parameter.setDescription(p.getDesc());
                    } else {
                        parameter = new FormParameter();
                        parameter.setName(p.getName());
                        parameter.setDescription(p.getDesc());
                    }
                    return parameter;
                }).collect(Collectors.toList()));

                paths.put(api.getUrl(), path);
            });
            swagger.setPaths(paths);

        } catch (IOException e) {

        }
        return swagger;
    }

    public List<Api> fetchApi(String scanPackage, String outputDir) throws IOException {
        List<Api> apis = new ArrayList<>();

//        PathMatchingResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
//        Resource[] resources = resourcePatternResolver.getResources("file:" + outputDir + "/**/*.class");
//        FileClassLoader loader = new FileClassLoader(this.getClass().getClassLoader(), outputDir);
////
//        for (Resource resource : resources) {
//            Class<?> clazz;
//            String fullPath = resource.getFile().getAbsolutePath();
//            fullPath = fullPath.substring(outputDir.length() + 1);
//            fullPath = fullPath.substring(0, fullPath.lastIndexOf('.'));
//            fullPath = fullPath.replace('/', '.');
//
//            try {
//                clazz = loader.loadClass(resource.getFile(), fullPath);
//
//            } catch (ClassNotFoundException e) {
//                continue;
//            }
//            exact(clazz, apis);
//        }

        Set<Class<?>> classes = new Reflections(scanPackage).getTypesAnnotatedWith(io.swagger.annotations.Api.class, true);
        classes.forEach(clazz -> {
            exact(clazz, apis);
        });

        return apis;
    }

    private boolean isSpringMvcController(Class<?> clazz) {
        return clazz.isAnnotationPresent(RestController.class) ||
                clazz.isAnnotationPresent(Controller.class);
    }

    private boolean isHttpHandleMethod(Method method) {
        return method.isAnnotationPresent(RequestMapping.class) ||
                method.isAnnotationPresent(GetMapping.class) ||
                method.isAnnotationPresent(PostMapping.class) ||
                method.isAnnotationPresent(PutMapping.class) ||
                method.isAnnotationPresent(DeleteMapping.class);

    }

    private void exact(Class<?> clazz, List<Api> apis) {
        if (isSpringMvcController(clazz)) {
            log.info(clazz + " is spring mvc controller");
            String[] urlPrefix;
            RequestMapping classMapping = clazz.getAnnotation(RequestMapping.class);
            if (null != classMapping) {
                urlPrefix = (classMapping.value() == null || classMapping.value().length == 0) ? classMapping.path() : classMapping.value();
            } else {
                urlPrefix = null;
            }
            io.swagger.annotations.Api apiAnno = clazz.getAnnotation(io.swagger.annotations.Api.class);
            for (Method method : clazz.getMethods()) {
                if (method.isDefault())
                    continue;
                if (method.getName().equals("equals") ||
                        method.getName().equals("wait") ||
                        method.getName().equals("notify") ||
                        method.getName().equals("notifyAll") ||
                        method.getName().equals("toString") ||
                        method.getName().equals("hashCode") ||
                        method.getName().equals("getClass"))
                    continue;

                log.debug("handle method :" + method.getName());

                if (isHttpHandleMethod(method)) {
                    String[] methods;
                    String[] urls;
                    if (method.isAnnotationPresent(RequestMapping.class)) {
                        RequestMapping methodMapping = method.getAnnotation(RequestMapping.class);

                        methods = new String[methodMapping.method().length];
                        for (int i = 0; i < methodMapping.method().length; i++) {
                            methods[i] = methodMapping.method()[i].name();
                        }
                        urls = (methodMapping.value() == null || methodMapping.value().length == 0) ? methodMapping.path() : methodMapping.value();
                    } else if (method.isAnnotationPresent(GetMapping.class)) {
                        GetMapping methodMapping = method.getAnnotation(GetMapping.class);
                        methods = new String[]{RequestMethod.GET.name()};
                        urls = (methodMapping.value() == null || methodMapping.value().length == 0) ? methodMapping.path() : methodMapping.value();
                    } else if (method.isAnnotationPresent(PostMapping.class)) {
                        PostMapping methodMapping = method.getAnnotation(PostMapping.class);
                        methods = new String[]{RequestMethod.POST.name()};
                        urls = (methodMapping.value() == null || methodMapping.value().length == 0) ? methodMapping.path() : methodMapping.value();
                    } else if (method.isAnnotationPresent(PutMapping.class)) {
                        PostMapping methodMapping = method.getAnnotation(PostMapping.class);
                        methods = new String[]{RequestMethod.PUT.name()};
                        urls = (methodMapping.value() == null || methodMapping.value().length == 0) ? methodMapping.path() : methodMapping.value();
                    } else if (method.isAnnotationPresent(DeleteMapping.class)) {
                        PostMapping methodMapping = method.getAnnotation(PostMapping.class);
                        methods = new String[]{RequestMethod.DELETE.name()};
                        urls = (methodMapping.value() == null || methodMapping.value().length == 0) ? methodMapping.path() : methodMapping.value();
                    } else {
                        throw new UnsupportedOperationException();
                    }
                    ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
                    List<Parameter> parameters = exactParameter(method);
                    log.debug("exact parameter for method," + parameters.size());
                    if (null != urlPrefix) {
                        for (String prefix : urlPrefix) {
                            for (String s : urls) {
                                Api api = new Api();
                                api.setUrl(prefix + s);
                                api.setMethod(methods);
                                if (null != apiOperation)
                                    api.setName(apiOperation.value());
                                api.setControllerClass(clazz);
                                api.setParameters(parameters);
                                if (null != apiAnno && null != apiAnno.tags())
                                    api.setTag(apiAnno.tags()[0]);
                                apis.add(api);
                            }
                        }
                    } else {
                        for (String s : urls) {
                            Api api = new Api();
                            api.setUrl(s);
                            api.setMethod(methods);
                            if (null != apiOperation)
                                api.setName(apiOperation.value());
                            api.setControllerClass(clazz);
                            api.setParameters(parameters);
                            if (null != apiAnno && null != apiAnno.tags())
                                api.setTag(apiAnno.tags()[0]);
                            apis.add(api);
                        }
                    }
                }
            }
        }
    }

    private List<Parameter> exactParameter(Method method) {
        List<Parameter> parameters = new ArrayList<>();
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {

            if (parameter.getType().equals(HttpServletRequest.class) ||
                    parameter.getType().equals(HttpServletResponse.class) ||
                    parameter.getType().equals(HttpSession.class) ||
                    parameter.getType().equals(Principal.class) ||
                    parameter.getType().equals(ModelAndView.class) ||
                    parameter.getType().equals(Model.class) ||
                    parameter.getType().equals(View.class)) {
                continue;
            }

            Parameter param = new Parameter();
            if (parameter.isAnnotationPresent(PathVariable.class)) {
                param.setType("path");
            } else if (parameter.isAnnotationPresent(RequestBody.class)) {
                param.setType("body");
            } else if (parameter.getType().equals(MultipartFile.class) || parameter.getType().equals(Part.class)) {
                param.setType("file");
            } else if (parameter.isAnnotationPresent(RequestParam.class)) {
                param.setType("query");
            } else
                param.setType("form");
            param.setName(parameter.getName());
            param.setDataType(parameter.getType().toString());
            ApiParam apiParam = method.getAnnotation(ApiParam.class);
            if (null != apiParam)
                param.setDesc(apiParam.value());
            parameters.add(param);
        }
        return parameters;
    }

    private class FileClassLoader extends ClassLoader {

        private String path;

        public FileClassLoader(ClassLoader loader, String path) {
            super(loader);
            this.path = path;
        }

        public Class<?> loadClass(File file, String name) throws ClassNotFoundException {
            try {
                System.out.println("load file:" + file);
                byte[] bytes = Files.readAllBytes(file.toPath());
                return super.defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException();
            }
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            try {
                return super.loadClass(name);
            } catch (ClassNotFoundException e) {

                return this.loadClass(new File(path + "/" + name.replace('.', '/') + ".class"), name);
            }
        }
    }

    private class Api {
        private String url;
        private String[] method;
        private Class<?> controllerClass;
        private String name;
        private List<Parser.Parameter> parameters;
        private String tag;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String[] getMethod() {
            return method;
        }

        public void setMethod(String[] method) {
            this.method = method;
        }

        public Class<?> getControllerClass() {
            return controllerClass;
        }

        public void setControllerClass(Class<?> controllerClass) {
            this.controllerClass = controllerClass;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Parser.Parameter> getParameters() {
            return parameters;
        }

        public void setParameters(List<Parser.Parameter> parameters) {
            this.parameters = parameters;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }
    }

    private class Parameter {
        private String type;
        private String dataType;
        private String name;
        private String desc;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDesc() {
            return desc;
        }

        public void setDesc(String desc) {
            this.desc = desc;
        }
    }
}
