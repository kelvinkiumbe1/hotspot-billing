package com.spalimited.hotspotbilling.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the built React app from the jar. Routing happens in the browser,
 * so a request for /admin or /tech has no matching file on disk — without
 * this, reloading the page or opening a link directly returns 404.
 *
 * <p>Each front-end route is forwarded explicitly rather than using a
 * catch-all: a wildcard would also swallow genuinely wrong URLs and
 * unmatched /api paths, turning a clear 404 into a silently blank page.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String route : new String[] { "/admin", "/tech", "/pay", "/my-account" }) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }
}
