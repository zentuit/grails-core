/* Copyright 2004-2005 the original author or authors.
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
package org.codehaus.groovy.grails.web.servlet.view;

import grails.util.GrailsUtil;
import groovy.lang.GroovyObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.commons.GrailsResourceUtils;
import org.codehaus.groovy.grails.plugins.GrailsPluginManager;
import org.codehaus.groovy.grails.plugins.PluginManagerAware;
import org.codehaus.groovy.grails.web.pages.GroovyPagesTemplateEngine;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.grails.web.util.WebUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.AbstractUrlBasedView;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

/**
 * Evaluates the existance of a view for different extensions choosing which one to delegate to.
 *
 * @author Graeme Rocher
 * @since 0.1
 */
public class GrailsViewResolver extends InternalResourceViewResolver
       implements ResourceLoaderAware, ApplicationContextAware, PluginManagerAware {

    private String localPrefix;
    private static final Log LOG = LogFactory.getLog(GrailsViewResolver.class);

    public static final String GSP_SUFFIX = ".gsp";
    public static final String JSP_SUFFIX = ".jsp";

    private ResourceLoader resourceLoader;
    protected GroovyPagesTemplateEngine templateEngine;

    private static final String GROOVY_PAGE_RESOURCE_LOADER = "groovyPageResourceLoader";
    // no need for static cache since GrailsViewResolver is in app context
    private Map<String, View> VIEW_CACHE = new ConcurrentHashMap<String, View>();
    private static final char DOT = '.';
    private GrailsPluginManager pluginManager;

    /**
     * Constructor.
     */
    public GrailsViewResolver() {
        setCache(!GrailsUtil.isDevelopmentEnv());
    }

    @Override
    protected View loadView(String viewName, Locale locale) throws Exception {
        Assert.notNull(templateEngine, "Property [templateEngine] cannot be null");

        if (VIEW_CACHE.containsKey(viewName) && !templateEngine.isReloadEnabled()) {
            return VIEW_CACHE.get(viewName);
        }

        // try GSP if res is null

        GrailsWebRequest webRequest = WebUtils.retrieveGrailsWebRequest();

        HttpServletRequest request = webRequest.getCurrentRequest();
        GroovyObject controller = webRequest.getAttributes().getController(request);

        GrailsApplication application = getApplicationContext().getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class);

        ResourceLoader loader = establishResourceLoader(application);

        String format = request.getAttribute(GrailsApplicationAttributes.CONTENT_FORMAT) != null ?
                request.getAttribute(GrailsApplicationAttributes.CONTENT_FORMAT).toString() : null;
        String gspView = localPrefix + viewName + DOT + format + GSP_SUFFIX;
        Resource res = null;

        if (format != null) {
            res = loader.getResource(gspView);
            if (!res.exists()) {
                gspView = resolveViewForController(controller, application, viewName, loader);
                res = loader.getResource(gspView);
            }
        }

        if (res == null || !res.exists()) {
            gspView = localPrefix + viewName + GSP_SUFFIX;
            res = loader.getResource(gspView);
            if (!res.exists()) {
                gspView = resolveViewForController(controller, application, viewName, loader);
                res = loader.getResource(gspView);
            }
        }

        if (res.exists()) {
            final View view = createGroovyPageView(webRequest, gspView);
            VIEW_CACHE.put(viewName, view);
            return view;
        }

        AbstractUrlBasedView view = buildView(viewName);
        view.setApplicationContext(getApplicationContext());
        view.afterPropertiesSet();
        VIEW_CACHE.put(viewName, view);
        return view;
    }

    private View createGroovyPageView(GrailsWebRequest webRequest, String gspView) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Resolved GSP view at URI [" + gspView + "]");
        }
        GroovyPageView gspSpringView = new GroovyPageView();
        gspSpringView.setServletContext(webRequest.getServletContext());
        gspSpringView.setUrl(gspView);
        gspSpringView.setApplicationContext(getApplicationContext());
        gspSpringView.setTemplateEngine(templateEngine);
        return gspSpringView;
    }

    /**
     * Attempts to resolve a view relative to a controller.
     *
     * @param controller The controller to resolve the view relative to
     * @param application The GrailsApplication instance
     * @param viewName The views name
     * @param loader The ResourceLoader to use
     * @return The URI of the view
     */
    protected String resolveViewForController(GroovyObject controller, GrailsApplication application,
            String viewName, ResourceLoader loader) {

        String gspView;// try to resolve the view relative to the controller first, this allows us to support views provided by plugins
        if (controller != null && application != null) {
            String pathToView = pluginManager != null ? pluginManager.getPluginViewsPathForInstance(controller) : null;
            if (pathToView != null) {
                gspView = GrailsResourceUtils.WEB_INF + pathToView + viewName + GSP_SUFFIX;
            }
            else {
                gspView = localPrefix + viewName + GSP_SUFFIX;
            }
        }
        else {
            gspView = localPrefix + viewName + GSP_SUFFIX;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Attempting to resolve view for URI [" + gspView + "] using ResourceLoader [" +
                    loader.getClass().getName() + "]");
        }
        return gspView;
    }

    private ResourceLoader establishResourceLoader(GrailsApplication application) {
        ApplicationContext ctx = getApplicationContext();

        if (ctx.containsBean(GROOVY_PAGE_RESOURCE_LOADER) && application != null &&
                !application.isWarDeployed()) {
            return (ResourceLoader)ctx.getBean(GROOVY_PAGE_RESOURCE_LOADER);
        }
        return resourceLoader;
    }

    public void setPluginManager(GrailsPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public void setPrefix(String prefix) {
        super.setPrefix(prefix);
        this.localPrefix = prefix;
    }

    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public void setTemplateEngine(GroovyPagesTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }
}
